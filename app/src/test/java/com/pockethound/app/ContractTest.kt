package com.pockethound.app

import com.pockethound.app.core.model.ApprovalOutcome
import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.PromptMode
import com.pockethound.app.core.model.QuestionAnswerItem
import com.pockethound.app.core.model.TurnKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste de contrato entre os três repositórios.
 *
 * O arquivo `fixtures.json` é gerado pelo **desk**
 * (`.dev/contract-fixtures.mjs`) e consumido pelo **plugin** e por este app. Ele
 * existe por causa de um defeito concreto do projeto anterior: o app chamava
 * rotas que não existiam no PC e havia três portas diferentes em circulação,
 * sem nada que pegasse a divergência antes de ela chegar ao usuário.
 *
 * Aqui o app desserializa **exatamente** os quadros que o PC emite. Se um lado
 * mudar o formato, este teste quebra — e não o usuário.
 */
class ContractTest {

    private val fixtures: Map<String, kotlinx.serialization.json.JsonElement> by lazy {
        val texto = javaClass.getResourceAsStream("/fixtures.json")?.bufferedReader()?.readText()
        assertNotNull("fixtures.json precisa estar em test/resources", texto)
        Json.parseToJsonElement(texto!!).jsonObject
    }

    private fun quadrosDeIda(): List<String> =
        fixtures.getValue("outbound").jsonArray.map { it.toString() }

    private fun quadrosDeVolta(): List<String> =
        fixtures.getValue("inbound").jsonArray.map { it.toString() }

    @Test
    fun `a versao do protocolo das fixtures bate com a do app`() {
        val versao = fixtures.getValue("protocol").jsonPrimitive.content.toInt()
        assertEquals(com.pockethound.app.core.model.PH_PROTOCOL_VERSION, versao)
    }

    @Test
    fun `todo quadro de ida desserializa sem erro`() {
        val quadros = quadrosDeIda()
        assertTrue("as fixtures precisam ter quadros", quadros.isNotEmpty())
        quadros.forEach { bruto ->
            assertNotNull("não desserializou: $bruto", PhCodec.decode(bruto))
        }
    }

    @Test
    fun `nenhum tipo de ida cai em Unknown`() {
        // Se um tipo novo aparecer nas fixtures sem tratamento no app, isto
        // quebra — que é exatamente o aviso que se quer.
        val desconhecidos = quadrosDeIda()
            .mapNotNull { PhCodec.decode(it) }
            .filterIsInstance<IncomingFrame.Unknown>()
            .map { it.type }
        assertEquals("tipos sem tratamento no app", emptyList<String>(), desconhecidos)
    }

    @Test
    fun `delta de texto vira TurnEvent com o texto preservado`() {
        val quadro = quadrosDeIda()
            .mapNotNull { PhCodec.decode(it) }
            .filterIsInstance<IncomingFrame.TurnEvent>()
            .firstOrNull { it.payload.kind == TurnKind.TextDelta }

        assertNotNull("faltou um text.delta nas fixtures", quadro)
        assertEquals("Vou compilar e publicar. ", quadro!!.payload.text)
        // turn e step identificam ONDE o delta pertence. Sem eles o app não
        // consegue agrupar os deltas do mesmo passo — e o protocolo do plugin
        // sempre os mandou; o modelo é que não os tinha.
        assertEquals(1, quadro.payload.turn)
        assertEquals(1, quadro.payload.step)
    }

    @Test
    fun `pedido de aprovacao traz ferramenta, motivo e argumentos`() {
        val quadro = quadrosDeIda()
            .mapNotNull { PhCodec.decode(it) }
            .filterIsInstance<IncomingFrame.ApprovalRequest>()
            .firstOrNull()

        assertNotNull("faltou um approval.request nas fixtures", quadro)
        assertEquals("bash", quadro!!.payload.toolName)
        assertTrue("o motivo precisa chegar ao celular", quadro.payload.reason!!.isNotBlank())
        assertTrue("os argumentos precisam chegar ao celular", quadro.payload.args != null)
        assertTrue("o pedido precisa de prazo", quadro.payload.expiresAt > 0)
    }

    @Test
    fun `quadros efemeros carregam seq zero`() {
        // Contrato que o cliente de sessão depende: `seq == 0` NÃO avança o
        // cursor. Se o PC passar a numerar `desk.state`, o replay do celular
        // pediria um buraco que nunca existiu.
        val efemeros = quadrosDeIda()
            .mapNotNull { PhCodec.decode(it) }
            .filter {
                it is IncomingFrame.DeskState || it is IncomingFrame.Notice ||
                    it is IncomingFrame.Pong
            }
        assertTrue("as fixtures precisam ter quadros efêmeros", efemeros.isNotEmpty())
        efemeros.forEach { assertEquals("${it::class.simpleName} deveria ter seq 0", 0L, it.seq) }
    }

    @Test
    fun `o seq e monotonico entre os quadros de ida`() {
        val numerados = quadrosDeIda().mapNotNull { PhCodec.decode(it) }.filter { it.seq > 0L }
        assertTrue("precisa haver quadros numerados", numerados.size > 1)
        numerados.zipWithNext { anterior, seguinte ->
            assertTrue(
                "seq fora de ordem: ${anterior.seq} -> ${seguinte.seq}",
                seguinte.seq >= anterior.seq,
            )
        }
    }

    @Test
    fun `todo quadro de volta vira um comando que o codec sabe montar`() {
        // O outro sentido do contrato: o app precisa conseguir PRODUZIR estes
        // quadros, não só consumir.
        quadrosDeVolta().forEach { bruto ->
            val obj = Json.parseToJsonElement(bruto).jsonObject
            val tipo = obj.getValue("type").jsonPrimitive.content
            val montado = when (tipo) {
                FrameType.HelloAck -> PhCodec.helloAck("aparelho", "id", 42L)
                FrameType.Subscribe -> PhCodec.subscribe(42L)
                FrameType.PromptSend -> PhCodec.promptSend("s1", "texto", PromptMode.Followup)
                FrameType.ApprovalDecide -> PhCodec.approvalDecide("r1", ApprovalOutcome.AllowedOnce, true)
                FrameType.QuestionAnswer -> PhCodec.questionAnswer(
                    "r2",
                    listOf(QuestionAnswerItem("q1", listOf("Sim"))),
                )
                FrameType.SessionCancel -> PhCodec.sessionCancel("s1")
                FrameType.SessionSelect -> PhCodec.sessionSelect("s1")
                FrameType.Ping -> PhCodec.ping("eco")
                else -> null
            }
            assertNotNull("o app não sabe montar o quadro '$tipo'", montado)
            // E o que ele monta precisa ser legível de volta pelo próprio codec.
            assertNotNull(PhCodec.decode(montado!!))
        }
    }

    @Test
    fun `a decisao de aprovacao usa o vocabulario fechado do DSH`() {
        val decisoes = quadrosDeVolta()
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == FrameType.ApprovalDecide }
            .map { it.getValue("payload").jsonObject.getValue("outcome").jsonPrimitive.content }

        assertTrue("as fixtures precisam ter uma decisão", decisoes.isNotEmpty())
        decisoes.forEach {
            assertTrue("'$it' não pertence ao vocabulário do DSH", it in ApprovalOutcome.all)
        }
    }
}
