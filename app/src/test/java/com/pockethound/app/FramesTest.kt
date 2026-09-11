package com.pockethound.app

import com.pockethound.app.core.model.ApprovalOutcome
import com.pockethound.app.core.model.Frame
import com.pockethound.app.core.model.FrameType
import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.PhCodec
import com.pockethound.app.core.model.PromptMode
import com.pockethound.app.core.model.PromptSendPayload
import com.pockethound.app.core.model.TurnEventPayload
import com.pockethound.app.core.model.TurnKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {

    @Test
    fun decodificaTurnEventDeTextoDigitando() {
        val raw = """
            {"v":1,"seq":1042,"ts":1757617324113,"type":"turn.event","session":"sess_01J8",
             "payload":{"kind":"text.delta","turn":1,"step":1,"text":"Bom dia","index":0}}
        """.trimIndent()

        val frame = PhCodec.decode(raw)
        assertTrue(frame is IncomingFrame.TurnEvent)
        val event = frame as IncomingFrame.TurnEvent
        assertEquals(1042L, event.seq)
        assertEquals(1757617324113L, event.ts)
        assertEquals("sess_01J8", event.session)
        assertEquals(TurnKind.TextDelta, event.payload.kind)
        assertEquals("Bom dia", event.payload.text)
        assertEquals(1, event.payload.turn)
        assertEquals(1, event.payload.step)
    }

    @Test
    fun decodificaTurnEventDeFerramentaComArgsAninhados() {
        val raw = """
            {"v":1,"seq":1043,"ts":1757617325000,"type":"turn.event","session":"sess_01J8",
             "payload":{"kind":"tool.call","turn":1,"step":2,"name":"bash","callId":"call_9",
                        "args":{"command":"./gradlew :app:assembleDebug"}}}
        """.trimIndent()

        val event = PhCodec.decode(raw) as IncomingFrame.TurnEvent
        assertEquals(TurnKind.ToolCall, event.payload.kind)
        assertEquals("bash", event.payload.name)
        assertEquals("call_9", event.payload.callId)
        assertEquals(2, event.payload.step)
        val args = event.payload.args as JsonObject
        assertEquals("./gradlew :app:assembleDebug", (args["command"] as JsonPrimitive).content)
    }

    @Test
    fun decodificaApprovalRequest() {
        val raw = """
            {"v":1,"seq":1044,"ts":1757617330000,"type":"approval.request","session":"sess_01J8",
             "payload":{"requestId":"apr_1","toolName":"write","callId":"call_10",
                        "reason":"escrita fora do workspace",
                        "args":{"path":"/tmp/x.txt"},"expiresAt":1757617420000}}
        """.trimIndent()

        val frame = PhCodec.decode(raw)
        assertTrue(frame is IncomingFrame.ApprovalRequest)
        val approval = frame as IncomingFrame.ApprovalRequest
        assertEquals("apr_1", approval.payload.requestId)
        assertEquals("write", approval.payload.toolName)
        assertEquals("escrita fora do workspace", approval.payload.reason)
        assertEquals(1757617420000L, approval.payload.expiresAt)
        assertEquals("sess_01J8", approval.session)
    }

    @Test
    fun decodificaApprovalResolvedENotice() {
        val resolved = PhCodec.decode(
            """{"v":1,"seq":9,"ts":1,"type":"approval.resolved","payload":{"requestId":"apr_1","outcome":"allowed-once"}}""",
        ) as IncomingFrame.ApprovalResolved
        assertEquals(ApprovalOutcome.AllowedOnce, resolved.payload.outcome)

        val notice = PhCodec.decode(
            """{"v":1,"seq":10,"ts":2,"type":"notice","payload":{"level":"warn","title":"tarde","body":"build quebrou"}}""",
        ) as IncomingFrame.Notice
        assertEquals("build quebrou", notice.payload.body)
    }

    @Test
    fun tipoDesconhecidoNaoQuebraEhGuardado() {
        val frame = PhCodec.decode(
            """{"v":1,"seq":11,"ts":3,"type":"desk.telemetry","payload":{"algo":1}}""",
        )
        assertTrue(frame is IncomingFrame.Unknown)
        assertEquals("desk.telemetry", (frame as IncomingFrame.Unknown).type)
    }

    @Test
    fun envelopeInvalidoDevolveNulo() {
        assertNull(PhCodec.decode(""))
        assertNull(PhCodec.decode("{"))
        assertNull(PhCodec.decode("[1,2,3]"))
    }

    @Test
    fun campoNovoNoPayloadEhIgnorado() {
        val frame = PhCodec.decode(
            """{"v":1,"seq":12,"ts":4,"type":"turn.event","payload":{"kind":"text.done","text":"ok","campoNovo":42}}""",
        ) as IncomingFrame.TurnEvent
        assertEquals(TurnKind.TextDone, frame.payload.kind)
        assertEquals("ok", frame.payload.text)
    }

    @Test
    fun quadroDeSaidaTemEnvelopeCompleto() {
        val raw = PhCodec.promptSend("sess_01J8", "roda o build", PromptMode.Steer)
        val frame = PhCodec.json.decodeFromString(Frame.serializer(), raw)
        assertEquals(FrameType.PromptSend, frame.type)
        assertEquals("sess_01J8", frame.session)
        assertEquals(1, frame.v)
        val payload = PhCodec.json.decodeFromJsonElement(
            PromptSendPayload.serializer(),
            frame.payload,
        )
        assertEquals("roda o build", payload.text)
        assertEquals(PromptMode.Steer, payload.mode)
    }

    @Test
    fun decideAprovacaoSerializaOutcomeERemember() {
        val raw = PhCodec.approvalDecide("apr_1", ApprovalOutcome.Rejected, remember = true)
        val frame = PhCodec.json.decodeFromString(Frame.serializer(), raw)
        assertEquals(FrameType.ApprovalDecide, frame.type)
        assertEquals("apr_1", (frame.payload["requestId"] as JsonPrimitive).content)
        assertEquals("rejected", (frame.payload["outcome"] as JsonPrimitive).content)
        assertEquals("true", (frame.payload["remember"] as JsonPrimitive).content)
    }

    @Test
    fun turnEventFazRoundTripPeloEnvelope() {
        val payload = TurnEventPayload(
            kind = TurnKind.ToolResult,
            turn = 1,
            step = 2,
            callId = "call_11",
            isError = false,
            text = "BUILD SUCCESSFUL",
        )
        val raw = PhCodec.outbound(FrameType.TurnEvent, PhCodec.payloadOf(payload), session = "sess_01J8")
        val frame = PhCodec.decode(raw) as IncomingFrame.TurnEvent
        assertEquals(TurnKind.ToolResult, frame.payload.kind)
        assertEquals("call_11", frame.payload.callId)
        assertEquals(false, frame.payload.isError)
        assertEquals("BUILD SUCCESSFUL", frame.payload.text)
        assertEquals(1, frame.payload.turn)
        assertEquals("sess_01J8", frame.session)
    }
}
