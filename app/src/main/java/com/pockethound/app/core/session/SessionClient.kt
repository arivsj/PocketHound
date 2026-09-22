package com.pockethound.app.core.session

import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.PingPayload
import com.pockethound.app.core.storage.SecureStore
import com.pockethound.app.core.storage.SettingsStorage
import com.pockethound.app.core.transport.LanBeacon
import com.pockethound.app.core.transport.SelectedTransport
import com.pockethound.app.core.transport.TransportMode
import com.pockethound.app.core.transport.TransportRequest
import com.pockethound.app.core.transport.TransportResult
import com.pockethound.app.core.transport.TransportSelector
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Estado da ligação com o PC, para a tela mostrar a verdade. */
enum class ConnectionStatus { Offline, Conectando, Online, Reconectando }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.Offline,
    val path: String = "—",
    val endpoint: String? = null,
    val latencyMs: Long? = null,
    val cursor: Long = 0L,
    val reason: String? = null,
)

/**
 * Cliente de sessão — o único lugar que fala com o PC.
 *
 * Concentra as três coisas que, espalhadas, dão errado: **reconexão**,
 * **cursor** e **idempotência**. A tela só observa [frames] e [state]; nenhuma
 * tela abre conexão nem guarda posição de leitura.
 *
 * ## Por que o cursor mora aqui
 *
 * Existe **um único `seq` no sistema inteiro**, e ele nasce no plugin dentro do
 * harness. Trocar de rede — Wi-Fi para 4G, direto para P2P — **não perde nada**
 * porque o celular guarda o último `seq` que processou e pede o replay do
 * buraco. Se esse número vivesse numa tela, trocar de aba perderia mensagem.
 *
 * ## Sobre reconectar
 *
 * O laço é de fora para dentro de propósito: o [com.pockethound.app.core.transport.Transport]
 * entrega UMA conexão e termina quando ela cai. Assim a queda é um evento
 * visível (a tela mostra "reconectando") em vez de um detalhe escondido.
 */
@Singleton
class SessionClient @Inject constructor(
    private val settingsStorage: SettingsStorage,
    private val secureStore: SecureStore,
    private val client: HttpClient,
    private val selector: TransportSelector,
    private val lanBeacon: LanBeacon,
) {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // replay = 0: um quadro perdido na tela é pior que um quadro repetido, e o
    // consumidor sabe descartar o que já viu pelo `seq`.
    private val _frames = MutableSharedFlow<IncomingFrame>(replay = 0, extraBufferCapacity = 256)
    val frames: SharedFlow<IncomingFrame> = _frames.asSharedFlow()

    private var loop: Job? = null

    /** O fluxo aberto agora. O pedido de atualização derruba só ele. */
    @Volatile
    private var stream: Job? = null

    /**
     * Cursor pedido para a PRÓXIMA conexão, quando alguém quer rever o passado.
     *
     * Nulo no caminho normal: aí quem manda é o último `seq` processado.
     */
    @Volatile
    private var replayDe: Long? = null

    /** Quando o cursor foi para o disco pela última vez. */
    private var cursorGuardadoEm: Long = 0L

    /**
     * Liga o laço de conexão. Idempotente: chamar duas vezes não abre dois laços.
     *
     * @param scope escopo que sobrevive à troca de tela (o do processo).
     */
    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        // O farol sobe junto: ele não é um caminho, é o que diz QUAL é o
        // endereço do PC agora. Sem ele, o app só sabe o endereço que ficou
        // gravado no pareamento — e esse envelhece sem avisar.
        lanBeacon.start(scope)
        loop = scope.launch { executar() }
    }

    /**
     * Pede ao PC o reenvio a partir de [from] e derruba o fluxo atual.
     *
     * ## Por que derrubar o fluxo, e não abrir um segundo
     *
     * Dois fluxos do MESMO aparelho não convivem: o PC derruba o antigo quando
     * o novo chega (é assim que ele não acumula conexões mortas). Abrir um
     * segundo para ler o passado mataria o fluxo ao vivo. Então a atualização é
     * uma reconexão — a mesma que já existe para quando a rede cai, só que com
     * um cursor escolhido a dedo.
     *
     * `from = 0` quer dizer "do começo do que o PC ainda guarda". Pedir do
     * último `seq` visto não serve para o que este botão existe: é justamente o
     * buraco no meio da conversa que se quer fechar.
     *
     * @param from cursor do reenvio; 0 = o mais antigo que o PC ainda tem.
     */
    fun resync(from: Long = 0L) {
        replayDe = from.coerceAtLeast(0L)
        stream?.cancel()
    }

    /** Derruba o laço e marca offline. */
    fun stop() {
        lanBeacon.stop()
        loop?.cancel()
        loop = null
        _state.value = _state.value.copy(status = ConnectionStatus.Offline)
    }

    private suspend fun token(): String? = secureStore.readToken()

    private suspend fun executar() {
        var atraso = ATRASO_MINIMO_MS
        while (true) {
            val sessao = settingsStorage.read()
            _state.value = _state.value.copy(
                status = if (_state.value.cursor > 0) ConnectionStatus.Reconectando else ConnectionStatus.Conectando,
            )

            // Sem pareamento não há o que conectar; espera e tenta de novo, para
            // o app se recuperar sozinho depois de um pareamento.
            if (!sessao.isPaired) {
                _state.value = ConnectionState(status = ConnectionStatus.Offline, reason = "PC não pareado")
                delay(ATRASO_MAXIMO_MS)
                continue
            }

            // forceRefresh na segunda tentativa em diante: se o fluxo caiu, o
            // caminho que estava valendo deixou de ser confiavel.
            val selecionado: SelectedTransport =
                selector.select(client, ::token, forceRefresh = atraso > ATRASO_MINIMO_MS)
            _state.value = _state.value.copy(
                path = selecionado.health.path,
                endpoint = selecionado.health.endpoint,
                latencyMs = selecionado.health.latencyMs,
                reason = selecionado.health.reason,
            )

            if (!selecionado.health.reachable) {
                _state.value = _state.value.copy(status = ConnectionStatus.Offline)
                delay(atraso)
                atraso = (atraso * 2).coerceAtMost(ATRASO_MAXIMO_MS)
                continue
            }

            atraso = ATRASO_MINIMO_MS
            _state.value = _state.value.copy(status = ConnectionStatus.Online, reason = null)

            // O cursor DESTA conexão. Um pedido de atualização manda mais que o
            // último `seq` visto: é ele que traz de volta o que a tela perdeu.
            val cursor = replayDe?.also { replayDe = null } ?: _state.value.cursor

            try {
                coroutineScope {
                    // Ping de vida enquanto o fluxo estiver aberto.
                    //
                    // Serve a dois propositos: o desk fica sabendo que este aparelho
                    // esta vivo (e pode largar conexoes velhas com seguranca), e o
                    // pong que volta mantem o fluxo com sinal mesmo quando a sessao
                    // esta parada.
                    val batimento = launch {
                        while (isActive) {
                            delay(PING_MS)
                            selecionado.transport.request(
                                TransportRequest(FrameType.Ping, PhCodec.payloadOf(PingPayload(echo = "vivo"))),
                            )
                        }
                    }

                    try {
                        // O fluxo vive num Job proprio: é ele que o pedido de
                        // atualização derruba, sem levar junto o laço de reconexão.
                        val fluxo = launch {
                            selecionado.transport
                                .events(cursor)
                                // SEM SINAL por muito tempo = conexao morta. Rede movel
                                // troca de torre, NAT expira, o socket vira zumbi — e sem
                                // esta linha o app fica pendurado esperando bytes que nunca
                                // vem, com "ao vivo" na tela. O PC manda um batimento a cada
                                // 15 s, entao 45 s de silencio e definitivo. Antes disso, so
                                // reiniciar o app resolvia.
                                .timeout(SILENCIO_MAXIMO_MS.milliseconds)
                                .collect { quadro -> receber(quadro) }
                        }
                        stream = fluxo
                        // O pedido pode ter chegado durante o handshake, quando
                        // ainda não havia fluxo para derrubar. Sem esta linha ele
                        // esperaria a conexão cair sozinha para valer.
                        if (replayDe != null) fluxo.cancel()
                        fluxo.join()
                    } finally {
                        // Fecha o batimento JUNTO com o fluxo: sem isto, um laco de
                        // reconexao deixaria um ping por tentativa, e o desk veria um
                        // aparelho falante e nenhuma conexao.
                        batimento.cancel()
                        stream = null
                    }
                }
            } catch (cancelado: CancellationException) {
                // Cuidado com esta exceção: ela chega aqui por DOIS motivos que
                // pedem coisas opostas. `stop()` cancela este laço de fora, e aí
                // ele deve mesmo morrer. Já o prazo de silêncio
                // (TimeoutCancellationException, que TAMBÉM é uma
                // CancellationException) é justamente o que manda reconectar.
                // Rethrowar os dois mataria a conexão para sempre na primeira
                // rede ruim — o defeito que este app mais teme.
                if (!currentCoroutineContext().isActive) throw cancelado
                perdeuOCaminho("silêncio")
            } catch (erro: Throwable) {
                perdeuOCaminho(erro::class.simpleName)
            }
            delay(ATRASO_MINIMO_MS)
        }
    }

    /**
     * O fluxo caiu: marca "reconectando" e esquece o caminho escolhido.
     *
     * Esquecer é o ponto. A escolha entre direto e P2P vale um minuto, e ela só
     * é refeita quando expira. Se a rede mudou enquanto o fluxo estava de pé —
     * o caso clássico: o celular saiu do Wi-Fi e entrou no 4G —, insistir no
     * caminho velho custa dezenas de segundos de tela parada antes de alguém
     * sondar de novo.
     *
     * @param motivo o que derrubou o fluxo, para a tela contar.
     */
    private fun perdeuOCaminho(motivo: String?) {
        selector.invalidar()
        _state.value = _state.value.copy(status = ConnectionStatus.Reconectando, reason = motivo)
    }

    /**
     * Um quadro chegou: anda o cursor e entrega a quem desenha a tela.
     *
     * @param quadro quadro já decodificado.
     */
    private suspend fun receber(quadro: IncomingFrame) {
        // `seq == 0` é quadro efêmero do PC (estado da máquina, aviso): não faz
        // parte da linha do tempo e NÃO pode avançar o cursor, senão o replay
        // pediria um buraco que não existe.
        //
        // E o cursor só ANDA PARA A FRENTE: durante um reenvio pedido à mão
        // chegam quadros antigos, e deixá-los puxar o cursor para trás faria a
        // próxima reconexão pedir de novo o que já está na tela.
        if (quadro.seq > _state.value.cursor) {
            _state.value = _state.value.copy(cursor = quadro.seq)
            guardarCursor(quadro.seq)
        }
        // O PC renumerou (Harness reiniciado): o cursor guardado aponta para uma
        // numeração que não existe mais, e pedir "tudo depois de 117 mil" a um PC
        // que está no 12 mil não devolve nada — para sempre. O `replay.done` do
        // desk carrega o topo do anel dele: é a hora de voltar à realidade.
        if (quadro is IncomingFrame.ReplayDone && quadro.seq == 0L) {
            val topo = quadro.payload.to
            if (topo > 0L && topo + MarcaDoReplay.FOLGA_RENUMERACAO < _state.value.cursor) {
                _state.value = _state.value.copy(cursor = topo)
                guardarCursor(topo)
            }
        }
        _frames.emit(quadro)
    }

    /**
     * Guarda o cursor no disco, com folga entre uma escrita e a seguinte.
     *
     * O fluxo entrega dezenas de quadros por segundo, e uma ida ao disco por
     * quadro é trabalho jogado fora: o valor não é lido na abertura do app (o
     * replay começa do zero e é o buffer do PC que reconstrói a tela). Ele fica
     * para diagnóstico — e para o dia em que o app puder voltar de onde parou.
     *
     * @param seq cursor a guardar.
     */
    private suspend fun guardarCursor(seq: Long) {
        val agora = System.currentTimeMillis()
        if (agora - cursorGuardadoEm < INTERVALO_CURSOR_MS) return
        cursorGuardadoEm = agora
        settingsStorage.updateLastSeq(seq)
    }

    /**
     * Envia um comando ao PC pelo caminho ativo.
     *
     * @param type tipo do quadro (`prompt.send`, `approval.decide`, …).
     * @param payload conteúdo já tipado, serializado pelo codec.
     * @param session sessão alvo, quando o quadro se refere a uma.
     * @return o resultado da entrega.
     */
    suspend fun send(type: String, payload: JsonObject, session: String? = null): TransportResult {
        // Sem sonda: com o fluxo vivo, o caminho ja esta provado (a escolha fica
        // cacheada por um minuto). So quando o envio falha e que vale sondar tudo
        // de novo — e ai a proxima chamada paga a sonda uma vez, nao a cada tecla.
        val selecionado = selector.select(client, ::token)
        if (!selecionado.health.reachable) {
            selector.invalidar()
            return TransportResult.NetworkError(IllegalStateException("PC inalcançável"))
        }
        val resultado = selecionado.transport.request(TransportRequest(type, payload, session))
        if (resultado is TransportResult.NetworkError) selector.invalidar()
        return resultado
    }

    companion object {
        const val ATRASO_MINIMO_MS = 1000L
        const val ATRASO_MAXIMO_MS = 15000L

        /**
         * Quanto tempo de silencio no fluxo significa conexao morta.
         *
         * O PC manda batimento a cada 15 s; tres batimentos perdidos e definitivo.
         */
        const val SILENCIO_MAXIMO_MS = 45_000L

        /** De quanto em quanto o app avisa que esta vivo. */
        const val PING_MS = 25_000L

        /** Folga entre duas gravações do cursor no disco. */
        const val INTERVALO_CURSOR_MS = 5_000L
    }
}
