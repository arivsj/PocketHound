package com.pockethound.app

import com.pockethound.app.core.model.Session
import com.pockethound.app.core.model.SessionStatus
import com.pockethound.app.core.session.SessionOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A ordem da lista e a escolha da sessao padrao.
 *
 * Isto nasceu de um prompt enviado pelo celular que foi parar numa sessao que
 * ninguem tinha escolhido — a primeira da lista, escolhida pela ordem de chegada
 * dos quadros. Ordem de chegada nao quer dizer nada para quem olha a tela.
 */
class SessionOrderTest {

    private fun sessao(
        id: String,
        status: SessionStatus = SessionStatus.Idle,
        lastSeen: Long? = null,
        origin: String? = null,
        title: String = id,
    ) = Session(id = id, title = title, status = status, lastSeen = lastSeen, origin = origin)

    @Test
    fun aQueEstaTrabalhandoVemPrimeiro() {
        val lista = listOf(
            sessao("antiga", status = SessionStatus.Idle, lastSeen = 900),
            sessao("viva", status = SessionStatus.Running, lastSeen = 100),
        )

        assertEquals(listOf("viva", "antiga"), SessionOrder.order(lista).map { it.id })
    }

    @Test
    fun dentroDoMesmoStatusAMaisRecenteVemPrimeiro() {
        val lista = listOf(
            sessao("ontem", lastSeen = 100),
            sessao("agora", lastSeen = 900),
        )

        assertEquals(listOf("agora", "ontem"), SessionOrder.order(lista).map { it.id })
    }

    @Test
    fun semCarimboDeTempoOrdemNaoEmbaralha() {
        // Sessões que existem só no disco não têm lastSeen: a ordem relativa fica.
        val lista = listOf(sessao("a"), sessao("b"), sessao("c"))

        assertEquals(listOf("a", "b", "c"), SessionOrder.order(lista).map { it.id })
    }

    @Test
    fun padraoNuncaESubagente() {
        val lista = listOf(
            sessao("sub", status = SessionStatus.Running, lastSeen = 999, origin = "subagent"),
            sessao("conversa", status = SessionStatus.Idle, lastSeen = 100),
        )

        assertEquals("conversa", SessionOrder.defaultActive(lista)?.id)
    }

    @Test
    fun semConversaOPadraoESubagenteMaisRecente() {
        val lista = listOf(
            sessao("sub-antigo", lastSeen = 100, origin = "subagent"),
            sessao("sub-novo", lastSeen = 900, origin = "subagent"),
        )

        assertEquals("sub-novo", SessionOrder.defaultActive(lista)?.id)
    }

    @Test
    fun listaVaziaNaoTemPadrao() {
        assertEquals(null, SessionOrder.defaultActive(emptyList()))
    }

    @Test
    fun escolhaDoHumanoVence() {
        val lista = listOf(
            sessao("viva", status = SessionStatus.Running, lastSeen = 900),
            sessao("minha", lastSeen = 100),
        )

        assertEquals("minha", SessionOrder.resolve(lista, "minha")?.id)
    }

    @Test
    fun escolhaQueSumiuCaiNoPadrao() {
        val lista = listOf(sessao("viva", status = SessionStatus.Running, lastSeen = 900))

        assertEquals("viva", SessionOrder.resolve(lista, "sumiu")?.id)
    }

    @Test
    fun semEscolhaOPadraoEOmaisRecenteViva() {
        val lista = listOf(
            sessao("primeira-que-chegou", lastSeen = 100),
            sessao("viva", status = SessionStatus.Running, lastSeen = 500),
        )

        assertEquals("viva", SessionOrder.resolve(lista, null)?.id)
    }
}
