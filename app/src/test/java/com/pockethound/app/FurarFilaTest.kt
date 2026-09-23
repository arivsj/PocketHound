package com.pockethound.app

import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.Frame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.PromptMode
import com.pockethound.app.core.model.PromptSendPayload
import com.pockethound.app.core.session.PromptAck
import com.pockethound.app.core.session.PromptWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O "furar fila": a mensagem entra no turno em curso, na frente de quem esperava.
 *
 * Ele nao e um botao de enviar a mais — e uma MARCA do envio. O que se prova aqui
 * e que a marca chega ao PC como `steer` (o caminho que o plugin entrega em
 * `next-step`, e nao em `next-turn`) e que o aviso da tela conta a verdade sobre
 * isso: quem fura fila nao "entrou na fila do proximo turno".
 */
class FurarFilaTest {

    @Test
    fun marcadoVaiComoSteer() {
        assertEquals(PromptMode.Steer, PromptMode.de(furarFila = true))
    }

    @Test
    fun desmarcadoVaiComoFollowup() {
        assertEquals(PromptMode.Followup, PromptMode.de(furarFila = false))
    }

    @Test
    fun oQuadroLevadoAoPcCarregaOSteer() {
        val raw = PhCodec.promptSend("sess_01J8", "para tudo e olha isso", PromptMode.de(true))
        val frame = PhCodec.json.decodeFromString(Frame.serializer(), raw)
        val payload = PhCodec.json.decodeFromJsonElement(PromptSendPayload.serializer(), frame.payload)

        assertEquals(FrameType.PromptSend, frame.type)
        assertEquals("para tudo e olha isso", payload.text)
        assertEquals("quem fura fila tem de chegar como steer", PromptMode.Steer, payload.mode)
    }

    @Test
    fun oVigiaSabeQueAMensagemFurouFila() {
        val vigia = PromptWatchdog()

        val estado = vigia.sent("s1", "olha isso agora", queued = false, now = 1_000L, furarFila = true)

        assertTrue("a tela precisa saber para nao dizer 'entrou na fila'", estado.furarFila)
        assertFalse("furar fila nao e fila esperando a vez", estado.queued)
    }

    @Test
    fun quemFurouFilaNaoViraAlarmeCedoDemais() {
        // A resposta comeca no proximo passo, e um passo com ferramenta demorada
        // leva minutos: com o prazo curto, a tela acusaria sessao travada no meio
        // de um passo normal.
        val vigia = PromptWatchdog(answerWindowMs = 1_000, queueWindowMs = 5_000)
        vigia.sent("s1", "olha isso agora", queued = false, now = 1_000L, furarFila = true)

        assertEquals(PromptAck.Waiting, vigia.tick(4_000L).ack)
    }

    @Test
    fun depoisDeLimparAMarcaSome() {
        val vigia = PromptWatchdog()
        vigia.sent("s1", "olha isso agora", queued = false, now = 1_000L, furarFila = true)

        val estado = vigia.clear()

        assertEquals(PromptAck.None, estado.ack)
        assertFalse(estado.furarFila)
    }
}
