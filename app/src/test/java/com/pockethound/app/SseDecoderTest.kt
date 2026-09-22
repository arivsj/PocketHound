package com.pockethound.app

import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.transport.SseDecoder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** O decodificador de SSE — a parte do transporte que erra fácil. */
class SseDecoderTest {

    private val quadro =
        """{"v":1,"seq":7,"ts":100,"type":"turn.event","session":"s1","payload":{"kind":"text.delta","text":"oi","turn":1,"step":1}}"""

    private fun decodificar(vararg linhas: String): List<IncomingFrame> =
        runBlocking { SseDecoder.decode(flowOf(*linhas)).toList() }

    @Test
    fun `um evento e emitido na linha vazia`() {
        val quadros = decodificar("id: 7", "data: $quadro", "")
        assertEquals(1, quadros.size)
        assertEquals(7L, quadros[0].seq)
    }

    @Test
    fun `o ultimo evento sem linha vazia nao se perde`() {
        // Sem isto, a última mensagem de um turno sumiria quando o PC fecha a
        // conexão logo depois de escrevê-la.
        val quadros = decodificar("data: $quadro")
        assertEquals(1, quadros.size)
    }

    @Test
    fun `batimento vira sinal de vida, nao em dado`() {
        // O comentário do SSE não é dado — não entra em transcrição nenhuma — mas
        // PRECISA chegar a quem mede silêncio: era descartado aqui dentro e uma
        // conexão saudável e parada era derrubada por "45 s sem nada" e refeita
        // de tempos em tempos.
        val quadros = decodificar(": beat", "", ": ping", "")
        assertEquals(2, quadros.size)
        assertTrue("nada de conteúdo, só vida", quadros.all { it is IncomingFrame.Beat })
        assertEquals("batimento não anda o cursor", 0L, quadros[0].seq)
    }

    @Test
    fun `linhas data do mesmo evento se concatenam`() {
        val metade1 = """{"v":1,"seq":9,"ts":1,"type":"notice","payload":"""
        val metade2 = """{"level":"info","title":"oi"}}"""
        val quadros = decodificar("data: $metade1", "data: $metade2", "")
        assertEquals(1, quadros.size)
        assertTrue(quadros[0] is IncomingFrame.Notice)
    }

    @Test
    fun `varios eventos na mesma leva saem em ordem`() {
        val segundo = """{"v":1,"seq":8,"ts":1,"type":"notice","payload":{"level":"info","title":"b"}}"""
        val quadros = decodificar("data: $quadro", "", ": beat", "", "data: $segundo", "")
        // O batimento no meio aparece na lista (é vida, não dado) e não desordena
        // os quadros de verdade.
        assertEquals(listOf(7L, 0L, 8L), quadros.map { it.seq })
        assertEquals(2, quadros.count { it !is IncomingFrame.Beat })
    }

    @Test
    fun `json quebrado nao derruba o fluxo`() {
        // Uma linha corrompida não pode apagar o que veio antes nem o que vem
        // depois — numa rede móvel isso acontece.
        val bom = """{"v":1,"seq":3,"ts":1,"type":"notice","payload":{"level":"info","title":"ok"}}"""
        val quadros = decodificar("data: {isso nao e json", "", "data: $bom", "")
        assertEquals(1, quadros.size)
        assertEquals(3L, quadros[0].seq)
    }

    @Test
    fun `tipo desconhecido e preservado como Unknown`() {
        // O PC pode ganhar quadros novos antes do app; descartá-los esconderia
        // funcionalidade em vez de degradar.
        val futuro = """{"v":1,"seq":4,"ts":1,"type":"algo.novo","payload":{"x":1}}"""
        val quadros = decodificar("data: $futuro", "")
        assertEquals(1, quadros.size)
        assertTrue(quadros[0] is IncomingFrame.Unknown)
        assertEquals("algo.novo", (quadros[0] as IncomingFrame.Unknown).type)
    }

    @Test
    fun `linha em branco sem data nao emite nada`() {
        assertEquals(0, decodificar("", "", "").size)
    }
}
