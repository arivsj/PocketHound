package com.pockethound.app.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockethound.app.core.model.ApprovalRequest
import com.pockethound.app.core.model.DecisaoDeAprovacao
import com.pockethound.app.core.model.DeskState
import com.pockethound.app.core.model.LinkState
import com.pockethound.app.core.model.Notice
import com.pockethound.app.core.model.PerguntaNaTela
import com.pockethound.app.core.model.Session
import com.pockethound.app.core.model.SessionSnapshot
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.Workspace
import com.pockethound.app.core.session.EstadoDaAtualizacao
import com.pockethound.app.core.session.PromptStatus
import com.pockethound.app.core.transport.TransportMode
import com.pockethound.app.core.session.PairingOutcome
import com.pockethound.app.core.session.SessionOrder
import com.pockethound.app.core.session.TurnStatus
import com.pockethound.app.data.repo.HoundRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado compartilhado pelas 4 abas.
 *
 * Enquanto não existe transporte, ele é a única fonte: todas as telas leem daqui.
 */
/** Estado da tela de pareamento. */
data class PairingUiState(
    val working: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val repository: HoundRepository,
    private val pairingClient: com.pockethound.app.core.session.PairingClient,
) : ViewModel() {
    val pairing: StateFlow<SessionSnapshot> = repository.pairing
    val link: StateFlow<LinkState> = repository.link
    val sessions: StateFlow<List<Session>> = repository.sessions
    val activeSessionId: StateFlow<String?> = repository.activeSessionId
    val transcript: StateFlow<Map<String, List<TurnItem>>> = repository.transcript
    val approvals: StateFlow<List<ApprovalRequest>> = repository.approvals

    /** O que aconteceu com cada decisão enviada, por requestId. */
    val decisoes: StateFlow<Map<String, DecisaoDeAprovacao>> = repository.decisoes

    /** Workspaces do Harness: onde uma sessão pode nascer. */
    val workspaces: StateFlow<List<Workspace>> = repository.workspaces

    /** Recado do último pedido de sessão nova, quando houve problema. */
    val criandoSessao: StateFlow<String?> = repository.criandoSessao

    /** O que houve com o último pedido de workspaces. */
    val recadoWorkspaces: StateFlow<String?> = repository.recadoWorkspaces

    /** Perguntas do agente esperando resposta do celular. */
    val perguntas: StateFlow<List<PerguntaNaTela>> = repository.perguntas

    /** Parar o turno e apagar a conversa da sessão ativa (diálogo "parar"). */
    fun limparConversa() = repository.limparConversa()

    fun responderPergunta(requestId: String, questionId: String, selecionadas: List<String>, textoLivre: String?) =
        repository.answerQuestion(requestId, questionId, selecionadas, textoLivre)

    fun pedirWorkspaces() = repository.pedirWorkspaces()

    fun criarSessao(workspaceId: String) = repository.criarSessao(workspaceId = workspaceId)
    val deskState: StateFlow<DeskState> = repository.deskState
    val notices: StateFlow<List<Notice>> = repository.notices

    /** O prompt em voo deu sinal de vida? A aba Chat avisa quando nao deu. */
    val promptStatus: StateFlow<PromptStatus> = repository.promptStatus

    /** A sessao de destino foi escolhida no dedo (true) ou pelo app (false). */
    val escolhaManual: StateFlow<Boolean> = repository.escolhaManual

    /** Estado do turno por sessao: trabalhando, tempo, passos, fila, tokens. */
    val turnStatus: StateFlow<Map<String, TurnStatus>> = repository.turnStatus

    /** O turno da sessao que esta na tela — e o que o cabecalho do chat mostra. */
    val activeTurnStatus: StateFlow<TurnStatus> = repository.turnStatus
        .map { mapa -> mapa[repository.activeSession()?.id] ?: TurnStatus() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TurnStatus())

    val isPaired: StateFlow<Boolean> = repository.pairing
        .map { it.isPaired }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pendingApprovals: StateFlow<Int> = repository.approvals
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Mesma regra do repositorio: a escolhida, senao a mais recente em atividade.
    val activeSession: StateFlow<Session?> = repository.sessions
        .map { list -> SessionOrder.resolve(list, repository.activeSessionId.value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectSession(sessionId: String) = repository.selectSession(sessionId)

    /**
     * Manda o prompt da tela.
     * @param text o rascunho.
     * @param furarFila se a mensagem entra no turno em curso, na frente da fila.
     */
    fun sendPrompt(text: String, furarFila: Boolean = false) = repository.sendPrompt(text, furarFila)

    fun cancelTurn() = repository.cancelTurn()

    /** O que houve com o último toque em "atualizar": a faixa do chat mostra. */
    val atualizacao: StateFlow<EstadoDaAtualizacao> = repository.atualizacao

    /** Pede ao PC o reenvio do que ele ainda guarda (o botão de atualizar). */
    fun atualizar() = repository.atualizar()

    /** Reconexão forçada (Torre): endpoint novo + laço religado. */
    fun reconectar() = repository.reconectar()

    fun decide(requestId: String, allowed: Boolean, remember: Boolean = false) =
        repository.decide(requestId, allowed, remember)

    fun setTransportMode(mode: TransportMode) = repository.setTransportMode(mode)

    private val _pairing = MutableStateFlow(PairingUiState())
    val pairingState: StateFlow<PairingUiState> = _pairing.asStateFlow()

    /**
     * Troca o codigo de 6 digitos pelo token e grava o vinculo.
     *
     * O token so existe na resposta desta chamada; a partir daqui ele vive no
     * Tink + Keystore e nunca mais aparece.
     *
     * @param address endereco do desk (`http://<ip>:<porta>`).
     * @param code codigo de 6 digitos mostrado no PC.
     */
    /**
     * Troca o codigo pelo token.
     *
     * **O tunel vem primeiro.** Se o QR trouxe o ticket, o pareamento funciona
     * de qualquer rede — que e o caso comum, com o PC no cabo e o telefone no
     * Wi-Fi. So quando nao ha ticket e que se tenta o caminho direto, que exige
     * os dois na mesma rede.
     *
     * @param address endereco do desk, quando conhecido (`http://<ip>:<porta>`).
     * @param code codigo de 6 digitos mostrado no PC.
     * @param ticket ticket do tunel, quando o QR trouxe um.
     */
    fun pair(address: String, code: String, ticket: String = "") {
        val endereco = address.trim().trimEnd('/')
        val temTicket = ticket.isNotBlank()

        if (code.trim().length != 6 || (!temTicket && endereco.isEmpty())) {
            _pairing.value = PairingUiState(
                error = if (temTicket) {
                    "Informe os 6 dígitos mostrados no PC."
                } else {
                    "Leia o QR do PC, ou informe o endereço e os 6 dígitos."
                },
            )
            return
        }

        _pairing.value = PairingUiState(working = true)
        viewModelScope.launch {
            val desfecho = if (temTicket) {
                // Pelo tunel nao ha o que sondar antes: o proprio handshake QUIC
                // ja e a sonda, e um ping separado so custaria uma ida e volta.
                pairingClient.pairOverTunnel(ticket, code, deviceName())
            } else {
                if (!pairingClient.ping(endereco)) {
                    _pairing.value = PairingUiState(
                        error = "Não achei o PC em " + endereco + ". Se ele estiver noutra rede, leia o QR.",
                    )
                    return@launch
                }
                pairingClient.pair(endereco, code, deviceName())
            }

            when (desfecho) {
                is PairingOutcome.Success -> {
                    markPaired(
                        deviceId = desfecho.deviceId,
                        pcName = desfecho.deviceName,
                        directBaseUrl = endereco,
                        token = desfecho.token,
                        p2pTicket = ticket,
                    )
                    _pairing.value = PairingUiState(done = true)
                }

                is PairingOutcome.Rejected -> _pairing.value = PairingUiState(error = desfecho.message)
                is PairingOutcome.Unreachable -> _pairing.value = PairingUiState(error = "Sem contato: " + desfecho.reason)
            }
        }
    }

    private fun deviceName(): String =
        (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim().ifBlank { "celular" }

    /**
     * Grava o pareamento.
     *
     * O [token] vem da resposta do PC e vai direto para o armazenamento seguro —
     * ele nao passa pelo DataStore nem por log. Os parametros sao nomeados de
     * proposito: com quatro Strings seguidas, trocar duas de lugar compila e
     * grava o segredo no campo errado.
     */
    fun markPaired(deviceId: String, pcName: String, directBaseUrl: String, token: String, p2pTicket: String = "") =
        repository.markPaired(
            deviceId = deviceId,
            pcName = pcName,
            directBaseUrl = directBaseUrl,
            token = token,
            p2pTicket = p2pTicket,
        )

    fun unpair() = repository.unpair()
}
