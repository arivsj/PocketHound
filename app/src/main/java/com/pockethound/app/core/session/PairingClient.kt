package com.pockethound.app.core.session

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Resultado de uma tentativa de pareamento. */
sealed interface PairingOutcome {
    data class Success(val deviceId: String, val deviceName: String, val token: String) : PairingOutcome

    /** O PC respondeu e recusou: codigo errado, expirado ou tentativas demais. */
    data class Rejected(val message: String) : PairingOutcome

    /** Nem chegou a falar com o PC. */
    data class Unreachable(val reason: String) : PairingOutcome
}

@Serializable
private data class PairRequest(
    val code: String,
    val name: String,
    val fingerprint: String? = null,
)

@Serializable
private data class PairDevice(val id: String, val name: String)

@Serializable
private data class PairResponse(
    val ok: Boolean = false,
    val device: PairDevice? = null,
    /** Em claro SO nesta resposta; o desk guarda apenas o SHA-256. */
    val token: String? = null,
)

@Serializable
private data class PairErrorBody(val code: String = "", val message: String = "")

@Serializable
private data class PairErrorEnvelope(val error: PairErrorBody = PairErrorBody())

/**
 * Troca o codigo de 6 digitos pelo token do dispositivo.
 *
 * **A credencial aqui e o codigo, nao o IP.** Um codigo de uso unico, com TTL de
 * 120 s e teto de 5 tentativas no PC. Nao existe liberacao por estar 'na mesma
 * rede': foi exatamente por ali que o projeto anterior deixou entrar quem tinha
 * o ticket do no P2P.
 *
 * Roda antes de existir token, entao e a unica chamada do app sem Bearer.
 */
@Singleton
class PairingClient @Inject constructor(
    private val client: HttpClient,
    private val irohProvider: com.pockethound.app.core.transport.IrohEndpointProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Pede o pareamento **pelo tunel P2P**.
     *
     * E este o caminho que funciona quando o celular e o PC estao em redes
     * diferentes — o caso comum, e o que acontece quando o PC esta no cabo e o
     * telefone no Wi-Fi. A requisicao vai por QUIC ate o PC, e a ponte a entrega
     * ao mesmo \`/ph/pair\` do caminho direto.
     *
     * O codigo continua sendo a credencial: o tunel so leva a pergunta ate quem
     * pode responde-la.
     *
     * @param ticket ticket do iroh lido do QR.
     * @param code codigo de 6 digitos mostrado no PC.
     * @param deviceName nome que aparece na lista de dispositivos do PC.
     * @return o desfecho, nunca uma excecao.
     */
    suspend fun pairOverTunnel(
        ticket: String,
        code: String,
        deviceName: String,
        fingerprint: String? = null,
    ): PairingOutcome {
        if (ticket.isBlank()) return PairingOutcome.Unreachable("sem ticket no QR")
        val transporte = com.pockethound.app.core.transport.P2pTransport(
            ticketProvider = { ticket },
            tokenProvider = { null },
            provider = irohProvider,
            json = json,
        )
        // O corpo vai como o PC espera em /ph/pair: {code, name, fingerprint}.
        val corpo = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(code.trim()))
            put("name", kotlinx.serialization.json.JsonPrimitive(deviceName))
            if (fingerprint != null) put("fingerprint", kotlinx.serialization.json.JsonPrimitive(fingerprint))
        }
        return when (val envio = transporte.chamar("POST", "/ph/pair", corpo)) {
            is com.pockethound.app.core.transport.TransportResult.Ok -> interpretar(envio.raw)
            is com.pockethound.app.core.transport.TransportResult.HttpError ->
                PairingOutcome.Rejected(envio.message.ifBlank { "HTTP " + envio.code })

            is com.pockethound.app.core.transport.TransportResult.NetworkError ->
                PairingOutcome.Unreachable(envio.cause::class.simpleName ?: "falha no tunel")
        }
    }

    /** Le a resposta do pareamento, venha ela do tunel ou do caminho direto. */
    private fun interpretar(texto: String?): PairingOutcome {
        if (texto.isNullOrBlank()) return PairingOutcome.Rejected("resposta vazia do PC")
        return runCatching {
            val corpo = json.decodeFromString(PairResponse.serializer(), texto)
            val dispositivo = corpo.device
            val token = corpo.token
            if (corpo.ok && dispositivo != null && !token.isNullOrBlank()) {
                PairingOutcome.Success(dispositivo.id, dispositivo.name, token)
            } else {
                PairingOutcome.Rejected("o PC respondeu sem token")
            }
        }.getOrElse { PairingOutcome.Rejected("resposta ilegivel do PC") }
    }

    /**
     * Sonda o PC antes de tentar parear, para a tela poder dizer 'nao achei o PC'
     * em vez de 'codigo errado' quando o problema e outro.
     *
     * @param baseUrl endereco do desk, sem barra final.
     * @return true quando o servico responde.
     */
    suspend fun ping(baseUrl: String): Boolean = runCatching {
        client.get(baseUrl.trimEnd('/') + "/ph/ping").status.isSuccess()
    }.getOrDefault(false)

    /**
     * Pede o pareamento.
     *
     * @param baseUrl endereco do desk.
     * @param code codigo de 6 digitos mostrado no PC.
     * @param deviceName nome que aparece na lista de dispositivos do PC.
     * @param fingerprint identificacao opcional do aparelho.
     * @return o desfecho, nunca uma excecao.
     */
    suspend fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        fingerprint: String? = null,
    ): PairingOutcome = try {
        val resposta = client.post(baseUrl.trimEnd('/') + "/ph/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest(code = code.trim(), name = deviceName, fingerprint = fingerprint))
        }
        val texto = resposta.bodyAsText()
        if (resposta.status.isSuccess()) {
            val corpo = json.decodeFromString(PairResponse.serializer(), texto)
            val dispositivo = corpo.device
            val token = corpo.token
            if (corpo.ok && dispositivo != null && !token.isNullOrBlank()) {
                PairingOutcome.Success(dispositivo.id, dispositivo.name, token)
            } else {
                PairingOutcome.Rejected("o PC respondeu sem token")
            }
        } else {
            val erro = runCatching { json.decodeFromString(PairErrorEnvelope.serializer(), texto) }.getOrNull()
            PairingOutcome.Rejected(erro?.error?.message ?: ("HTTP " + resposta.status.value))
        }
    } catch (erro: Throwable) {
        PairingOutcome.Unreachable(erro::class.simpleName ?: "falha de rede")
    }
}
