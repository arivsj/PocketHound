package com.pockethound.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.PromptSendPayload
import com.pockethound.app.core.session.PairingClient
import com.pockethound.app.core.session.PairingOutcome
import com.pockethound.app.core.storage.SecureStore
import com.pockethound.app.core.storage.SettingsStorage
import com.pockethound.app.core.transport.IrohEndpointProvider
import com.pockethound.app.core.transport.P2pTransport
import com.pockethound.app.core.transport.TransportResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O tunel P2P de verdade, do celular ao PC.
 *
 * Este teste roda **no aparelho** (ou no emulador) e e o unico lugar onde as
 * duas pontas se encontram: o app Android fala QUIC com a ponte Python que
 * aponta para um desk real. Nada aqui e simulado.
 *
 * Por que nao da para testar na JVM: o iroh e uma biblioteca nativa e falha com
 * UnsatisfiedLinkError fora do runtime do Android.
 *
 * Como rodar (o desk headless e a ponte precisam estar no ar):
 *
 *   node .dev/headless-desk.mjs 7451
 *   npm run p2p:serve
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.deskUrl=http://10.0.2.2:7451 \
 *     -Pandroid.testInstrumentationRunnerArguments.code=123456 \
 *     -Pandroid.testInstrumentationRunnerArguments.ticket=endpoint...
 */
@RunWith(AndroidJUnit4::class)
class P2pTunnelTest {

    private val argumentos = InstrumentationRegistry.getArguments()
    private val deskUrl = argumentos.getString("deskUrl") ?: "http://10.0.2.2:7451"
    private val codigo = argumentos.getString("code") ?: ""
    private val ticket = argumentos.getString("ticket") ?: ""

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
    fun oTunelP2pChegaAoDesk() = runBlocking {
        // Este teste precisa de ambiente (desk + ponte no ar). Sem os
        // argumentos, ele PULA em vez de falhar: um teste que reprova por falta
        // de setup ensina a ignorar a suíte inteira.
        org.junit.Assume.assumeTrue(
            "sem 'ticket'/'code': rode com os argumentos de instrumentação",
            ticket.isNotBlank() && codigo.isNotBlank(),
        )

        val settings = SettingsStorage(contexto)
        val seguro = SecureStore(contexto)
        val http = cliente()

        // 1. Pareia pelo caminho direto e guarda o token. Na vida real o QR
        //    faria isso; aqui o codigo vem por argumento para o teste ser
        //    reproduzivel.
        val desfecho = PairingClient(http, IrohEndpointProvider(seguro)).pair(deskUrl, codigo, "teste de tunel")
        assertTrue("o pareamento falhou: " + desfecho, desfecho is PairingOutcome.Success)
        val token = (desfecho as PairingOutcome.Success).token
        seguro.writeToken(token)
        settings.updatePairing(desfecho.deviceId, desfecho.deviceName, deskUrl, ticket)

        // 2. O transporte P2P, com a identidade propria do aparelho.
        val provider = IrohEndpointProvider(seguro)
        val p2p = P2pTransport(
            ticketProvider = { settings.read().p2pTicket },
            tokenProvider = { seguro.readToken() },
            provider = provider,
            json = json,
        )

        // 3. Sonda o tunel. Este passo ja prova que dois runtimes diferentes
        //    (Kotlin/JVM + Rust nativo de um lado, Python + Rust do outro)
        //    concordam no enquadramento e no ALPN.
        val saude = p2p.probe()
        assertTrue("o tunel nao respondeu: " + saude.reason, saude.reachable)
        assertEquals("p2p", saude.path)

        // 4. Um comando de verdade pelo tunel.
        val envio = p2p.request(
            com.pockethound.app.core.transport.TransportRequest(
                type = FrameType.PromptSend,
                payload = PhCodec.payloadOf(PromptSendPayload("sess-teste", "ola pelo tunel", "followup")),
                session = "sess-teste",
            ),
        )
        assertTrue("o comando falhou: " + envio, envio is TransportResult.Ok)

        // 5. E o stream — o que o projeto anterior nao conseguia.
        val recebidos = mutableListOf<IncomingFrame>()
        kotlinx.coroutines.withTimeoutOrNull(8_000) {
            p2p.events(0).collect { quadro ->
                recebidos.add(quadro)
                if (recebidos.size >= 2) throw kotlinx.coroutines.CancellationException("basta")
            }
        }
        assertTrue("o stream nao entregou nada", recebidos.isNotEmpty())
    }
}
