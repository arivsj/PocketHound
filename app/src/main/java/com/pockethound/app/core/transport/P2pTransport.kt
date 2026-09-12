package com.pockethound.app.core.transport

import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.storage.SecureStore
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.RecvStream
import computer.iroh.SecretKey
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Enquadramento do tunel: 4 bytes big-endian de tamanho + JSON UTF-8.
 *
 * O mesmo do lado do PC. Um limite de 1 MiB por frame evita que um tamanho
 * corrompido vire uma alocacao de gigabytes — o numero vem da rede.
 */
object P2pFraming {
    private const val MAX_FRAME = 1 shl 20

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME) { "frame grande demais" }
        return ByteBuffer.allocate(4).putInt(payload.size).array() + payload
    }

    suspend fun decode(recv: RecvStream): ByteArray {
        val cabecalho = recv.readExact(4u)
        val tamanho = ByteBuffer.wrap(cabecalho).int
        require(tamanho in 1..MAX_FRAME) { "frame invalido: " + tamanho }
        return recv.readExact(tamanho.toUInt())
    }
}

/**
 * Pedido que atravessa o tunel.
 *
 * O campo [v] **nao tem valor padrao de proposito**: o kotlinx.serialization nao
 * grava campos iguais ao default, e um pedido sem `v` ja fez o PC responder 426
 * "atualize o app" em toda requisicao.
 */
@Serializable
data class P2pRequest(
    val v: Int,
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
)

/**
 * Resposta, ou pedaco dela.
 *
 * Uma resposta comum chega num frame com [done]. Um SSE chega em **varios**:
 * um por linha do stream, e o ultimo com [done]. E isto que permite chat ao
 * vivo pelo tunel — o projeto anterior so tinha request/response e um SSE
 * jamais passaria por ali.
 */
@Serializable
data class P2pResponse(
    val v: Int = 1,
    val status: Int = 0,
    val streaming: Boolean = false,
    val event: String? = null,
    val body: JsonElement? = null,
    val done: Boolean = false,
)

/**
 * Detem o endpoint QUIC do celular.
 *
 * O bind e unico e protegido por mutex: dois binds no mesmo processo com a mesma
 * chave disputam a identidade e o iroh recusa o segundo. A chave vive no
 * Tink + Keystore, como todo segredo do app.
 */
@Singleton
class IrohEndpointProvider @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val mutex = Mutex()

    @Volatile
    private var endpoint: Endpoint? = null

    suspend fun endpoint(): Endpoint = mutex.withLock {
        endpoint?.let { return it }
        val segredo = secureStore.readP2pKey() ?: SecretKey.generate().toBytes().also { secureStore.writeP2pKey(it) }
        val ligado = Endpoint.bind(EndpointOptions(secretKey = segredo, alpns = listOf(ALPN)))
        endpoint = ligado
        ligado
    }

    suspend fun endpointId(): String = endpoint().id().toString()

    companion object {
        /** O mesmo ALPN do outro lado. ALPN diferente = conexao recusada sem explicacao. */
        val ALPN = "pockethound/1".encodeToByteArray()
    }
}

private const val PROTOCOL_VERSION = 1

// Tentativa curta quando a conexao e reaproveitada: se o celular trocou de rede
// sem o Android avisar, a conexao antiga fica 'morta mas aberta' e o pedido
// ficaria pendurado. Assim ele cai rapido e reconecta.
private const val ATTEMPT_TIMEOUT_MS = 20_000L
private const val REQUEST_TIMEOUT_MS = 180_000L
private const val PROBE_TIMEOUT_MS = 15_000L

/**
 * Caminho P2P: QUIC pelo iroh, com hole punching e relay quando o CGNAT nao deixa.
 *
 * O transporte e **transparente**: dentro do tunel vao exatamente as mesmas
 * requisicoes do caminho direto, com o mesmo Bearer. Trocar de transporte nao
 * muda o que o PC ve — e por isso o token continua sendo o unico guardiao.
 */
class P2pTransport(
    private val ticketProvider: suspend () -> String,
    private val tokenProvider: suspend () -> String?,
    private val provider: IrohEndpointProvider,
    private val json: Json,
) : Transport {

    private var conexao: Connection? = null

    private suspend fun conectar(): Connection {
        conexao?.let { atual ->
            // Reaproveita enquanto estiver saudavel; closeReason != null e uma
            // conexao ja encerrada que responderia com erro.
            if (atual.closeReason() == null) return atual
        }
        val ticket = ticketProvider()
        require(ticket.isNotBlank()) { "sem ticket P2P: pareie de novo" }
        val alvo = EndpointTicket.fromString(ticket).endpointAddr()
        val nova = provider.endpoint().connect(alvo, IrohEndpointProvider.ALPN)
        conexao = nova
        return nova
    }

    /**
     * Um pedido cru pelo tunel: metodo, caminho e corpo.
     *
     * Existe porque nem tudo e comando. O pareamento, por exemplo, vai para
     * `/ph/pair` e nao leva token (ainda nao existe um). Sem esta porta, a
     * unica saida seria inventar campos no payload de `/ph/frame` — que e
     * exatamente o tipo de gambiarra que faz o protocolo apodrecer.
     *
     * @param method verbo HTTP.
     * @param path caminho no desk (`/ph/...`).
     * @param body corpo JSON, quando houver.
     * @param headers cabecalhos extras; o Authorization entra sozinho se houver token.
     */
    suspend fun chamar(
        method: String,
        path: String,
        body: kotlinx.serialization.json.JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ): TransportResult {
        val iniciado = System.currentTimeMillis()
        return try {
            val token = tokenProvider()
            val resposta = withTimeout(REQUEST_TIMEOUT_MS) {
                val conn = conectar()
                val bi = conn.openBi()
                val pedido = P2pRequest(
                    v = PROTOCOL_VERSION,
                    method = method,
                    path = path,
                    headers = if (token != null) headers + ("Authorization" to "Bearer " + token) else headers,
                    body = body,
                )
                bi.send().writeAll(
                    P2pFraming.encode(json.encodeToString(P2pRequest.serializer(), pedido).encodeToByteArray()),
                )
                bi.send().finish()
                lerAteOFim(bi.recv())
            }
            if (resposta.status in 200..299) {
                TransportResult.Ok(resposta.body?.toString(), System.currentTimeMillis() - iniciado)
            } else {
                TransportResult.HttpError(resposta.status, resposta.body?.toString()?.take(300) ?: "")
            }
        } catch (cancelado: CancellationException) {
            throw cancelado
        } catch (erro: Throwable) {
            conexao = null
            TransportResult.NetworkError(erro)
        }
    }

    override suspend fun request(call: TransportRequest): TransportResult {
        val iniciado = System.currentTimeMillis()
        return try {
            val resposta = withTimeout(REQUEST_TIMEOUT_MS) {
                val conn = conectar()
                val bi = conn.openBi()
                // O enquadramento recebe BYTES; o JSON sai como String.
                val corpo = json.encodeToString(P2pRequest.serializer(), pedido(call)).encodeToByteArray()
                bi.send().writeAll(P2pFraming.encode(corpo))
                bi.send().finish()
                lerAteOFim(bi.recv())
            }
            if (resposta.status in 200..299) {
                TransportResult.Ok(resposta.body?.toString(), System.currentTimeMillis() - iniciado)
            } else {
                TransportResult.HttpError(resposta.status, resposta.body?.toString()?.take(300) ?: "")
            }
        } catch (cancelado: CancellationException) {
            throw cancelado
        } catch (erro: Throwable) {
            // A conexao pode ter morrido no meio; descartar forca um handshake novo.
            conexao = null
            TransportResult.NetworkError(erro)
        }
    }

    /**
     * Fluxo do PC pelo tunel.
     *
     * O stream carrega **linhas de SSE**, exatamente como o caminho direto. Por
     * isso o decodificador e o mesmo ([SseDecoder]): o que muda e so por onde os
     * bytes viajam. Reaproveitar o decodificador e o que garante que trocar de
     * transporte nao mude o comportamento do app.
     */
    override fun events(cursor: Long): Flow<IncomingFrame> = flow {
        val token = tokenProvider()
        val conn = conectar()
        val bi = conn.openBi()
        val pedido = P2pRequest(
            v = PROTOCOL_VERSION,
            method = "GET",
            path = "/ph/stream",
            query = mapOf("cursor" to cursor.toString()),
            headers = if (token != null) mapOf("Authorization" to "Bearer " + token) else emptyMap(),
        )
        bi.send().writeAll(P2pFraming.encode(json.encodeToString(P2pRequest.serializer(), pedido).encodeToByteArray()))
        bi.send().finish()

        val recv = bi.recv()
        val linhas = flow {
            while (true) {
                val quadro = json.decodeFromString(
                    P2pResponse.serializer(),
                    P2pFraming.decode(recv).decodeToString(),
                )
                if (quadro.done) break
                quadro.event?.let { emit(it) }
            }
        }
        SseDecoder.decode(linhas).collect { emit(it) }
    }

    override suspend fun probe(): TransportHealth {
        val ticket = ticketProvider()
        if (ticket.isBlank()) {
            return TransportHealth(false, path = "p2p", reason = "sem ticket: pareie de novo")
        }
        val iniciado = System.currentTimeMillis()
        return try {
            val resposta = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                val conn = conectar()
                val bi = conn.openBi()
                val sonda = P2pRequest(v = PROTOCOL_VERSION, method = "GET", path = "/ph/ping")
                bi.send().writeAll(P2pFraming.encode(json.encodeToString(P2pRequest.serializer(), sonda).encodeToByteArray()))
                bi.send().finish()
                lerAteOFim(bi.recv())
            }
            if (resposta != null && resposta.status in 200..299) {
                TransportHealth(true, System.currentTimeMillis() - iniciado, "p2p", ticket.take(16))
            } else {
                TransportHealth(false, path = "p2p", endpoint = ticket.take(16), reason = "sem resposta")
            }
        } catch (erro: Throwable) {
            conexao = null
            TransportHealth(false, path = "p2p", endpoint = ticket.take(16), reason = erro::class.simpleName)
        }
    }

    /**
     * Monta o pedido do tunel para um comando.
     *
     * O corpo e o QUADRO INTEIRO — o mesmo JSON que o caminho direto posta em
     * `/ph/frame` — e nao o payload solto. O desk le `body.type` e
     * `body.payload` para saber o que fazer; mandar so o payload fazia TODO
     * comando chegar sem tipo e cair no "comando desconhecido" do outro lado.
     *
     * Era esse o defeito que fazia aprovar pelo celular nao funcionar: com o
     * transporte em P2P (fora da rede local, ou escolhido em Ajustes), o toque
     * saia do aparelho, era entregue e descartado em silencio — e a ponte
     * estourava o prazo como se ninguem tivesse respondido. O fluxo PC -> celular
     * continuava funcionando porque o SSE e um GET: nao passa por aqui.
     */
    private suspend fun pedido(call: TransportRequest): P2pRequest {
        val token = tokenProvider()
        val quadro = json.parseToJsonElement(PhCodec.outbound(call.type, call.payload, call.session))
        return P2pRequest(
            v = PROTOCOL_VERSION,
            method = "POST",
            path = "/ph/frame",
            headers = if (token != null) mapOf("Authorization" to "Bearer " + token) else emptyMap(),
            body = quadro,
        )
    }

    /** Le frames ate um com [P2pResponse.done], devolvendo o ultimo. */
    private suspend fun lerAteOFim(recv: RecvStream): P2pResponse {
        var ultimo = P2pResponse()
        while (true) {
            val quadro = json.decodeFromString(P2pResponse.serializer(), P2pFraming.decode(recv).decodeToString())
            ultimo = quadro
            if (quadro.done) return ultimo
        }
    }
}
