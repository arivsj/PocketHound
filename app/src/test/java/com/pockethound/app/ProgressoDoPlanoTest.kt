package com.pockethound.app

import com.pockethound.app.core.model.TodoItem
import com.pockethound.app.core.session.ProgressoDoPlano
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O botao fechado do painel de tarefas — o que ele conta sobre o plano.
 *
 * O plano vem do Harness (projecao `todos`, zerada a cada inicio de turno) e o
 * painel do celular mostra a mesma lista do navegador. Fechado, ele so cabe uma
 * linha: quantas ja foram e o que esta acontecendo agora.
 */
class ProgressoDoPlanoTest {

    private fun tarefa(texto: String, status: String) = TodoItem(content = texto, status = status)

    @Test
    fun planoVazioNaoTemOQueContar() {
        val progresso = ProgressoDoPlano.de(emptyList())

        assertEquals(0, progresso.total)
        assertEquals("0/0", progresso.contagem)
        assertNull(progresso.atual)
        assertFalse("nada a fazer nao e' tudo feito'", progresso.concluido)
    }

    @Test
    fun contaAsConcluidasEAnotaAQueEstaAndando() {
        val progresso = ProgressoDoPlano.de(
            listOf(
                tarefa("ler o contrato", "completed"),
                tarefa("escrever o plugin", "in_progress"),
                tarefa("instalar", "pending"),
            ),
        )

        assertEquals(3, progresso.total)
        assertEquals(1, progresso.feitas)
        assertEquals("1/3", progresso.contagem)
        assertEquals("a em andamento manda no rotulo", "escrever o plugin", progresso.atual)
    }

    @Test
    fun semNadaAndandoMostraAProximaPendente() {
        val progresso = ProgressoDoPlano.de(
            listOf(
                tarefa("primeira", "completed"),
                tarefa("segunda", "pending"),
                tarefa("terceira", "pending"),
            ),
        )

        assertEquals("segunda", progresso.atual)
    }

    @Test
    fun tudoConcluidoFechaOPlano() {
        val progresso = ProgressoDoPlano.de(
            listOf(tarefa("a", "completed"), tarefa("b", "completed")),
        )

        assertTrue(progresso.concluido)
        assertEquals("2/2", progresso.contagem)
        // Nada esta andando e nada falta: o botao diz o placar, nao uma tarefa.
        assertNull(progresso.atual)
    }

    @Test
    fun statusDesconhecidoContaComoPendente() {
        // Um Harness mais novo pode inventar um estado. Contar como feito seria
        // mentir sobre o progresso; contar como pendente nao promete nada.
        val progresso = ProgressoDoPlano.de(
            listOf(tarefa("a", "completed"), tarefa("b", "cancelada")),
        )

        assertEquals(1, progresso.feitas)
        assertEquals("1/2", progresso.contagem)
        assertEquals("b", progresso.atual)
    }
}
