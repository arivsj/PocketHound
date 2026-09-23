package com.pockethound.app

import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.TodoItem
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
    fun aFilaSeCorrigeMesmoDuranteARecuperacao() {
        // O app ficou preso em "1 na fila" com o PC dizendo zero: o quadro que
        // zerava a fila chegou dentro de uma recuperacao, quando o estado do turno
        // ficava congelado, e foi descartado. A fila e numero ABSOLUTO do PC — nao
        // pode ficar de fora, senao nao existe quadro futuro capaz de corrigi-la.
        assertTrue(TurnStatusReducer.dobraDuranteRecuperacao(TurnKind.Inbox))

        val comFila = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.Inbox, queued = 1), ts = 10)
        val corrigido = passo(comFila, TurnEventPayload(kind = TurnKind.Inbox, queued = 0), ts = 20)

        assertEquals(1, comFila.queued)
        assertEquals("a fila vazia do PC tem de entrar", 0, corrigido.queued)
    }

    @Test
    fun oRestoDoTurnoContinuaCongeladoNaRecuperacao() {
        // O congelamento existe por um motivo: um `turn.start` antigo, dobrado como
        // se fosse agora, deixaria a sessao "trabalhando" para sempre.
        listOf(
            TurnKind.TurnStart,
            TurnKind.StepStart,
            TurnKind.TextDelta,
            TurnKind.ReasoningDelta,
            TurnKind.TurnEnd,
            TurnKind.Stats,
        ).forEach { kind ->
            assertFalse("nao deveria dobrar na recuperacao: " + kind, TurnStatusReducer.dobraDuranteRecuperacao(kind))
        }
    }

    @Test
    fun sessaoQueOPcNaoConsideraVivaNaoFicaTrabalhando() {
        val aberto = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.TurnStart, turn = 1), ts = 0)

        assertFalse(TurnStatusReducer.sessao(aberto, viva = false).running)
        assertTrue(TurnStatusReducer.sessao(aberto, viva = true).running)
    }


    /* ------------------------------------------- o retrato do rodape (stats) */

    @Test
    fun retratoDoRodapeEntraNoEstado() {
        // O PC lê as projeções do Harness (gasto em dólar e ocupação do contexto)
        // e publica este retrato. É o que o celular desenha embaixo do composer.
        val estado = passo(
            TurnStatus(),
            TurnEventPayload(
                kind = TurnKind.Stats,
                usd = 0.0123,
                usdPico = 0.0,
                entrada = 119_000_000L,
                saida = 383_600L,
                cache = 118_000_000L,
                contextoUsado = 120_000L,
                contextoJanela = 1_000_000L,
            ),
            ts = 10,
        )

        assertEquals(0.0123, estado.custoUsd, 0.0000001)
        assertEquals(119_000_000L, estado.entrada)
        // 120 mil de 1 milhão: 12%.
        assertEquals(12.0, estado.contextoPct!!, 0.01)
        assertTrue(estado.temRetrato)
    }

    @Test
    fun retratoIncompletoNaoApagaOQueJaVeio() {
        // Perfil sem o plugin de custo manda só o contexto; perfil sem o medidor
        // manda só o gasto. Um nulo não pode zerar o que já estava na tela.
        val comCusto = passo(TurnStatus(), TurnEventPayload(kind = TurnKind.Stats, usd = 0.5), ts = 10)
        val depois = passo(comCusto, TurnEventPayload(kind = TurnKind.Stats, contextoJanela = 1_000_000L, contextoUsado = 250_000L), ts = 11)

        assertEquals("o gasto ficou", 0.5, depois.custoUsd, 0.0000001)
        assertEquals("e o contexto chegou", 25.0, depois.contextoPct!!, 0.1)
    }

    @Test
    fun planoNovoEntraNoEstado() {
        val comPlano = passo(
            TurnStatus(),
            TurnEventPayload(
                kind = TurnKind.Stats,
                todos = listOf(TodoItem("primeira", "in_progress")),
            ),
            ts = 10,
        )

        assertEquals(1, comPlano.todos.size)
        assertEquals("primeira", comPlano.todos.first().content)
    }

    @Test
    fun retratoSemPlanoApagaOPainel() {
        // O Harness zera a projecao `todos` a cada inicio de turno e manda a lista
        // vazia no retrato seguinte. O painel do celular tem de sumir junto com o
        // do navegador: guardar o plano velho deixava o celular mentindo sobre o
        // que a tela do PC ja' tinha apagado.
        val comPlano = passo(
            TurnStatus(),
            TurnEventPayload(kind = TurnKind.Stats, todos = listOf(TodoItem("a", "pending"))),
            ts = 10,
        )
        val semPlano = passo(comPlano, TurnEventPayload(kind = TurnKind.Stats), ts = 20)

        assertEquals(1, comPlano.todos.size)
        assertTrue("o painel sai da tela", semPlano.todos.isEmpty())
    }

    @Test
    fun sessaoSemRetratoNaoMostraLinha() {
        assertFalse(TurnStatus().temRetrato)
    }
}
