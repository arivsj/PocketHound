package com.pockethound.app.data.repo

import com.pockethound.app.core.model.ApprovalDecidePayload
import com.pockethound.app.core.model.ApprovalRequest
import com.pockethound.app.core.model.DeskState
import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.LinkState
import com.pockethound.app.core.model.LinkStatus
import com.pockethound.app.core.model.Notice
import com.pockethound.app.core.model.NoticeLevel
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.PromptMode
import com.pockethound.app.core.model.PromptSendPayload
import com.pockethound.app.core.model.QuestionAnswerItem
import com.pockethound.app.core.model.QuestionAnswerPayload
import com.pockethound.app.core.model.Session
import com.pockethound.app.core.model.SessionCancelPayload
import com.pockethound.app.core.model.SessionSelectPayload
import com.pockethound.app.core.model.SessionSnapshot
import com.pockethound.app.core.model.SessionStatus
import com.pockethound.app.core.model.SessionUpsertPayload
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.session.ConnectionStatus
import com.pockethound.app.core.session.SessionClient
import com.pockethound.app.core.session.TranscriptReducer
import com.pockethound.app.core.storage.SecureStore
import com.pockethound.app.core.storage.SettingsStorage
import com.pockethound.app.core.transport.TransportMode
import com.pockethound.app.core.transport.TransportResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fonte unica de estado da UI.
 *
 * Nao abre conexao nem guarda posicao de leitura: quem faz isso e o
 * [SessionClient]. Aqui so DOBRAMOS os quadros que ele entrega nos estados que
 * as telas observam.
 *
 * O estado comeca VAZIO, nao com exemplo. Dado de exemplo numa tela que deveria
 * mostrar o PC e pior que tela vazia: o usuario nao sabe se esta vendo a maquina
 * dele ou uma demonstracao.
 */
@Singleton
class HoundRepository @Inject constructor(
    private val settingsStorage: SettingsStorage,
    private val secureStore: SecureStore,
    private val sessionClient: SessionClient,
) {
    // Escopo do processo: o estado compartilhado tem de sobreviver a troca de aba.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _transcript = MutableStateFlow<Map<String, List<TurnItem>>>(emptyMap())
    val transcript: StateFlow<Map<String, List<TurnItem>>> = _transcript.asStateFlow()

    private val _approvals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val approvals: StateFlow<List<ApprovalRequest>> = _approvals.asStateFlow()

    private val _deskState = MutableStateFlow(DeskState())
    val deskState: StateFlow<DeskState> = _deskState.asStateFlow()

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    private val _link = MutableStateFlow(LinkState(status = LinkStatus.Offline, path = "-"))

    /** Ligacao com o PC, traduzida do estado do cliente de sessao. */
    val link: StateFlow<LinkState> = _link.asStateFlow()

    val pairing: StateFlow<SessionSnapshot> = settingsStorage.session
        .stateIn(scope, SharingStarted.Eagerly, SessionSnapshot())

    init {
        // Um unico ponto de entrada dos quadros: tudo o que chega do PC passa
        // por aqui, na ordem em que o PC mandou.
        scope.launch {
            sessionClient.frames.collect { quadro -> aplicar(quadro) }
        }
        scope.launch {
            sessionClient.state.collect { estado ->
                _link.value = LinkState(
                    status = when (estado.status) {
                        ConnectionStatus.Online -> LinkStatus.Online
                        ConnectionStatus.Conectando, ConnectionStatus.Reconectando -> LinkStatus.Connecting
                        ConnectionStatus.Offline -> LinkStatus.Offline
                    },
                    path = estado.path,
                    latencyMs = estado.latencyMs,
                    reason = estado.reason,
                )
            }
        }
        sessionClient.start(scope)
    }

    /** Dobra um quadro do PC no estado da tela. */
    private fun aplicar(quadro: IncomingFrame) {
        when (quadro) {
            is IncomingFrame.SessionUpsert -> {
                val sessao = quadro.payload.paraSessao()
                _sessions.update { atual ->
                    // A sessao viva sobe para o topo: a lista existe para dizer
                    // onde o agente esta trabalhando agora.
                    (listOf(sessao) + atual.filterNot { it.id == sessao.id })
                        .sortedByDescending { it.status == SessionStatus.Running }
                }
                if (_activeSessionId.value == null) _activeSessionId.value = sessao.id
            }

            is IncomingFrame.SessionGone -> {
                val id = quadro.payload.id
                _sessions.update { atual -> atual.filterNot { it.id == id } }
                _transcript.update { atual -> atual - id }
                if (_activeSessionId.value == id) _activeSessionId.value = _sessions.value.firstOrNull()?.id
            }

            is IncomingFrame.TurnEvent -> {
                val id = quadro.session ?: _activeSessionId.value ?: return
                _transcript.update { atual ->
                    atual + (id to TranscriptReducer.reduce(atual[id].orEmpty(), quadro))
                }
            }

            is IncomingFrame.ApprovalRequest -> {
                val pedido = quadro.payload
                _approvals.update { atual ->
                    atual.filterNot { it.requestId == pedido.requestId } + ApprovalRequest(
                        requestId = pedido.requestId,
                        toolName = pedido.toolName,
                        callId = pedido.callId,
                        reason = pedido.reason,
                        argsPreview = pedido.args.toString().take(600),
                        sessionId = quadro.session,
                        expiresAt = pedido.expiresAt,
                    )
                }
            }

            is IncomingFrame.ApprovalResolved -> {
                // A decisao pode ter vindo do desktop ou de uma regra
                // 'nao perguntar de novo' - a fila esvazia de qualquer jeito.
                _approvals.update { atual -> atual.filterNot { it.requestId == quadro.payload.requestId } }
            }

            is IncomingFrame.DeskState -> {
                _deskState.value = DeskState.fromPayload(quadro.payload)
            }

            is IncomingFrame.Notice -> {
                avisar(
                    quadro.payload.level,
                    quadro.payload.title.ifBlank { "PocketHound" },
                    quadro.payload.body,
                )
            }

            is IncomingFrame.QuestionRequest -> {
                val primeira = quadro.payload.questions.firstOrNull()
                avisar(
                    NoticeLevel.Info,
                    primeira?.header ?: "Pergunta do agente",
                    primeira?.question.orEmpty(),
                )
            }

            else -> Unit
        }
    }

    /** Traduz o payload de sessao do protocolo no modelo da tela. */
    private fun SessionUpsertPayload.paraSessao(): Session = Session(
        id = id,
        title = title,
        workspace = workspace,
        // O PC fala 'live'/'cold'; a tela fala em estado de agente.
        status = when (status) {
            "live" -> SessionStatus.Running
            "cold" -> SessionStatus.Idle
            else -> SessionStatus.fromWire(status)
        },
        origin = origin,
        depth = depth,
        events = events,
    )

    fun activeSession(): Session? =
        _sessions.value.firstOrNull { it.id == _activeSessionId.value } ?: _sessions.value.firstOrNull()

    fun selectSession(sessionId: String) {
        _activeSessionId.value = sessionId
        scope.launch {
            sessionClient.send(
                FrameType.SessionSelect,
                PhCodec.payloadOf(SessionSelectPayload(sessionId)),
                sessionId,
            )
        }
    }

    /**
     * Manda o prompt e NAO inventa a resposta.
     *
     * A linha do usuario entra na tela na hora, como confirmacao local; o resto
     * vem do PC pelo mesmo caminho de todos os outros quadros. Ecoar uma resposta
     * falsa aqui faria a tela mentir quando o PC recusasse o comando.
     */
    fun sendPrompt(text: String) {
        val limpo = text.trim()
        if (limpo.isEmpty()) return
        val sessao = activeSession() ?: return

        appendToTranscript(
            sessao.id,
            TurnItem(id = "local-" + System.nanoTime(), kind = TurnItemKind.UserMessage, text = limpo),
        )

        scope.launch {
            val envio = sessionClient.send(
                FrameType.PromptSend,
                PhCodec.payloadOf(PromptSendPayload(sessao.id, limpo, PromptMode.Followup)),
                sessao.id,
            )
            when (envio) {
                is TransportResult.Ok -> Unit
                is TransportResult.HttpError ->
                    avisar(NoticeLevel.Error, "O PC recusou o prompt", "HTTP " + envio.code + ": " + envio.message)

                is TransportResult.NetworkError ->
                    avisar(NoticeLevel.Warn, "Sem contato com o PC", "O prompt não foi entregue.")
            }
        }
    }

    fun cancelTurn() {
        val sessao = activeSession() ?: return
        scope.launch {
            sessionClient.send(
                FrameType.SessionCancel,
                PhCodec.payloadOf(SessionCancelPayload(sessao.id)),
                sessao.id,
            )
        }
    }

    /**
     * Decide uma aprovacao.
     *
     * A fila NAO e esvaziada aqui: quem esvazia e o quadro approval.resolved que o
     * PC devolve. Tirar da tela antes de o PC confirmar esconderia uma falha de
     * rede - o usuario acharia que decidiu.
     */
    fun decide(requestId: String, allowed: Boolean, remember: Boolean = false) {
        scope.launch {
            val envio = sessionClient.send(
                FrameType.ApprovalDecide,
                PhCodec.payloadOf(
                    ApprovalDecidePayload(
                        requestId = requestId,
                        outcome = if (allowed) "allowed-once" else "rejected",
                        remember = remember,
                    ),
                ),
            )
            if (envio !is TransportResult.Ok) {
                avisar(NoticeLevel.Error, "A decisão não chegou ao PC", "A pergunta continua aberta.")
            }
        }
    }

    fun answerQuestion(requestId: String, questionId: String, selected: List<String>) {
        scope.launch {
            sessionClient.send(
                FrameType.QuestionAnswer,
                PhCodec.payloadOf(
                    QuestionAnswerPayload(requestId, listOf(QuestionAnswerItem(questionId, selected))),
                ),
            )
        }
    }

    fun markPaired(deviceId: String, pcName: String, directBaseUrl: String, token: String, p2pTicket: String = "") {
        // O token e segredo: vai para o Tink + Keystore, nunca para o DataStore.
        secureStore.writeToken(token)
        scope.launch { settingsStorage.updatePairing(deviceId, pcName, directBaseUrl, p2pTicket) }
        avisar(NoticeLevel.Success, "Pareado", pcName + " (" + deviceId + ")")
    }

    fun setTransportMode(mode: TransportMode) {
        scope.launch { settingsStorage.updateTransportMode(mode) }
    }

    fun unpair() {
        scope.launch {
            settingsStorage.clearPairing()
            secureStore.clear()
        }
        _sessions.value = emptyList()
        _transcript.value = emptyMap()
        _approvals.value = emptyList()
        _activeSessionId.value = null
    }

    private fun avisar(nivel: String, titulo: String, corpo: String) {
        _notices.update { atual -> (listOf(Notice(nivel, titulo, corpo)) + atual).take(20) }
    }

    private fun appendToTranscript(sessionId: String, item: TurnItem) {
        _transcript.update { atual -> atual + (sessionId to (atual[sessionId].orEmpty() + item)) }
    }
}
