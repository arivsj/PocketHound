package com.pockethound.app.core.transport

import com.pockethound.app.core.storage.SettingsStorage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Um PC que se anunciou na rede local.
 *
 * @param host endereco de quem MANDOU o pacote — nunca o que o pacote diz.
 * @param porta porta do servidor do celular.
 * @param nome nome da maquina, como ela mesma se chama.
 * @param dispositivos aparelhos que aquele PC conhece. Vazio no anuncio antigo.
 * @param vistoEm quando o pacote chegou (ms).
 */
data class PcNaLan(
    val host: String,
    val porta: Int,
    val nome: String = "",
    val dispositivos: List<String> = emptyList(),
    val vistoEm: Long = 0L,
) {
    /** Endereco para falar com ele agora. */
    val baseUrl: String get() = "http://" + host + ":" + porta
}

/**
 * O que o PC manda no farol UDP.
 *
 * Campo desconhecido e ignorado (como em todo o protocolo): um desk mais novo
 * pode anunciar mais coisas sem quebrar um app mais velho.
 */
@Serializable
data class AnuncioNaLan(
    val service: String = "",
    val v: Int = 0,
    val port: Int = 0,
    val name: String = "",
    val mode: String = "",
    val at: Long = 0L,
    /** Ids dos aparelhos pareados NAQUELE PC. Opcional: o desk antigo nao manda. */
    val dev: List<String> = emptyList(),
)

/**
 * As regras do farol — puras de proposito.
 *
 * O transporte por rede local e HTTP claro com o token no cabecalho (risco ja
 * aceito e documentado em `network_security_config.xml`). Seguir um farol
 * automaticamente significa ESCOLHER para quem mandar esse token, entao a
 * decisao de confiar precisa ser explicita e testavel — nao um `if` perdido
 * dentro do laco do soquete.
 */
object Farol {

    /** O que o desk anuncia em `service`. */
    const val SERVICO = "pockethound"

    /** Porta UDP em que o PC grita. */
    const val PORTA = 7412

    /**
     * Quanto tempo um anuncio vale.
     *
     * O PC anuncia a cada 3 s. Quatro anuncios perdidos (rede ocupada, tela
     * apagada) ja sao motivo para parar de oferecer um endereco que talvez nao
     * exista mais.
     */
    const val VALIDADE_MS = 13_000L

    /**
     * Le o anuncio, se ele for deste protocolo e desta versao.
     *
     * @param texto corpo do pacote.
     * @param json codec do app.
     * @param host quem mandou.
     * @param agora instante da chegada.
     * @return o PC anunciado, ou null quando o pacote nao serve.
     */
    fun ler(texto: String, json: Json, host: String, agora: Long): PcNaLan? {
        val anuncio = runCatching { json.decodeFromString(AnuncioNaLan.serializer(), texto) }.getOrNull()
            ?: return null
        if (anuncio.service != SERVICO) return null
        // Versao maior = um PC mais novo do que este app entende. Melhor ignorar
        // do que falar um protocolo que mudou.
        if (anuncio.v <= 0 || anuncio.v > com.pockethound.app.core.model.PH_PROTOCOL_VERSION) return null
        if (anuncio.port !in 1..65535) return null
        if (host.isBlank()) return null
        return PcNaLan(
            host = host,
            porta = anuncio.port,
            nome = anuncio.name,
            dispositivos = anuncio.dev.map { it.trim() }.filter { it.isNotEmpty() },
            vistoEm = agora,
        )
    }

    /**
     * Este PC merece o token?
     *
     * Duas provas, nesta ordem:
     *
     * 1. **O PC conhece este aparelho.** Quando o anuncio traz a lista `dev` e o
     *    `deviceId` esta nela, nao ha duvida: e o PC em que este celular foi
     *    pareado. E a prova forte, porque o `deviceId` e um UUID que nao viaja em
     *    anuncio nenhum.
     * 2. **E o PC com quem ja pareamos**, pelo nome. E a prova fraca, e existe
     *    para o desk antigo (que ainda nao manda `dev`). Nome de maquina na rede e
     *    publico, entao ela sobe a barra — nao e um cadeado.
     *
     * Sem nenhuma das duas, um farol forjado na mesma rede faria o app entregar
     * o token (a unica credencial do Harness) a um estranho. Nesse caso o app
     * simplesmente nao usa o endereco descoberto.
     *
     * @param pc PC anunciado.
     * @param deviceId id deste aparelho, como o pareamento gravou.
     * @param pcPareado nome do PC com quem este aparelho foi pareado.
     * @param agora instante da decisao.
     * @return true quando da para usar o endereco anunciado.
     */
    fun confiavel(pc: PcNaLan, deviceId: String?, pcPareado: String?, agora: Long): Boolean {
        if (agora - pc.vistoEm > VALIDADE_MS) return false
        if (pc.dispositivos.isNotEmpty()) return deviceId != null && deviceId in pc.dispositivos
        val alvo = pcPareado?.trim().orEmpty()
        return alvo.isNotEmpty() && pc.nome.trim().equals(alvo, ignoreCase = true)
    }
}

/**
 * Escuta o farol do PC na rede local.
 *
 * Existe porque o app so sabia chegar ao PC pelo endereco que ficou gravado no
 * pareamento — e esse endereco envelhece (DHCP troca o IP do PC, o celular muda
 * de rede) sem que ninguem avise. O sintoma era o pior possivel: "estou em casa,
 * na mesma rede, e nao conecta".
 *
 * O PC ja grita na porta 7412 a cada 3 s (o desk faz isso desde sempre);
 * faltava alguem escutando.
 */
@Singleton
class LanBeacon @Inject constructor(
    private val settingsStorage: SettingsStorage,
    private val json: Json,
) {
    private val _pc = MutableStateFlow<PcNaLan?>(null)

    /** O PC visto agora na rede local, ou null quando nao ha nenhum. */
    val pc: StateFlow<PcNaLan?> = _pc.asStateFlow()

    private var escuta: Job? = null

    /**
     * Liga a escuta. Idempotente: chamar duas vezes nao abre dois socos.
     *
     * @param scope escopo do processo (o mesmo do cliente de sessao).
     */
    fun start(scope: CoroutineScope) {
        if (escuta?.isActive == true) return
        escuta = scope.launch(Dispatchers.IO) { escutar() }
    }

    /** Desliga a escuta e esquece o PC. */
    fun stop() {
        escuta?.cancel()
        escuta = null
        _pc.value = null
    }

    private suspend fun escutar() {
        while (currentCoroutineContext().isActive) {
            try {
                ouvir()
            } catch (cancelado: kotlinx.coroutines.CancellationException) {
                throw cancelado
            } catch (erro: Throwable) {
                // Rede caiu, Wi-Fi trocou, porta ocupada por outro app: espera um
                // pouco e tenta de novo. A descoberta e um conforto — nao pode
                // derrubar nada, e o P2P continua no lugar dela.
                _pc.value = null
                delay(TENTATIVA_MS)
            }
        }
    }

    /**
     * Abre o soquete e fica ouvindo ate dar erro ou ser cancelado.
     *
     * `soTimeout` curto de proposito: sem ele o `receive` bloquearia para
     * sempre e nao haveria como nem expirar o anuncio nem encerrar a escuta.
     */
    private suspend fun ouvir() {
        val soquete = DatagramSocket(null)
        try {
            soquete.reuseAddress = true
            soquete.broadcast = true
            soquete.soTimeout = PASSO_MS.toInt()
            soquete.bind(InetSocketAddress(Farol.PORTA))
            val buffer = ByteArray(1024)
            while (currentCoroutineContext().isActive) {
                val pacote = DatagramPacket(buffer, buffer.size)
                try {
                    soquete.receive(pacote)
                } catch (tempo: SocketTimeoutException) {
                    // Ninguem falou nesta janela: o PC anunciado pode ter saido.
                    if (expirou(_pc.value, System.currentTimeMillis())) _pc.value = null
                    continue
                }
                val host = pacote.address?.hostAddress ?: continue
                val texto = String(pacote.data, 0, pacote.length, Charsets.UTF_8)
                val agora = System.currentTimeMillis()
                val visto = Farol.ler(texto, json, host, agora) ?: continue
                val sessao = settingsStorage.read()
                if (!Farol.confiavel(visto, sessao.deviceId, sessao.pcName, agora)) continue
                _pc.value = visto
            }
        } finally {
            runCatching { soquete.close() }
        }
    }

    /** O anuncio ja venceu? */
    private fun expirou(pc: PcNaLan?, agora: Long): Boolean =
        pc != null && agora - pc.vistoEm > Farol.VALIDADE_MS

    private companion object {
        /** De quanto em quanto o `receive` desiste para olhar o relogio. */
        const val PASSO_MS = 1_000L

        /** Espera antes de reabrir o soquete, quando ele falha. */
        const val TENTATIVA_MS = 1_000L
    }
}
