package com.pockethound.app.core.transport

import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.storage.SettingsStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Modo de transporte escolhido em Ajustes (ARQUITETURA.md §7). */
enum class TransportMode {
    /** Sonda o direto com 1,5 s de limite e cai para P2P. */
    AUTO,

    /** Só HTTP + SSE em http://<ip>:<porta>. */
    DIRECT_ONLY,

    /** Só QUIC (iroh): hole punching, relay quando não dá. */
    P2P_ONLY,
    ;

    companion object {
        fun fromWire(value: String?): TransportMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** Um quadro de saída a ser entregue ao PC. */
data class TransportRequest(
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val session: String? = null,
)

sealed interface TransportResult {
    data class Ok(val raw: String?, val latencyMs: Long) : TransportResult

    data class HttpError(val code: Int, val message: String) : TransportResult

    data class NetworkError(val cause: Throwable) : TransportResult
}

data class TransportHealth(
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val path: String,
    val endpoint: String? = null,
    val reason: String? = null,
)

/**
 * Canal entre o celular e o PocketHound desk.
 *
 * Regra herdada dos projetos anteriores: **transporte seguro não é autorização**.
 * O canal protege o caminho; o handshake com token protege o comando. Toda
 * conexão — direta ou P2P — passa pelo mesmo hello/token.
 *
 * **Nunca se confia no endereço de origem.** Estar na mesma rede não autoriza
 * nada; só o token autoriza. Foi a falha crítica do projeto anterior, onde um
 * proxy local fazia requisição remota chegar como loopback e ganhar as rotas
 * administrativas sem token.
 */
interface Transport {
    /** Entrega um comando ao PC. */
    suspend fun request(call: TransportRequest): TransportResult

    /**
     * Fluxo de quadros do PC, **a partir do cursor**.
     *
     * O fluxo termina quando a conexão cai — quem recoloca de pé é o cliente de
     * sessão, que guarda o cursor e chama de novo. Aqui dentro não há laço de
     * reconexão de propósito: um transporte que se reconecta sozinho esconde a
     * queda de quem precisa saber dela para mostrar "reconectando" na tela.
     */
    fun events(cursor: Long): Flow<IncomingFrame>

    /** Sonda o caminho com limite curto, para o modo AUTO decidir. */
    suspend fun probe(): TransportHealth
}

/**
 * Caminho direto: HTTP + SSE em `http://<ip>:<porta>` (ARQUITETURA.md §7).
 *
 * O token vai em `Authorization: Bearer` em toda chamada, inclusive o SSE — a
 * rota `/ph/stream` não é pública.
 *
 * @param baseUrl endereço do desk, sem barra final.
 * @param tokenProvider token do dispositivo, lido do armazenamento seguro.
 * @param client cliente Ktor compartilhado (timeouts e conversores já montados).
 */
class DirectTransport(
    private val baseUrl: String,
    private val tokenProvider: suspend () -> String?,
    private val client: HttpClient,
) : Transport {

    private val raiz: String = baseUrl.trimEnd('/')

    override suspend fun request(call: TransportRequest): TransportResult {
        val iniciado = System.currentTimeMillis()
        return try {
            val token = tokenProvider()
            val resposta = client.post("$raiz/ph/frame") {
                contentType(ContentType.Application.Json)
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                setBody(PhCodec.outbound(call.type, call.payload, call.session))
            }
            val texto = resposta.bodyAsText()
            if (resposta.status.isSuccess()) {
                TransportResult.Ok(texto, System.currentTimeMillis() - iniciado)
            } else {
                TransportResult.HttpError(resposta.status.value, texto.take(300))
            }
        } catch (erro: Throwable) {
            TransportResult.NetworkError(erro)
        }
    }

    override fun events(cursor: Long): Flow<IncomingFrame> = flow {
        val token = tokenProvider()
        val resposta: HttpResponse = client.get("$raiz/ph/stream?cursor=$cursor") {
            header(HttpHeaders.Accept, "text/event-stream")
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!resposta.status.isSuccess()) {
            throw IllegalStateException("stream recusado: ${resposta.status.value}")
        }
        val canal = resposta.bodyAsChannel()
        // Lê linha a linha e entrega ao decodificador. O canal fica aberto por
        // desenho: é SSE, a conexão dura o turno inteiro.
        val linhas = flow {
            while (true) {
                val linha = canal.readUTF8Line() ?: break
                emit(linha)
            }
        }
        SseDecoder.decode(linhas).collect { emit(it) }
    }

    override suspend fun probe(): TransportHealth {
        val iniciado = System.currentTimeMillis()
        return try {
            val resposta = client.get("$raiz/ph/ping") {
                // Limite curto: quem está fora da LAN não pode esperar 10 s
                // para o app descobrir que precisa cair para o P2P.
                timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS }
            }
            if (resposta.status.isSuccess()) {
                TransportHealth(
                    reachable = true,
                    latencyMs = System.currentTimeMillis() - iniciado,
                    path = "direct",
                    endpoint = raiz,
                )
            } else {
                TransportHealth(false, path = "direct", endpoint = raiz, reason = "HTTP ${resposta.status.value}")
            }
        } catch (erro: Throwable) {
            TransportHealth(false, path = "direct", endpoint = raiz, reason = erro::class.simpleName)
        }
    }

    companion object {
        /** Limite da sonda do modo AUTO, como no seletor do projeto anterior. */
        const val PROBE_TIMEOUT_MS = 1500L
    }
}

data class SelectedTransport(
    val mode: TransportMode,
    val transport: Transport,
    val health: TransportHealth,
)

/**
 * Escolhe o transporte conforme o modo salvo em Ajustes.
 *
 * No modo AUTO sonda o direto com limite curto e cai para o P2P. A ordem importa:
 * na mesma rede o direto é mais rápido e não depende de relay; fora dela o
 * direto nem responde, e insistir nele custaria a paciência do usuário.
 */
@Singleton
class TransportSelector @Inject constructor(
    private val settingsStorage: SettingsStorage,
    private val irohProvider: IrohEndpointProvider,
    private val json: Json,
) {
    /**
     * A escolha vale por um minuto.
     *
     * Sondar a rede a cada comando custa caro e, pior, faz o app parecer quebrado:
     * um POST que deveria sair em 150 ms esperava a sonda do caminho direto (1,5 s)
     * ou, quando ela falhava, a do túnel (ate 15 s). Com o fluxo vivo, o caminho
     * ja esta provado — nao ha o que sondar de novo a cada tecla.
     */
    private var cache: Pair<SelectedTransport, Long>? = null

    /** Esquece a escolha; o proximo envio sonda de novo. */
    fun invalidar() {
        cache = null
    }

    /**
     * @param client cliente Ktor já montado.
     * @param tokenProvider token do dispositivo.
     * @param forceRefresh sonda de novo mesmo com a escolha ainda valida.
     */
    suspend fun select(
        client: HttpClient,
        tokenProvider: suspend () -> String?,
        forceRefresh: Boolean = false,
    ): SelectedTransport {
        val agora = System.currentTimeMillis()
        cache?.let { (escolhido, quando) ->
            if (!forceRefresh && agora - quando < VALIDADE_MS) return escolhido
        }
        val sessao = settingsStorage.read()
        val direto = DirectTransport(sessao.directBaseUrl, tokenProvider, client)
        // O ticket e lido na hora, nao capturado: o pareamento pode acontecer
        // depois de o seletor existir.
        val p2p = P2pTransport(
            ticketProvider = { settingsStorage.read().p2pTicket },
            tokenProvider = tokenProvider,
            provider = irohProvider,
            json = json,
        )

        val escolhido = when (sessao.transportMode) {
            TransportMode.DIRECT_ONLY -> SelectedTransport(sessao.transportMode, direto, direto.probe())

            TransportMode.P2P_ONLY -> SelectedTransport(sessao.transportMode, p2p, p2p.probe())

            TransportMode.AUTO -> {
                val saude = direto.probe()
                if (saude.reachable) {
                    SelectedTransport(sessao.transportMode, direto, saude)
                } else {
                    SelectedTransport(sessao.transportMode, p2p, p2p.probe())
                }
            }
        }
        cache = escolhido to agora
        return escolhido
    }

    companion object {
        /** Por quanto tempo a escolha do caminho vale, em ms. */
        const val VALIDADE_MS = 60_000L
    }
}
