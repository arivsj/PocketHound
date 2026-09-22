package com.pockethound.app

import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.session.PromptDaResposta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O rotulo "respondendo a" acima da resposta do agente.
 *
 * A ligacao so pode sair da ordem da conversa — e a regra tem de saber calar
 * quando a ordem mente (dois prompts antes de a primeira resposta sair).
 */
class PromptDaRespostaTest {

    private fun eu(texto: String) = TurnItem(id = "u-$texto", kind = TurnItemKind.UserMessage, text = texto)

    private fun agente(id: String, turno: Int, texto: String = "...") =
        TurnItem(id = id, kind = TurnItemKind.AssistantMessage, text = texto, turn = turno)

    private fun passo(id: String, turno: Int) =
        TurnItem(id = id, kind = TurnItemKind.ToolCall, text = "bash", turn = turno)

    @Test
    fun `a resposta ganha o prompt que a pediu`() {
        val rotulos = PromptDaResposta.casar(listOf(eu("faz o build"), agente("a1", 1)))

        assertEquals("faz o build", rotulos["a1"])
    }

    @Test
    fun `um rotulo por turno, so na primeira resposta`() {
        val rotulos = PromptDaResposta.casar(
            listOf(eu("faz o build"), agente("a1", 1), passo("p1", 1), agente("a2", 1)),
        )

        assertEquals("faz o build", rotulos["a1"])
        assertTrue("o segundo passo do mesmo turno nao repete o rotulo", rotulos["a2"] == null)
    }

    @Test
    fun `cada turno com o seu prompt`() {
        val rotulos = PromptDaResposta.casar(
            listOf(
                eu("primeiro"), agente("a1", 1),
                eu("segundo"), agente("a2", 2),
            ),
        )

        assertEquals("primeiro", rotulos["a1"])
        assertEquals("segundo", rotulos["a2"])
    }

    @Test
    fun `com dois prompts na fila a ordem mente e nao ha rotulo`() {
        // O caso que faz a regra calar: o segundo prompt apareceu antes da
        // resposta do primeiro, entao a resposta NAO e dele.
        val rotulos = PromptDaResposta.casar(
            listOf(eu("faz o build"), eu("e roda os testes"), agente("a1", 1)),
        )

        assertTrue("melhor sem rotulo do que com rotulo errado", rotulos.isEmpty())
    }

    @Test
    fun `depois da fila esvaziar, o proximo turno volta a ser rotulado`() {
        val rotulos = PromptDaResposta.casar(
            listOf(
                eu("faz o build"), eu("e roda os testes"), agente("a1", 1),
                agente("a2", 2),
            ),
        )

        assertTrue("o turno ambiguo fica sem rotulo", rotulos["a1"] == null)
        assertEquals("o seguinte e do prompt que sobrou", "e roda os testes", rotulos["a2"])
    }

    @Test
    fun `resposta sem prompt nenhum antes nao ganha rotulo`() {
        assertTrue(PromptDaResposta.casar(listOf(agente("a1", 1))).isEmpty())
    }
}
