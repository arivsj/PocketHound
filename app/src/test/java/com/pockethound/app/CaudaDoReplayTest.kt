package com.pockethound.app

import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.session.CaudaDoReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regra do "se não viu, já foi".
 *
 * Depois de um tempo fora, o PC reenvia o buffer dele: horas de conversa. A tela
 * do celular mostra o presente — as últimas mensagens —, e o resto do replay é
 * descartado. É regra de produto, então é função pura e testada.
 */
class CaudaDoReplayTest {

    private var contador = 0

    private fun conversa(texto: String, tipo: TurnItemKind = TurnItemKind.AssistantMessage) =
        TurnItem(id = "c${contador++}", kind = tipo, text = texto)

    private fun passo() = TurnItem(id = "p${contador++}", kind = TurnItemKind.ToolCall, text = "bash")

    @Test
    fun `conversa curta passa inteira`() {
        val itens = listOf(conversa("oi", TurnItemKind.UserMessage), passo(), conversa("olá"))
        assertSame("nada a cortar: a mesma lista", itens, CaudaDoReplay.cortar(itens))
    }

    @Test
    fun `lista vazia continua vazia`() {
        assertTrue(CaudaDoReplay.cortar(emptyList()).isEmpty())
    }

    @Test
    fun `guarda as ultimas dez mensagens e o que veio depois delas`() {
        // Vinte mensagens, cada uma seguida de dois passos: é o formato de uma
        // conversa de verdade (o modelo fala, usa ferramenta, fala de novo).
        val itens = buildList {
            repeat(20) { indice ->
                add(conversa("m" + (indice + 1)))
                add(passo())
                add(passo())
            }
        }

        val cauda = CaudaDoReplay.cortar(itens)

        assertEquals("dez mensagens", 10, cauda.count { it.kind == TurnItemKind.AssistantMessage })
        assertEquals("a mais antiga delas é a décima primeira", "m11", cauda.first().text)
        assertEquals("a última é a última", "m20", cauda.last { it.kind == TurnItemKind.AssistantMessage }.text)
        assertEquals("e os passos que vieram depois dela ficam", TurnItemKind.ToolCall, cauda.last().kind)
    }

    @Test
    fun `sem mensagem nenhuma o teto de linhas manda`() {
        // Um turno gigante de ferramenta não pode virar o despejo que o corte
        // existe para evitar.
        val itens = buildList { repeat(300) { add(passo()) } }
        val cauda = CaudaDoReplay.cortar(itens)

        assertEquals(CaudaDoReplay.MAX_ITENS, cauda.size)
        assertEquals("as ÚLTIMAS linhas, não as primeiras", itens.last().id, cauda.last().id)
    }

    @Test
    fun `uma mensagem sozinha no fim vale mais que os passos antes dela`() {
        // O modelo trabalhou 200 passos e disse uma frase. A frase é o presente;
        // os passos que vieram antes dela são o trabalho que já passou.
        val itens = buildList {
            repeat(200) { add(passo()) }
            add(conversa("resposta", TurnItemKind.AssistantMessage))
        }
        val cauda = CaudaDoReplay.cortar(itens)

        assertEquals(1, cauda.size)
        assertEquals("resposta", cauda.last().text)
    }

    @Test
    fun `conversa curta com muitos passos vale o teto de linhas`() {
        val itens = buildList {
            repeat(300) { add(passo()) }
            add(conversa("m1", TurnItemKind.UserMessage))
            repeat(300) { add(passo()) }
            add(conversa("m2", TurnItemKind.AssistantMessage))
        }
        val cauda = CaudaDoReplay.cortar(itens)

        assertEquals("o teto de linhas entra aqui", CaudaDoReplay.MAX_ITENS, cauda.size)
        assertEquals("m2", cauda.last().text)
    }

    @Test
    fun `mensagem ainda sendo escrita conta como conversa`() {
        // O balão em streaming é a última coisa que o usuário viu; cortá-lo fora
        // deixaria a tela terminando num passo de ferramenta solto.
        val itens = buildList {
            repeat(12) { add(conversa("m" + it)) }
            add(TurnItem(id = "vivo", kind = TurnItemKind.AssistantMessage, text = "escrevendo", streaming = true))
        }
        val cauda = CaudaDoReplay.cortar(itens)

        assertTrue("o balão aberto fica", cauda.any { it.id == "vivo" })
        assertEquals(10, cauda.count { it.kind == TurnItemKind.AssistantMessage })
    }

    @Test
    fun `a cauda nunca cresce`() {
        val itens = buildList {
            repeat(50) { add(conversa("m" + it, TurnItemKind.UserMessage)) }
        }
        assertTrue(CaudaDoReplay.cortar(itens).size <= CaudaDoReplay.MAX_ITENS)
    }
}
