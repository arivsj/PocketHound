package com.pockethound.app

import com.pockethound.app.core.model.PerguntaNaTela
import com.pockethound.app.core.model.QuestionItem
import com.pockethound.app.core.model.QuestionRequestPayload
import com.pockethound.app.core.model.RespostaDaPergunta
import com.pockethound.app.core.session.FilaDePerguntas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fila de perguntas: resolvida sai, resolvida não ressuscita no replay, e a
 * trava do celular não se apaga com o reenvio do pedido.
 */
class FilaDePerguntasTest {

    @Test
    fun pedidoNovoEntraNoFim() {
        val fila = FilaDePerguntas.receber(
            listOf(PerguntaNaTela(pedido = pergunta("a"))),
            pergunta("b"),
            emptyList(),
        )
        assertEquals(listOf("a", "b"), fila.map { it.pedido.requestId })
    }

    @Test
    fun reenvioDePendenteNaoDuplicaNemPerdeALugar() {
        val inicial = listOf(
            PerguntaNaTela(pedido = pergunta("a")),
            PerguntaNaTela(pedido = pergunta("b")),
        )
        val fila = FilaDePerguntas.receber(inicial, pergunta("a"), emptyList())
        assertEquals(listOf("b", "a"), fila.map { it.pedido.requestId })
        assertEquals(2, fila.size)
    }

    @Test
    fun resolvidaNaoRessuscitaNoReplay() {
        val fila = FilaDePerguntas.receber(emptyList(), pergunta("a"), listOf("a"))
        assertTrue("ressuscitou", fila.isEmpty())
    }

    @Test
    fun resolvedTiraDaFilaMesmoSemItem() {
        val fila = FilaDePerguntas.resolver(
            listOf(PerguntaNaTela(pedido = pergunta("b"))),
            "a",
        )
        assertEquals(listOf("b"), fila.map { it.pedido.requestId })
    }

    @Test
    fun travaLocalDoCelularNaoSeApagaNoReenvio() {
        val respondida = PerguntaNaTela(
            pedido = pergunta("a"),
            resposta = RespostaDaPergunta(selecionadas = listOf("Sim"), por = "celular"),
        )
        val fila = FilaDePerguntas.receber(listOf(respondida), pergunta("a"), emptyList())
        assertSame("destravou a trava local", respondida, fila.single())
        assertEquals("Sim", fila.single().resposta?.selecionadas?.single())
    }

    @Test
    fun mesmaInstanciaQuandoNadaMuda() {
        val atual = listOf(PerguntaNaTela(pedido = pergunta("a")))
        assertSame(atual, FilaDePerguntas.receber(atual, pergunta("a"), listOf("a")))
    }

    @Test
    fun memoriaDeResolvidasFicaMaisRecenteNaFrenteEComTeto() {
        var resolvidas: List<String> = emptyList()
        repeat(FilaDePerguntas.LIMITE_RESOLVIDAS + 20) { i ->
            resolvidas = FilaDePerguntas.marcarResolvida(resolvidas, "id-" + i)
        }
        assertEquals(FilaDePerguntas.LIMITE_RESOLVIDAS, resolvidas.size)
        assertEquals("id-" + (FilaDePerguntas.LIMITE_RESOLVIDAS + 19), resolvidas.first())
        assertTrue("id-0 foi pro teto", "id-0" !in resolvidas)
    }

    @Test
    fun repetidaNaoDobraANemEmpurraDeLugar() {
        val resolvidas = FilaDePerguntas.marcarResolvida(listOf("a", "b"), "b")
        assertEquals(listOf("b", "a"), resolvidas)
    }

    @Test
    fun idEmBrancoNaoEntraNaMemoria() {
        assertEquals(listOf("a"), FilaDePerguntas.marcarResolvida(listOf("a"), ""))
    }

    private fun pergunta(id: String) = QuestionRequestPayload(
        requestId = id,
        questions = listOf(QuestionItem(id = "q1", question = "Segue?")),
    )
}