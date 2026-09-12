package com.pockethound.app

import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.TurnEventPayload
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.model.TurnKind
import com.pockethound.app.core.session.TranscriptReducer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Como uma chamada de ferramenta vira linha na transcricao.
 *
 * Duas coisas quebraram em campo e estao presas aqui: o JSON cru aparecendo no
 * lugar do texto escrito para o humano, e o resultado sem nome ("resultado · "),
 * porque o nome vem da CHAMADA e o PC so manda o callId de volta.
 */
class TranscriptRowsTest {

    private fun quadro(payload: TurnEventPayload, seq: Long): IncomingFrame =
        PhCodec.decode(PhCodec.outbound(FrameType.TurnEvent, PhCodec.payloadOf(payload), session = "s1", seq = seq))
            ?: error("nao desserializou")

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun chamada(callId: String, name: String, json: String, seq: Long): IncomingFrame =
        quadro(
            TurnEventPayload(
                kind = TurnKind.ToolCall,
                turn = 1,
                step = 1,
                callId = callId,
                name = name,
                args = args(json),
            ),
            seq,
        )

    private fun resultado(callId: String, texto: String, erro: Boolean, seq: Long): IncomingFrame =
        quadro(
            TurnEventPayload(
                kind = TurnKind.ToolResult,
                turn = 1,
                step = 1,
                callId = callId,
                text = texto,
                isError = erro,
            ),
            seq,
        )

    @Test
    fun chamadaViraLinhaComRotuloEAssunto() {
        val t = TranscriptReducer.reduce(
            emptyList(),
            chamada("c1", "bash", """{"command":"ls","description":"Listar arquivos"}""", 1),
        )

        val item = t.single()
        assertEquals(TurnItemKind.ToolCall, item.kind)
        assertEquals("Comando", item.label)
        assertEquals("Listar arquivos", item.subject)
        assertEquals("c1", item.callId)
        // O JSON nao desaparece: ele vira o detalhe, que so abre no toque.
        assertTrue("argumentos no detalhe", item.detail.orEmpty().contains("command"))
    }

    @Test
    fun resultadoCasaComAChamadaPeloCallId() {
        var t: List<TurnItem> = emptyList()
        t = TranscriptReducer.reduce(t, chamada("c9", "read", """{"file_path":"src/Main.kt"}""", 1))
        t = TranscriptReducer.reduce(t, resultado("c9", "package com.x\n\nclass Main", erro = false, seq = 2))

        val item = t.last()
        assertEquals(TurnItemKind.ToolResult, item.kind)
        assertEquals("Leitura", item.label)
        assertEquals("src/Main.kt", item.subject)
        assertEquals("read", item.toolName)
        assertEquals("package com.x", item.text)
        assertEquals(true, item.ok)
        assertTrue("corpo inteiro no detalhe", item.detail.orEmpty().contains("class Main"))
    }

    @Test
    fun resultadoComErroViraErroEMantemODesfecho() {
        var t: List<TurnItem> = emptyList()
        t = TranscriptReducer.reduce(t, chamada("c1", "bash", """{"description":"Rodar testes"}""", 1))
        t = TranscriptReducer.reduce(t, resultado("c1", "FAILED: 2 testes", erro = true, seq = 2))

        val item = t.last()
        assertEquals(TurnItemKind.Error, item.kind)
        assertEquals(false, item.ok)
        assertEquals("FAILED: 2 testes", item.text)
    }

    @Test
    fun resultadoSemChamadaConhecidaAindaTemLinha() {
        val t = TranscriptReducer.reduce(emptyList(), resultado("orfao", "saida qualquer", erro = false, seq = 5))

        val item = t.single()
        assertNotNull("linha nao pode ficar sem rotulo", item.label)
        assertEquals("saida qualquer", item.text)
    }

    @Test
    fun raciocinioFicaInteiroNoItemParaATelaRecolher() {
        val t = TranscriptReducer.reduce(
            emptyList(),
            quadro(
                TurnEventPayload(
                    kind = TurnKind.TextDone,
                    turn = 1,
                    step = 1,
                    text = "Resposta",
                    reasoning = "Primeira linha do pensamento\nsegunda linha",
                ),
                seq = 7,
            ),
        )

        assertEquals(2, t.size)
        val raciocinio = t.first { it.kind == TurnItemKind.Reasoning }
        assertTrue(raciocinio.text.startsWith("Primeira linha"))
        assertFalse("quem recolhe e a tela, nao o dado", raciocinio.text.isEmpty())
    }
}
