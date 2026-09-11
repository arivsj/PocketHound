package com.pockethound.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockethound.app.core.model.PairingPayload
import com.pockethound.app.core.session.PairingClient
import com.pockethound.app.core.session.PairingOutcome
import com.pockethound.app.core.storage.SecureStore
import com.pockethound.app.core.storage.SettingsStorage
import com.pockethound.app.core.transport.IrohEndpointProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O caminho do QR: ler o payload, parear pelo tunel, sair conectado.
 *
 * Este teste roda NO APARELHO e faz o que o usuario faz, menos a camera:
 * recebe o mesmo texto que o QR carrega, decodifica com o MESMO parser
 * ([PairingPayload]) e pareia pelo tunel P2P.
 *
 * E o caminho que resolve o caso comum: PC no cabo e telefone no Wi-Fi, em
 * redes diferentes. O pareamento direto nao funciona ali.
 *
 * Rodar (desk headless + ponte no ar):
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.payload='pockethound://pair?...'
 */
@RunWith(AndroidJUnit4::class)
class QrPairingTest {

    private val argumentos = InstrumentationRegistry.getArguments()
    private val payloadCru = argumentos.getString("payload") ?: ""

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun cliente(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
    }

    @Test
    fun oQrLevaAoPareamentoPeloTunel() = runBlocking {
        // Precisa de ambiente (desk + ponte no ar). Sem o payload, PULA: teste
        // que reprova por falta de setup ensina a ignorar a suíte.
        org.junit.Assume.assumeTrue(
            "sem 'payload': rode com os argumentos de instrumentação",
            payloadCru.isNotBlank(),
        )

        // 1. O mesmo parser que a tela usa no QR.
        val payload = PairingPayload.parse(payloadCru)
        assertNotNull("o payload do QR nao foi reconhecido", payload)
        assertTrue("o QR nao trouxe ticket", payload!!.ticket.isNotBlank())
        assertEquals("o QR nao trouxe o codigo", 6, payload.code?.length ?: 0)

        // 2. Pareamento pelo tunel — sem depender de mesma rede.
        val seguro = SecureStore(contexto)
        val settings = SettingsStorage(contexto)
        val clienteHttp = cliente()
        val pairing = PairingClient(clienteHttp, IrohEndpointProvider(seguro))

        val desfecho = pairing.pairOverTunnel(
            ticket = payload.ticket,
            code = payload.code!!,
            deviceName = "aparelho de teste (QR)",
        )
        assertTrue("o pareamento pelo tunel falhou: " + desfecho, desfecho is PairingOutcome.Success)
        val sucesso = desfecho as PairingOutcome.Success
        assertTrue("o PC nao devolveu token", sucesso.token.isNotBlank())

        // 3. Guarda o vinculo, como o app faria.
        seguro.writeToken(sucesso.token)
        settings.updatePairing(sucesso.deviceId, sucesso.deviceName, payload.address.orEmpty(), payload.ticket)
        assertEquals("o token nao foi guardado", sucesso.token, seguro.readToken())

        // 4. E o tunel ja funciona com esse vinculo.
        val transporte = com.pockethound.app.core.transport.P2pTransport(
            ticketProvider = { settings.read().p2pTicket },
            tokenProvider = { seguro.readToken() },
            provider = IrohEndpointProvider(seguro),
            json = json,
        )
        val saude = transporte.probe()
        assertTrue("o tunel nao respondeu apos parear: " + saude.reason, saude.reachable)
        assertEquals("p2p", saude.path)
    }
}
