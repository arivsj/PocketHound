package com.pockethound.app.data.repo

import com.pockethound.app.core.model.ApprovalDecidePayload
import com.pockethound.app.core.model.ApprovalRequest
import com.pockethound.app.core.model.DecisaoDeAprovacao
import com.pockethound.app.core.model.DeskState
import com.pockethound.app.core.model.EstadoDaDecisao
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
import com.pockethound.app.core.session.PromptAck
import com.pockethound.app.core.session.PromptStatus
import com.pockethound.app.core.session.PromptWatchdog
import com.pockethound.app.core.session.SessionClient
import com.pockethound.app.core.session.SessionOrder
import com.pockethound.app.core.session.TranscriptReducer
import com.pockethound.app.core.session.TurnStatus
import com.pockethound.app.core.session.TurnStatusReducer
import com.pockethound.app.core.storage.SecureStore
import com.pockethound.app.core.storage.SettingsStorage
import com.pockethound.app.core.transport.TransportMode
import com.pockethound.app.core.transport.TransportResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    /**
     * O que aconteceu com cada decisão que saiu daqui, por requestId.
     *
     * A fila sozinha não conta essa história: o cartão fica igual enquanto o
     * comando falha em silêncio no caminho.
     */
    private val _decisoes = MutableStateFlow<Map<String, DecisaoDeAprovacao>>(emptyMap())
    val decisoes: StateFlow<Map<String, DecisaoDeAprovacao>> = _decisoes.asStateFlow()

    private val _deskState = MutableStateFlow(DeskState())
    val deskState: StateFlow<DeskState> = _deskState.asStateFlow()

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    private val _link = MutableStateFlow(LinkState(status = LinkStatus.Offline, path = "-"))

    /** Ligacao com o PC, traduzida do estado do cliente de sessao. */
    val link: StateFlow<LinkState> = _link.asStateFlow()

    /**
     * A escolha da sessao foi do humano?
     *
     * Enquanto for false, a sessao ativa acompanha a lista (a mais recente). Assim
     * que o humano toca num chip, a escolha dele manda — e o app para de mover o
     * destino debaixo do dedo.
     */
    private val _escolhaManual = MutableStateFlow(false)
    val escolhaManual: StateFlow<Boolean> = _escolhaManual.asStateFlow()

    /** Acompanhamento do prompt em voo, para a tela poder avisar o que travou. */
    private val _promptStatus = MutableStateFlow(PromptStatus())
    val promptStatus: StateFlow<PromptStatus> = _promptStatus.asStateFlow()

    /**
     * Estado do turno por sessao: trabalhando ou parado, ha quanto tempo, quantos
     * passos, quantas na fila e quantos tokens.
     *
     * A tela precisa disso para responder a pergunta que o usuario faz o tempo
     * todo: "esta acontecendo alguma coisa?".
     */
    private val _turnStatus = MutableStateFlow<Map<String, TurnStatus>>(emptyMap())
    val turnStatus: StateFlow<Map<String, TurnStatus>> = _turnStatus.asStateFlow()

    private val watchdog = PromptWatchdog()
    private var vigia: Job? = null

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
                // A ordem vem da ATIVIDADE de cada sessao, nao da chegada do
                // quadro: quem esta trabalhando agora fica no topo.
                _sessions.value = SessionOrder.order(
                    _sessions.value.filterNot { it.id == sessao.id } + sessao,
                )
                if (!_escolhaManual.value) {
                    _activeSessionId.value = SessionOrder.defaultActive(_sessions.value)?.id
                }
                // Sessao que o PC nao considera viva nao esta trabalhando — sem
                // isto a tela ficaria girando por uma sessao que ja morreu.
                _turnStatus.update { atual ->
                    atual + (sessao.id to TurnStatusReducer.sessao(
                        atual[sessao.id] ?: TurnStatus(),
                        sessao.status == SessionStatus.Running,
                    ))
                }
            }

            is IncomingFrame.SessionGone -> {
                val id = quadro.payload.id
                _sessions.value = _sessions.value.filterNot { it.id == id }
                _transcript.update { atual -> atual - id }
                _turnStatus.update { atual -> atual - id }
                if (_activeSessionId.value == id) {
                    // A sessao escolhida sumiu: volta a escolher sozinho, senao o
                    // destino fica apontando para o vazio.
                    _escolhaManual.value = false
                    _activeSessionId.value = SessionOrder.defaultActive(_sessions.value)?.id
                }
            }

            is IncomingFrame.TurnEvent -> {
                val id = quadro.session ?: _activeSessionId.value ?: return
                _transcript.update { atual ->
                    atual + (id to TranscriptReducer.reduce(atual[id].orEmpty(), quadro))
                }
                _turnStatus.update { atual ->
                    atual + (id to TurnStatusReducer.fold(atual[id] ?: TurnStatus(), quadro))
                }
                observarPrompt(id, quadro.payload.kind)
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
                _decisoes.update { atual -> atual - quadro.payload.requestId }
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
        lastSeen = lastSeen,
        createdAt = createdAt,
    )

    /**
     * Sessao de destino agora.
     *
     * Sem escolha do humano, vale a mais recente em atividade — nunca a primeira
     * que chegou, que era como o prompt ia parar numa sessao que ninguem pediu.
     */
    fun activeSession(): Session? = SessionOrder.resolve(_sessions.value, _activeSessionId.value)

    /**
     * Troca a sessao de destino.
     *
     * A partir daqui a escolha e do humano e a lista para de mover o destino
     * sozinha.
     */
    fun selectSession(sessionId: String) {
        _escolhaManual.value = true
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
     *
     * Aceito nao e respondido: por isso o [PromptWatchdog] entra em cena aqui. A
     * sessao que aceita o prompt e morre sem responder deixa a tela muda, e mudo e
     * indistinguivel de "esta pensando".
     */
    fun sendPrompt(text: String) {
        val limpo = text.trim()
        if (limpo.isEmpty()) return
        val sessao = activeSession() ?: return

        appendToTranscript(
            sessao.id,
            TurnItem(id = "local-" + System.nanoTime(), kind = TurnItemKind.UserMessage, text = limpo),
        )

        // Sessao ja trabalhando: o prompt entra na fila do proximo turno e a
        // demora e legitima. O prazo do vigia sabe disso.
        //
        // Quem responde isso e o TURNO, nao o status da sessao: o PC manda
        // "live" querendo dizer "esta sessao existe na memoria", e traduzir isso
        // como "ocupada" fazia o aviso de fila aparecer com o agente parado —
        // mentira que custou uma tarde de diagnostico.
        val ocupada = _turnStatus.value[sessao.id]?.running == true
        scope.launch {
            val envio = sessionClient.send(
                FrameType.PromptSend,
                PhCodec.payloadOf(PromptSendPayload(sessao.id, limpo, PromptMode.Followup)),
                sessao.id,
            )
            when (envio) {
                is TransportResult.Ok -> {
                    _promptStatus.value = watchdog.sent(sessao.id, limpo, ocupada, System.currentTimeMillis())
                    agendarVigia()
                }

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
        _decisoes.update { it + (requestId to DecisaoDeAprovacao(EstadoDaDecisao.Enviando)) }
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
            // A resposta do desk diz se o comando SAIU; quem decide de verdade e o
            // harness, e a confirmacao chega depois pelo quadro approval.resolved.
            // Por isso "entregue" nao e "aprovado" — a tela fala exatamente isso.
            val resultado = when (envio) {
                is TransportResult.Ok -> DecisaoDeAprovacao(
                    EstadoDaDecisao.Entregue,
                    "comando entregue ao PC em " + envio.latencyMs + " ms",
                )

                is TransportResult.HttpError -> DecisaoDeAprovacao(
                    EstadoDaDecisao.Recusada,
                    "o PC recusou (HTTP " + envio.code + "): " + envio.message.take(160),
                )

                is TransportResult.NetworkError -> DecisaoDeAprovacao(
                    EstadoDaDecisao.Recusada,
                    "não saiu do aparelho: " +
                        (envio.cause.message ?: envio.cause::class.simpleName.orEmpty()),
                )
            }
            _decisoes.update { it + (requestId to resultado) }
            if (resultado.estado == EstadoDaDecisao.Recusada) {
                avisar(NoticeLevel.Error, "A decisão não chegou ao PC", resultado.detalhe)
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
        _turnStatus.value = emptyMap()
        _approvals.value = emptyList()
        _decisoes.value = emptyMap()
        _activeSessionId.value = null
        _escolhaManual.value = false
        _promptStatus.value = watchdog.clear()
        vigia?.cancel()
        vigia = null
    }

    /**
     * Reavalia o prazo do prompt em voo.
     *
     * O laco acorda de tempo em tempo em vez de dormir o prazo inteiro: assim o
     * aviso aparece no instante certo e o contador da tela acompanha a espera.
     */
    private fun agendarVigia() {
        vigia?.cancel()
        vigia = scope.launch {
            while (true) {
                delay(PASSO_VIGIA_MS)
                val estado = watchdog.tick(System.currentTimeMillis())
                _promptStatus.value = estado
                if (estado.ack != PromptAck.Waiting) break
            }
            val estado = _promptStatus.value
            if (estado.ack == PromptAck.Silent || estado.ack == PromptAck.Stalled) avisarTrava(estado)
        }
    }

    /** Um quadro do PC chegou: ele diz se o prompt em voo ja foi respondido. */
    private fun observarPrompt(sessionId: String?, kind: String?) {
        if (_promptStatus.value.ack == PromptAck.None) return
        val depois = watchdog.frame(sessionId, kind, System.currentTimeMillis())
        _promptStatus.value = depois
        if (depois.ack == PromptAck.None) {
            vigia?.cancel()
            vigia = null
        }
    }

    /**
     * Traduz a trava num aviso.
     *
     * Os dois casos tem causas diferentes e o texto diz qual e qual: nenhum quadro
     * nenhum costuma ser transporte, e quadro de abertura sem producao e o
     * sintoma da sessao travada atras de um turno aberto.
     */
    private fun avisarTrava(estado: PromptStatus) {
        val segundos = (estado.waitedMs / 1000).toLong()
        val titulo = if (estado.ack == PromptAck.Stalled) {
            "A sessão começou e parou sem responder"
        } else {
            "O PC aceitou o prompt, mas a sessão não deu sinal"
        }
        avisar(
            NoticeLevel.Warn,
            titulo,
            segundos.toString() + " s de silêncio. A sessão pode estar travada atrás de um turno " +
                "aberto — toque em parar ou escolha outra sessão.",
        )
    }

    companion object {
        /** De quanto em quanto tempo o vigia reavalia o prazo. */
        const val PASSO_VIGIA_MS = 5_000L
    }

    private fun avisar(nivel: String, titulo: String, corpo: String) {
        _notices.update { atual -> (listOf(Notice(nivel, titulo, corpo)) + atual).take(20) }
    }

    private fun appendToTranscript(sessionId: String, item: TurnItem) {
        _transcript.update { atual -> atual + (sessionId to (atual[sessionId].orEmpty() + item)) }
    }
}
