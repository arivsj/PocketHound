package com.pockethound.app

import com.pockethound.app.core.model.DeskStatePayload
import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.TurnEventPayload
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.model.TurnKind
import com.pockethound.app.core.session.TranscriptReducer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regra de juntar deltas — a parte da UI que mais erra.
 *
 * O PC emite o texto do agente em pedaços de ~40 ms. Se cada pedaço virasse um
 * balão, uma resposta de três parágrafos encheria a tela de balões de duas
 * palavras.
 */
class TranscriptReducerTest {

    /**
     * Monta o quadro com o PRÓPRIO codificador, não com string na mão.
     *
     * A primeira versão deste teste montava o JSON por concatenação e errava em
     * silêncio: o texto de um valor podia conter o marcador do próximo. Usar o
     * [PhCodec] elimina essa classe de erro — e de quebra testa o caminho de
     * serialização de verdade.
     */
    private fun quadroDeTurno(payload: TurnEventPayload, seq: Long): IncomingFrame =
        PhCodec.decode(PhCodec.outbound(FrameType.TurnEvent, PhCodec.payloadOf(payload), session = "s1", seq = seq))
            ?: error("não desserializou")

    private fun delta(
        texto: String,
        turn: Int = 1,
        step: Int = 1,
        index: Int = 0,
        seq: Long = 1,
        tipo: String = TurnKind.TextDelta,
    ): IncomingFrame = quadroDeTurno(
        TurnEventPayload(kind = tipo, turn = turn, step = step, index = index, text = texto),
        seq,
    )

    private fun done(
        texto: String,
        reasoning: String? = null,
        turn: Int = 1,
        step: Int = 1,
        seq: Long = 9,
    ): IncomingFrame = quadroDeTurno(
        TurnEventPayload(kind = TurnKind.TextDone, turn = turn, step = step, at = seq, text = texto, reasoning = reasoning),
        seq,
    )

    @Test
    fun deltasDoMesmoPassoViramUmBalaoSo() {
        var t = emptyList<TurnItem>()
        t = TranscriptReducer.reduce(t, delta("Vou "))
        t = TranscriptReducer.reduce(t, delta("compilar ", seq = 2))
        t = TranscriptReducer.reduce(t, delta("o projeto.", seq = 3))

        assertEquals("um balão só", 1, t.size)
        assertEquals("Vou compilar o projeto.", t[0].text)
        assertTrue("ainda recebendo deltas", t[0].streaming)
    }

    @Test
    fun passoNovoAbreBalaoNovo() {
        var t = TranscriptReducer.reduce(emptyList(), delta("primeiro", step = 1))
        t = TranscriptReducer.reduce(t, delta("segundo", step = 2, seq = 2))

        assertEquals(2, t.size)
        assertEquals("primeiro", t[0].text)
        assertEquals("segundo", t[1].text)
    }

    @Test
    fun turnoNovoAbreBalaoNovo() {
        var t = TranscriptReducer.reduce(emptyList(), delta("turno um", turn = 1))
        t = TranscriptReducer.reduce(t, delta("turno dois", turn = 2, seq = 2))

        assertEquals(2, t.size)
    }

    @Test
    fun oConsolidadoSubstituiOMontadoPorDeltas() {
        // Sem isto o markdown final sai cortado: os deltas não têm como saber
        // que "**bo" + "ld**" formava um negrito.
        var t = TranscriptReducer.reduce(emptyList(), delta("**bo"))
        t = TranscriptReducer.reduce(t, delta("ld**", seq = 2))
        assertEquals("**bold**", t[0].text)

        t = TranscriptReducer.reduce(t, done("**bold**"))
        assertEquals("um balão só, sem duplicar", 1, t.size)
        assertEquals("**bold**", t[0].text)
        assertFalse("parou de receber deltas", t[0].streaming)
    }

    @Test
    fun raciocinioTemBalaoProprioENaoSeMisturaComAResposta() {
        var t = TranscriptReducer.reduce(emptyList(), delta("Preciso ver o build", tipo = "reasoning.delta"))
        t = TranscriptReducer.reduce(t, delta("Vou compilar.", seq = 2))

        assertEquals(2, t.size)
        assertEquals(TurnItemKind.Reasoning, t[0].kind)
        assertEquals(TurnItemKind.AssistantMessage, t[1].kind)
        assertEquals("Preciso ver o build", t[0].text)
    }

    @Test
    fun oRaciocinioDoFechamentoEntraQuandoOsDeltasDeleNaoVieram() {
        val t = TranscriptReducer.reduce(emptyList(), done("resposta", reasoning = "porque sim"))
        assertEquals(2, t.size)
        assertEquals(TurnItemKind.Reasoning, t[0].kind)
        assertEquals("porque sim", t[0].text)
        assertEquals("resposta", t[1].text)
    }

    @Test
    fun fimDeTurnoParaOCursor() {
        // Se continuar streaming, a tela pisca o cursor para sempre.
        var t = TranscriptReducer.reduce(emptyList(), delta("texto"))
        assertTrue(t[0].streaming)
        t = TranscriptReducer.reduce(
            t,
            quadroDeTurno(TurnEventPayload(kind = TurnKind.TurnEnd, turn = 1, reason = "completed"), 9),
        )
        assertFalse(t[0].streaming)
    }

    @Test
    fun chamadaDeFerramentaViraLinhaComNomeEArgumentos() {
        val t = TranscriptReducer.reduce(
            emptyList(),
            quadroDeTurno(
                TurnEventPayload(
                    kind = TurnKind.ToolCall,
                    turn = 1,
                    step = 1,
                    callId = "c1",
                    name = "bash",
                    args = JsonObject(mapOf("command" to JsonPrimitive("ls"))),
                ),
                3,
            ),
        )
        assertEquals(1, t.size)
        assertEquals(TurnItemKind.ToolCall, t[0].kind)
        assertEquals("bash", t[0].toolName)
        assertTrue("os argumentos precisam aparecer", t[0].text.contains("ls"))
    }

    @Test
    fun resultadoComErroViraErrorNaoToolResult() {
        val t = TranscriptReducer.reduce(
            emptyList(),
            quadroDeTurno(
                TurnEventPayload(
                    kind = TurnKind.ToolResult,
                    turn = 1,
                    step = 1,
                    callId = "c1",
                    isError = true,
                    errorCode = "ENOENT",
                    text = "não encontrado",
                ),
                4,
            ),
        )
        assertEquals(TurnItemKind.Error, t[0].kind)
        assertFalse(t[0].ok!!)
    }

    @Test
    fun quadroQueNaoEDeTurnoNaoMexeNaTranscricao() {
        val atual = TranscriptReducer.reduce(emptyList(), delta("oi"))
        val depois = TranscriptReducer.reduce(
            atual,
            PhCodec.decode(
                PhCodec.outbound(
                    FrameType.DeskState,
                    PhCodec.payloadOf(DeskStatePayload(cores = 16, cpuPercent = 12.5)),
                ),
            )!!,
        )
        assertEquals("a mesma lista, sem cópia", atual, depois)
    }
}
