package com.pockethound.app

import com.pockethound.app.core.transport.P2pFraming
import com.pockethound.app.core.transport.P2pRequest
import com.pockethound.app.core.transport.P2pResponse
import java.nio.ByteBuffer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O enquadramento do tunel P2P.
 *
 * E a parte que os dois lados precisam concordar byte a byte: 4 bytes
 * big-endian de tamanho, depois JSON UTF-8. Um erro aqui nao aparece como
 * "formato errado" — aparece como a conexao travando sem responder, que e o
 * pior tipo de defeito para diagnosticar.
 *
 * O iroh nativo nao carrega numa JVM pura (UnsatisfiedLinkError), entao o
 * handshake de verdade so roda num aparelho. O que da para provar aqui e o
 * formato — e e o formato que costuma estar errado.
 */
class P2pFramingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun enquadrar(texto: String): ByteArray = P2pFraming.encode(texto.encodeToByteArray())

    @Test
    fun oPrefixoEOTamanhoEmBigEndian() {
        val bytes = enquadrar("abc")
        assertEquals("4 bytes de cabecalho + 3 de corpo", 7, bytes.size)
        assertEquals(3, ByteBuffer.wrap(bytes.copyOf(4)).int)
        assertEquals("abc", bytes.copyOfRange(4, 7).decodeToString())
    }

    @Test
    fun oTamanhoAguentaCorpoGrande() {
        // Um balão de resposta com markdown passa de 64 KiB; se o tamanho fosse
        // de 2 bytes, o frame seria silenciosamente cortado.
        val grande = "x".repeat(200_000)
        val bytes = enquadrar(grande)
        assertEquals(200_004, bytes.size)
        assertEquals(200_000, ByteBuffer.wrap(bytes.copyOf(4)).int)
    }

    @Test
    fun oLimiteRecusaFrameAbsurdo() {
        // O tamanho vem da rede: um valor corrompido nao pode virar uma
        // alocacao de gigabytes.
        var recusou = false
        try {
            P2pFraming.encode(ByteArray((1 shl 20) + 1))
        } catch (erro: IllegalArgumentException) {
            recusou = true
        }
        assertTrue("frame acima de 1 MiB tem de ser recusado", recusou)
    }

    @Test
    fun oPedidoSerializaComVExplícito() {
        // O PC responde 426 se \"v\" faltar, e o kotlinx.serialization omite
        // campos iguais ao default. Por isso \"v\" NAO tem valor padrao no modelo.
        val texto = json.encodeToString(
            P2pRequest.serializer(),
            P2pRequest(v = 1, method = "POST", path = "/ph/frame"),
        )
        assertTrue("o campo v precisa estar no JSON", texto.contains("\"v\":1"))
        assertTrue(texto.contains("\"/ph/frame\""))
    }

    @Test
    fun aRespostaDeStreamingSeReconhecePeloDone() {
        // O leitor so sabe que o stream acabou pelo \"done\". Sem isto ele
        // ficaria esperando para sempre num SSE que terminou.
        val evento = json.decodeFromString(P2pResponse.serializer(), """{"v":1,"event":"data: {}"}""")
        val fim = json.decodeFromString(P2pResponse.serializer(), """{"v":1,"status":200,"done":true}""")
        assertTrue("evento nao fecha o stream", !evento.done)
        assertTrue("o frame final fecha", fim.done)
        assertEquals("data: {}", evento.event)
    }
}
