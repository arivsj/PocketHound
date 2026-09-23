package com.pockethound.app

import com.pockethound.app.core.model.ApprovalRequest
import com.pockethound.app.core.model.QuestionItem
import com.pockethound.app.core.model.QuestionRequestPayload
import com.pockethound.app.notificacao.Aviso
import com.pockethound.app.notificacao.CanalNotificacao
import com.pockethound.app.notificacao.NotificacaoTexto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As regras puras da bandeja: o que a notificação DIZ e qual identidade ela tem.
 *
 * Por que testar isto: o corpo de uma aprovação é a única coisa que convence o
 * humano a liberar o comando de relance — e o id é o que decide se a bandeja
 * recebe uma notificação atualizada ou dez duplicadas.
 */
class NotificacaoTest {

    /* ------------------------------------------------------------ conteúdo */

    @Test
    fun aprovacaoComMotivoNomeiaFerramentaEMotivo() {
        val corpo = NotificacaoTexto.aprovacao("bash", "escreve fora do workspace", "")
        assertEquals("bash: escreve fora do workspace", corpo)
    }

    @Test
    fun aprovacaoSemMotivoCaiParaOsArgumentos() {
        val corpo = NotificacaoTexto.aprovacao("write", null, "{\"path\":\"/etc/x\"}")
        assertEquals("write pede: {\"path\":\"/etc/x\"}", corpo)
    }

    @Test
    fun quebrasERecuosDoArgumentoViramUmaLinhaSo() {
        val corpo = NotificacaoTexto.aprovacao("bash", null, "ls\n    -la")
        assertEquals("bash pede: ls -la", corpo)
    }

    @Test
    fun ferramentaSemNomeNaoDeixaANascerNoVazio() {
        val corpo = NotificacaoTexto.aprovacao("   ", null, "")
        assertEquals("O Harness quer executar Uma ferramenta", corpo)
    }

    @Test
    fun corpoLongoParaExatamenteNoLimiteComReticencias() {
        val corpo = NotificacaoTexto.aprovacao("bash", "x".repeat(500), "")
        assertEquals(NotificacaoTexto.LIMITE_CORPO, corpo.length)
        assertTrue(corpo.endsWith("…"))
    }

    @Test
    fun corteNuncaDeixaMeioEmojiParaTras() {
        val corpo = NotificacaoTexto.truncar("👍".repeat(100), 20)
        assertTrue("passou do limite: " + corpo.length, corpo.length <= 20)
        assertTrue(corpo.endsWith("…"))
        assertTrue("sobrou surrogate solto", temSurrogateSolto(corpo).not())
    }

    /**
     * Surrogate solto = metade de um emoji. Vale olhar OS DOIS lados: um par
     * VÁLIDO tambem e' feito de chars surrogate — e foi exatamente esse detalhe
     * que fez a primeira versao deste teste acusar o codigo certo.
     */
    private fun temSurrogateSolto(texto: String): Boolean {
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= texto.length || !texto[i + 1].isLowSurrogate()) return true
                i += 2
            } else {
                if (c.isLowSurrogate()) return true
                i += 1
            }
        }
        return false
    }

    @Test
    fun textoCurtoNaoGanhaReticencias() {
        assertEquals("curto", NotificacaoTexto.truncar("curto"))
    }

    @Test
    fun perguntaMostraPrimeiraEContaAsDemais() {
        val corpo = NotificacaoTexto.pergunta(
            listOf(perguntaDaFerramenta("Qual caminho?"), perguntaDaFerramenta("E o prazo?")),
        )
        assertEquals("Qual caminho? (+1)", corpo)
    }

    @Test
    fun perguntaUnicaVemSemContador() {
        assertEquals("Seguimos?", NotificacaoTexto.pergunta(listOf(perguntaDaFerramenta("Seguimos?"))))
    }

    @Test
    fun pedidoSemPerguntasNaoFicaVazioNaBandeja() {
        assertEquals("Uma pergunta espera sua resposta.", NotificacaoTexto.pergunta(emptyList()))
    }

    @Test
    fun fimDeTurnoNomeiaASessao() {
        assertEquals("“repo novo” terminou o turno.", NotificacaoTexto.turno("repo novo"))
    }

    @Test
    fun fimDeTurnoSemTituloCaiNoTextoGenerico() {
        val esperado = "O turno terminou — toque para ver o resultado."
        assertEquals(esperado, NotificacaoTexto.turno(null))
        assertEquals(esperado, NotificacaoTexto.turno("   "))
    }

    /* ------------------------------------------------------------ identidade */

    @Test
    fun mesmoPedidoViraSempreOMesmoId() {
        val aviso = Aviso.deAprovacao(ApprovalRequest(requestId = "apr_1", toolName = "bash"))
        assertEquals("aprovacao:apr_1", aviso.id)
        assertEquals(CanalNotificacao.PEDIDOS, aviso.canal)
        assertEquals(NotificacaoTexto.TITULO_APROVACAO, aviso.titulo)
        assertEquals(Aviso.Cancelar(Aviso.idAprovacao("apr_1")), Aviso.Cancelar(aviso.id))
    }

    @Test
    fun fimDeTurnoTemIdPorSessaoECanalProprio() {
        val aviso = Aviso.deTurno("session-1", "qualquer")
        assertEquals("turno:session-1", aviso.id)
        assertEquals(CanalNotificacao.TRABALHO, aviso.canal)
        assertEquals(NotificacaoTexto.TITULO_TURNO, aviso.titulo)
    }

    @Test
    fun perguntaDoAskEPedidoComoAAprovacao() {
        val aviso = Aviso.dePergunta(
            QuestionRequestPayload(
                requestId = "ask_1",
                questions = listOf(perguntaDaFerramenta("Segue?")),
            ),
        )
        assertEquals("pergunta:ask_1", aviso.id)
        assertEquals(CanalNotificacao.PEDIDOS, aviso.canal)
        assertEquals("Segue?", aviso.corpo)
    }

    private fun perguntaDaFerramenta(texto: String) = QuestionItem(id = "q1", question = texto)
}