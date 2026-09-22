package com.pockethound.app

import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.TurnEventPayload
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.model.TurnKind
import com.pockethound.app.core.session.ContagemDaAtualizacao
import com.pockethound.app.core.session.MarcaDoReplay
import com.pockethound.app.core.session.TranscriptReducer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O botão "atualizar": o que ele pede, o que volta, e por que não duplica.
 *
 * A conversa do celular é montada do fluxo ao vivo, e o fluxo perde pedaços —
 * rede que troca de torre, aplicativo suspenso, delta descartado na
 * contrapressão. O botão pede ao PC o reenvio do buffer dele, e o reenvio traz
 * de volta quase tudo o que já está na tela. Estas três peças são o que faz o
 * reenvio ser seguro: uma marca d'água que diz o que já passou, um contador que
 * diz o que mudou, e um redutor que não aceita o mesmo item duas vezes.
 */
class AtualizacaoTest {

    /* ------------------------------------------------------------ a contagem */

    @Test
    fun aContagemSoVeOCrescimento() {
        val antes = mapOf("s1" to 10, "s2" to 4)
        val depois = mapOf("s1" to 13, "s2" to 4, "s3" to 2)

        assertEquals(5, ContagemDaAtualizacao.novidades(antes, depois))
    }

    @Test
    fun sessaoQueSumiuNaoViraNovidadeNegativa() {
        // `session.gone` no meio da atualização apaga a transcrição inteira. Uma
        // conta que aceitasse negativo diria que a atualização T I R O U mensagens.
        val antes = mapOf("s1" to 10)
        val depois = mapOf("s2" to 3)

        assertEquals(3, ContagemDaAtualizacao.novidades(antes, depois))
    }

    @Test
    fun nadaNovoDaZero() {
        val igual = mapOf("s1" to 7)
        assertEquals(0, ContagemDaAtualizacao.novidades(igual, igual))
    }

    /* --------------------------------------------------------- a marca d'água */

    @Test
    fun aMarcaSoAceitaOQueNaoPassou() {
        val marca = MarcaDoReplay()
        assertTrue("o primeiro quadro é novidade", marca.aceita(5))
        marca.marcou(5)
        assertFalse("o mesmo quadro de novo não é", marca.aceita(5))
        assertFalse("e um mais antigo também não", marca.aceita(4))
        assertTrue("o seguinte é", marca.aceita(6))
    }

    @Test
    fun aMarcaNuncaAndaParaTras() {
        // O reenvio traz quadros antigos em ordem; se eles puxassem a marca para
        // trás, a conversa já vista voltaria a ser "novidade" na próxima volta.
        val marca = MarcaDoReplay()
        marca.marcou(900)
        marca.marcou(12)

        assertEquals(900L, marca.ultimo)
    }

    /* ------------------------------------------------- o redutor é idempotente */

    private fun quadro(payload: TurnEventPayload, seq: Long): IncomingFrame =
        PhCodec.decode(PhCodec.outbound(FrameType.TurnEvent, PhCodec.payloadOf(payload), session = "s1", seq = seq))
            ?: error("não desserializou")

    @Test
    fun aMesmaMensagemNaoEntraDuasVezes() {
        val mensagem = quadro(TurnEventPayload(kind = TurnKind.UserMessage, text = "faz o build"), 7)

        var t = TranscriptReducer.reduce(emptyList(), mensagem)
        t = TranscriptReducer.reduce(t, mensagem)

        assertEquals("o reenvio devolveu o mesmo quadro", 1, t.size)
        assertEquals("faz o build", t[0].text)
    }

    @Test
    fun aMesmaChamadaNaoEntraDuasVezes() {
        val chamada = quadro(
            TurnEventPayload(
                kind = TurnKind.ToolCall,
                turn = 1,
                step = 1,
                callId = "c1",
                name = "bash",
                args = JsonObject(mapOf("command" to JsonPrimitive("ls"))),
            ),
            3,
        )

        var t = TranscriptReducer.reduce(emptyList(), chamada)
        t = TranscriptReducer.reduce(t, chamada)

        assertEquals(1, t.size)
        assertEquals(TurnItemKind.ToolCall, t[0].kind)
    }

    @Test
    fun oMesmoResultadoNaoEntraDuasVezes() {
        val resultado = quadro(
            TurnEventPayload(
                kind = TurnKind.ToolResult,
                turn = 1,
                step = 1,
                callId = "c1",
                text = "ok",
            ),
            4,
        )

        var t = TranscriptReducer.reduce(emptyList(), resultado)
        t = TranscriptReducer.reduce(t, resultado)

        assertEquals(1, t.size)
    }

    @Test
    fun oFechamentoRepetidoNaoDuplicaOBalao() {
        // O caso que mais dói: o balão consolidado voltando no reenvio. Duas
        // linhas com o mesmo id derrubam a lista, porque o id é a chave dela.
        val fechamento = quadro(
            TurnEventPayload(kind = TurnKind.TextDone, turn = 1, step = 1, text = "pronto"),
            9,
        )

        var t = TranscriptReducer.reduce(emptyList(), fechamento)
        t = TranscriptReducer.reduce(t, fechamento)

        assertEquals(1, t.size)
        assertEquals("pronto", t[0].text)
    }

    @Test
    fun oRaciocinioRepetidoNaoDuplica() {
        val fechamento = quadro(
            TurnEventPayload(
                kind = TurnKind.TextDone,
                turn = 1,
                step = 1,
                text = "resposta",
                reasoning = "porque sim",
            ),
            9,
        )

        var t = TranscriptReducer.reduce(emptyList(), fechamento)
        t = TranscriptReducer.reduce(t, fechamento)

        assertEquals(2, t.size)
        assertEquals(TurnItemKind.Reasoning, t[0].kind)
        assertEquals(TurnItemKind.AssistantMessage, t[1].kind)
    }

    @Test
    fun oReenvioNaoMisturaOPassoQueJaEstavaNaTelaComONovo() {
        // O que o reenvio tem de fazer de verdade: juntar o que faltava SEM
        // mexer no que já estava lá.
        val antigo = quadro(TurnEventPayload(kind = TurnKind.UserMessage, text = "primeiro"), 2)
        val novo = quadro(TurnEventPayload(kind = TurnKind.UserMessage, text = "segundo"), 5)

        var t = TranscriptReducer.reduce(emptyList(), antigo)
        t = TranscriptReducer.reduce(t, antigo)
        t = TranscriptReducer.reduce(t, novo)

        assertEquals(2, t.size)
        assertEquals("primeiro", t[0].text)
        assertEquals("segundo", t[1].text)
    }
}
