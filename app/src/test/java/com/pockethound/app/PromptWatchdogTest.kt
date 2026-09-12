package com.pockethound.app

import com.pockethound.app.core.model.TurnKind
import com.pockethound.app.core.session.PromptAck
import com.pockethound.app.core.session.PromptWatchdog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O vigia do prompt em voo.
 *
 * O caso que originou isto, medido no log de uma sessao de verdade: o PC recebeu
 * o prompt, abriu o turno, abriu o passo e fechou o passo seis segundos depois —
 * sem produzir uma linha. A sessao ficou com o turno aberto e o celular, mudo.
 *
 * Um vigia que se contentasse com "chegou algum quadro" nao veria nada de errado
 * nisso; e por isso que os testes abaixo separam quadro de ABERTURA de quadro de
 * PRODUCAO.
 */
class PromptWatchdogTest {

    private val t0 = 1_000_000L

    @Test
    fun promptRecemEnviadoEstaEsperando() {
        val vigia = PromptWatchdog()
        val estado = vigia.sent("s1", "faz isso", queued = false, now = t0)

        assertEquals(PromptAck.Waiting, estado.ack)
        assertEquals("s1", estado.sessionId)
        assertEquals(0L, estado.waitedMs)
    }

    @Test
    fun prazoEstouradoSemNenhumQuadroViraSilent() {
        val vigia = PromptWatchdog(answerWindowMs = 1_000)
        vigia.sent("s1", "faz isso", queued = false, now = t0)

        assertEquals(PromptAck.Waiting, vigia.tick(t0 + 999).ack)
        assertEquals(PromptAck.Silent, vigia.tick(t0 + 1_000).ack)
    }

    @Test
    fun quadroDeOutraSessaoNaoSalvaOPrompt() {
        val vigia = PromptWatchdog(answerWindowMs = 1_000)
        vigia.sent("s1", "faz isso", queued = false, now = t0)

        vigia.frame("s2", TurnKind.TextDelta, now = t0 + 500)

        assertEquals("quadro de outra sessão não é resposta", PromptAck.Silent, vigia.tick(t0 + 1_000).ack)
    }

    @Test
    fun aberturaDeTurnoNaoBastaVirouTravada() {
        val vigia = PromptWatchdog(answerWindowMs = 1_000)
        vigia.sent("s1", "faz isso", queued = false, now = t0)

        // A assinatura exata da sessão que travou: turno e passo abrem e fecham.
        vigia.frame("s1", TurnKind.TurnStart, now = t0 + 10)
        vigia.frame("s1", TurnKind.StepStart, now = t0 + 20)
        vigia.frame("s1", TurnKind.UserMessage, now = t0 + 30)
        vigia.frame("s1", TurnKind.StepEnd, now = t0 + 40)

        assertEquals(PromptAck.Stalled, vigia.tick(t0 + 1_000).ack)
    }

    @Test
    fun textoOuFerramentaEncerramOVigia() {
        for (kind in listOf(TurnKind.TextDelta, TurnKind.TextDone, TurnKind.ReasoningDelta, TurnKind.ToolCall, TurnKind.TurnEnd)) {
            val vigia = PromptWatchdog(answerWindowMs = 1_000)
            vigia.sent("s1", "faz isso", queued = false, now = t0)

            val estado = vigia.frame("s1", kind, now = t0 + 100)

            assertEquals("kind " + kind, PromptAck.None, estado.ack)
            assertEquals("kind " + kind, PromptAck.None, vigia.tick(t0 + 60_000).ack)
        }
    }

    @Test
    fun sessaoJaOcupadaTemPrazoMaior() {
        val vigia = PromptWatchdog(answerWindowMs = 1_000, queueWindowMs = 5_000)
        vigia.sent("s1", "faz isso", queued = true, now = t0)

        assertEquals(PromptAck.Waiting, vigia.tick(t0 + 4_000).ack)
        assertEquals("na fila a demora é legítima", PromptAck.Silent, vigia.tick(t0 + 5_000).ack)

        val estado = vigia.tick(t0 + 5_000)
        assertEquals(true, estado.queued)
    }

    @Test
    fun quadroDeProducaoDepoisDoPrazoLimpaOAviso() {
        val vigia = PromptWatchdog(answerWindowMs = 1_000)
        vigia.sent("s1", "faz isso", queued = false, now = t0)
        assertEquals(PromptAck.Silent, vigia.tick(t0 + 2_000).ack)

        // O modelo acordou atrasado: o aviso some, não fica pregado na tela.
        assertEquals(PromptAck.None, vigia.frame("s1", TurnKind.TextDelta, now = t0 + 2_100).ack)
    }

    @Test
    fun clearEsqueceOPromptAnterior() {
        val vigia = PromptWatchdog(answerWindowMs = 1_000)
        vigia.sent("s1", "faz isso", queued = false, now = t0)

        val estado = vigia.clear()

        assertEquals(PromptAck.None, estado.ack)
        assertEquals(PromptAck.None, vigia.tick(t0 + 60_000).ack)
    }

    @Test
    fun promptNovoRecomecaORelogio() {
        val vigia = PromptWatchdog(answerWindowMs = 1_000)
        vigia.sent("s1", "primeiro", queued = false, now = t0)
        vigia.sent("s1", "segundo", queued = false, now = t0 + 900)

        assertEquals(PromptAck.Waiting, vigia.tick(t0 + 1_500).ack)
        assertEquals(PromptAck.Silent, vigia.tick(t0 + 1_900).ack)
    }
}
