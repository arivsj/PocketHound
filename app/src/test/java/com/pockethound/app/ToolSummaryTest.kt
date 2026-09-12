package com.pockethound.app

import com.pockethound.app.core.session.ToolSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotulo e assunto de uma chamada de ferramenta.
 *
 * O Harness no navegador escreve cada passo como uma LINHA ("Comando · Check node
 * and ignore rules"), e o campo "description" existe justamente para ser lido por
 * um humano. Mostrar o JSON cru esconde a unica parte escrita para voce.
 */
class ToolSummaryTest {

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun rotuloTraduzAsFerramentasConhecidas() {
        assertEquals("Comando", ToolSummary.label("bash"))
        assertEquals("Codigo", ToolSummary.label("run_code"))
        assertEquals("Leitura", ToolSummary.label("read"))
        assertEquals("Escrita", ToolSummary.label("write"))
        assertEquals("Edicao", ToolSummary.label("edit"))
        assertEquals("Busca", ToolSummary.label("grep"))
        // Ferramenta que o app ainda nao conhece nao pode virar linha vazia.
        assertEquals("Ferramenta", ToolSummary.label("ferramenta_do_futuro"))
    }

    @Test
    fun assuntoPrefereODescriptionEscritoParaOHumano() {
        val linha = ToolSummary.of(
            "bash",
            args("""{"command":"ls -la","description":"Check node and ignore rules"}"""),
        )

        assertEquals("Comando", linha.label)
        assertEquals("Check node and ignore rules", linha.subject)
    }

    @Test
    fun semDescriptionOAssuntoEAlvoDaAcao() {
        assertEquals(
            "src/Main.kt",
            ToolSummary.subject("read", args("""{"file_path":"src/Main.kt"}""")),
        )
        assertEquals(
            "**/*.kt",
            ToolSummary.subject("glob", args("""{"pattern":"**/*.kt"}""")),
        )
    }

    @Test
    fun caminhoLongoEncurtaPelaEsquerda() {
        val longo = "app/src/main/java/com/pockethound/app/ui/chat/ChatScreen.kt"

        val assunto = ToolSummary.subject("edit", args("""{"file_path":"$longo"}"""))

        assertEquals(".../chat/ChatScreen.kt", assunto)
        assertTrue("o nome do arquivo e o que importa", assunto.endsWith("ChatScreen.kt"))
    }

    @Test
    fun linhaDoComandoCaiParaAPrimeiraLinha() {
        val linha = ToolSummary.of(
            "bash",
            args("""{"command":"cd /tmp\nls -la\nrm -rf nada"}"""),
        )

        assertEquals("cd /tmp", linha.subject)
    }

    @Test
    fun assuntoNuncaEhVazio() {
        assertEquals("ferramenta_x", ToolSummary.subject("ferramenta_x", null))
    }

    @Test
    fun resultadoFechadoMostraAPrimeiraLinhaComConteudo() {
        val texto = "\n\n   \nBUILD SUCCESSFUL in 34s\n2 actionable tasks"

        assertEquals("BUILD SUCCESSFUL in 34s", ToolSummary.resultSubject(texto))
    }

    @Test
    fun resultadoVazioNaoViraLinhaEmBranco() {
        assertEquals("(sem saida)", ToolSummary.resultSubject("   \n  "))
    }

    @Test
    fun detalheVemIdentadoParaLeitura() {
        val detalhe = ToolSummary.pretty(args("""{"file_path":"a.kt","limit":10}"""))

        assertTrue("quebra de linha no detalhe", detalhe.contains("\n"))
        assertTrue(detalhe.contains("file_path"))
    }
}
