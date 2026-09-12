package com.pockethound.app

import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.TokenUsage
import com.pockethound.app.core.model.TurnEventPayload
import com.pockethound.app.core.model.TurnKind
import com.pockethound.app.core.session.TurnStatus
import com.pockethound.app.core.session.TurnStatusReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estado do turno — o que responde "esta acontecendo alguma coisa?".
 *
 * O relogio vem do proprio quadro, entao o mesmo log produz o mesmo estado e o
 * teste nao precisa esperar por tempo real.
 */
class TurnStatusTest {

    private fun quadro(payload: TurnEventPayload, ts: Long, kind: String = FrameType.TurnEvent): IncomingFrame =
        PhCodec.decode(PhCodec.outbound(kind, PhCodec.payloadOf(payload), session = "s1", seq = ts, ts = ts))
            ?: error("nao desserializou")

    private fun passo(atual: TurnStatus, payload: TurnEventPayload, ts: Long): TurnStatus =
        TurnStatusReducer.fold(atual, quadro(payload, ts))

    @Test
    fun aberturaDeTurnoMarcaTrabalhandoEAnotaOInstante() {
        val estado = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.TurnStart, turn = 3), ts = 1_000)

        assertTrue(estado.running)
        assertEquals(3, estado.turn)
        assertEquals(1_000L, estado.startedAt)
        assertEquals(3, estado.turns)
    }

    @Test
    fun decorridoSoContaComTurnoAberto() {
        val estado = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.TurnStart, turn = 1), ts = 1_000)

        assertEquals(2_500L, estado.decorrido(3_500))
        assertFalse(estado.copy(running = false).let { it.decorrido(9_999) } > 0)
    }

    @Test
    fun passosContamDentroDoTurno() {
        var estado = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.TurnStart, turn = 1), ts = 0)
        estado = passo(estado, TurnEventPayload(kind = TurnKind.StepStart, turn = 1, step = 1), ts = 10)
        estado = passo(estado, TurnEventPayload(kind = TurnKind.StepStart, turn = 1, step = 2), ts = 20)
        // Passo repetido nao conta duas vezes.
        estado = passo(estado, TurnEventPayload(kind = TurnKind.StepStart, turn = 1, step = 2), ts = 30)

        assertEquals(2, estado.steps)
    }

    @Test
    fun fimDeTurnoParaORelogio() {
        var estado = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.TurnStart, turn = 1), ts = 0)
        estado = passo(estado, TurnEventPayload(kind = TurnKind.TurnEnd, turn = 1), ts = 50)

        assertFalse(estado.running)
        assertEquals(0L, estado.startedAt)
        assertEquals(0L, estado.decorrido(9_999))
    }

    @Test
    fun tokensSomamNoPassoConsolidado() {
        val estado = passo(
            TurnStatus(),
            TurnEventPayload(
                kind = TurnKind.TextDone,
                turn = 1,
                step = 1,
                usage = TokenUsage(input = 120, output = 40, total = 160),
            ),
            ts = 10,
        )

        assertEquals(120L, estado.tokensIn)
        assertEquals(40L, estado.tokensOut)
    }

    @Test
    fun filaVemAbsolutaDoPc() {
        val estado = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.Inbox, queued = 2), ts = 10)

        assertEquals(2, estado.queued)
    }

    @Test
    fun sessaoQueOPcNaoConsideraVivaNaoFicaTrabalhando() {
        val aberto = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.TurnStart, turn = 1), ts = 0)

        assertFalse(TurnStatusReducer.sessao(aberto, viva = false).running)
        assertTrue(TurnStatusReducer.sessao(aberto, viva = true).running)
    }
}
