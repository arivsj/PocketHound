package com.pockethound.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Versão do protocolo PocketHound (ARQUITETURA.md §4). */
const val PH_PROTOCOL_VERSION = 1

/**
 * Envelope único de todo quadro do protocolo. O corpo varia por [type], então o
 * [payload] chega como árvore JSON e só depois é convertido no tipo do quadro.
 *
 * [seq] é monotônico por PC (não por sessão) — é a espinha do replay: o celular
 * manda [SubscribePayload.cursor] e o PC reenvia tudo o que veio depois.
 */
@Serializable
data class Frame(
    val v: Int = PH_PROTOCOL_VERSION,
    val seq: Long = 0L,
    val ts: Long = 0L,
    val type: String,
    val session: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
)

/** Nomes de quadro do protocolo. String crua em vez de enum: o PC pode
 *  acrescentar tipos novos e o app precisa ignorá-los sem quebrar. */
object FrameType {
    // PC -> celular
    const val Hello = "hello"
    const val SessionUpsert = "session.upsert"
    const val SessionGone = "session.gone"
    const val TurnEvent = "turn.event"
    const val ApprovalRequest = "approval.request"
    const val ApprovalResolved = "approval.resolved"
    const val QuestionRequest = "question.request"

    /** A pergunta foi respondida em outro lugar (a tela do PC). */
    const val QuestionResolved = "question.resolved"
    const val DeskState = "desk.state"
    const val Notice = "notice"
    const val Pong = "pong"
    const val ReplayDone = "replay.done"
    const val BridgeState = "bridge.state"

    /** Resposta a workspace.list: os workspaces do Harness. */
    const val WorkspaceList = "workspace.list"

    // celular -> PC
    const val HelloAck = "hello.ack"
    const val Subscribe = "subscribe"
    const val PromptSend = "prompt.send"
    const val ApprovalDecide = "approval.decide"
    const val QuestionAnswer = "question.answer"
    const val SessionCancel = "session.cancel"
    const val SessionSelect = "session.select"
    const val Ping = "ping"

    /** Abre sessao nova dentro de um workspace. */
    const val SessionCreate = "session.create"
}

/** Vocabulário fechado de decisão de aprovação do DSH. */
object ApprovalOutcome {
    const val AllowedOnce = "allowed-once"
    const val Rejected = "rejected"
    const val Cancelled = "cancelled"
    const val Unavailable = "unavailable"
    val all = listOf(AllowedOnce, Rejected, Cancelled, Unavailable)
}

/** Modo de injeção do prompt: followup entra na fila, steer interrompe o turno. */
object PromptMode {
    const val Followup = "followup"
    const val Steer = "steer"
}

object NoticeLevel {
    const val Info = "info"
    const val Success = "success"
    const val Warn = "warn"
    const val Error = "error"
}

// ---------------------------------------------------------------------------
// Payloads PC -> celular
// ---------------------------------------------------------------------------

@Serializable
data class SessionInfo(
    val id: String,
    val title: String = "",
    val workspace: String = "",
    val status: String = "idle",
    /** Ultimo sinal de vida visto pelo PC (ms). Nulo no que veio so do disco. */
    val lastSeen: Long? = null,
    /** Quando a sessao nasceu (ms). */
    val createdAt: Long? = null,
)

@Serializable
data class HelloPayload(
    val name: String = "",
    /** Número, não texto: o protocolo manda `"version": 1`. */
    val version: Int = PH_PROTOCOL_VERSION,
    val sessions: List<SessionInfo> = emptyList(),
    val cursorMax: Long = 0L,
)

@Serializable
data class SessionUpsertPayload(
    val id: String,
    val title: String = "",
    val workspace: String = "",
    /** O plugin manda "live" (agente rodando) ou "cold" (existe so no disco). */
    val status: String = "idle",
    /** "subagent" quando a sessao e de um subagente do Harness. */
    val origin: String? = null,
    /** Profundidade na arvore de delegacao. */
    val depth: Int? = null,
    /** Eventos no log; nulo nas sessoes que existem so no disco. */
    val events: Long? = null,
    /**
     * Ultimo sinal de vida visto pelo PC (ms).
     *
     * O plugin carimba a cada evento da sessao, entao este campo e o que ordena a
     * lista do celular por atividade de verdade — e nao pela ordem de chegada dos
     * quadros, que nao quer dizer nada para quem olha a tela.
     */
    val lastSeen: Long? = null,
    /** Quando a sessao nasceu (ms). */
    val createdAt: Long? = null,
)

@Serializable
data class SessionGonePayload(val id: String)

/**
 * Carga de um [FrameType.TurnEvent].
 *
 * O payload real é `{ kind, … }` e cada kind traz campos diferentes. Em vez de uma
 * hierarquia polimórfica (que quebraria a cada campo novo do DSH), os campos
 * conhecidos ficam declarados e os desconhecidos são descartados — o Json do codec
 * usa ignoreUnknownKeys.
 */
@Serializable
data class TurnEventPayload(
    val kind: String,
    val turn: Int? = null,
    val step: Int? = null,
    val index: Int? = null,
    /** Texto do delta, do balão consolidado, do resultado da ferramenta ou do
     *  que você mandou — depende do [kind]. */
    val text: String? = null,
    /** Raciocínio do passo, em `text.done`. */
    val reasoning: String? = null,
    /** Instante do evento no PC (ms). */
    val at: Long? = null,
    /** Por que o turno fechou, em `turn.end`. */
    val reason: String? = null,
    /** Chamadas que o passo pediu, em `text.done`. */
    val calls: List<ToolCallRef> = emptyList(),
    /** Contabilidade de tokens do passo, em `text.done`. */
    val usage: TokenUsage? = null,
    val callId: String? = null,
    /** Nome da ferramenta, em `tool.call` e nas chamadas de `text.done`. */
    val name: String? = null,
    /** Argumentos já analisados da ferramenta. */
    val args: JsonObject? = null,
    val isError: Boolean? = null,
    val errorCode: String? = null,
    /** Quem mandou a mensagem, em `user.message`: user, plugin, etc. */
    val source: String? = null,
    val plugin: String? = null,
    val todos: List<TodoItem> = emptyList(),
    /** Mensagens esperando a vez na fila da sessão, em `inbox`. */
    val queued: Int? = null,
)

/** Uma chamada de ferramenta embutida no fechamento do passo. */
@Serializable
data class ToolCallRef(
    val callId: String = "",
    val name: String = "",
    val args: JsonObject? = null,
)

/** Contabilidade de tokens do passo. */
@Serializable
data class TokenUsage(
    val input: Int? = null,
    val output: Int? = null,
    val total: Int? = null,
)

@Serializable
data class TodoItem(
    val content: String = "",
    val status: String = "pending",
)

/** Kinds de turn.event mapeados em ARQUITETURA.md §5. */
object TurnKind {
    const val TurnStart = "turn.start"
    const val TurnEnd = "turn.end"
    const val StepStart = "step.start"
    const val StepEnd = "step.end"
    const val TextDelta = "text.delta"
    const val ReasoningDelta = "reasoning.delta"
    const val TextDone = "text.done"
    const val ToolCall = "tool.call"
    const val ToolResult = "tool.result"
    const val TodoWrite = "todo.write"
    const val UserMessage = "user.message"

    /** Tamanho da fila da sessão mudou: é o "N na fila" que a tela mostra. */
    const val Inbox = "inbox"
}

@Serializable
data class ApprovalRequestPayload(
    val requestId: String,
    val toolName: String = "",
    val callId: String? = null,
    val reason: String? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val expiresAt: Long = 0L,
)

@Serializable
data class ApprovalResolvedPayload(
    val requestId: String,
    val outcome: String,
)

@Serializable
data class DeskStatePayload(
    val at: Long = 0L,
    val hostname: String = "",
    val platform: String = "",
    val release: String = "",
    val cpuModel: String = "",
    val cores: Int = 0,
    val uptimeSeconds: Long = 0L,
    /** Carga média de 1, 5 e 15 minutos. */
    val loadAvg: List<Double> = emptyList(),
    val memTotalMb: Long = 0L,
    val memUsedMb: Long = 0L,
    /** Só aparece a partir da segunda amostra — a primeira não tem taxa. */
    val cpuPercent: Double? = null,
    /** Só aparece quando o sistema expõe o sensor. Ausente não é zero. */
    val temperatureC: Double? = null,
)

@Serializable
data class NoticePayload(
    val level: String = NoticeLevel.Info,
    val title: String = "",
    val body: String = "",
)

@Serializable
data class PongPayload(val echo: String = "")

@Serializable
data class ReplayDonePayload(val from: Long = 0L, val to: Long = 0L)

/**
 * O agente fez uma pergunta e espera a resposta do celular.
 *
 * É o inverso da aprovação: em vez de pedir permissão, o Harness pede opinião.
 * Vem da ferramenta `pockethound_ask`.
 */
@Serializable
data class QuestionResolvedPayload(
    val requestId: String,
    /** Quem respondeu: desktop (a tela do PC) ou phone (o celular). */
    @SerialName("by") val por: String = "",
    /** A resposta, quando quem respondeu foi o celular. */
    val answers: List<QuestionAnswerItem> = emptyList(),
)

@Serializable
data class QuestionRequestPayload(
    val requestId: String,
    val sessionId: String = "",
    val questions: List<QuestionItem> = emptyList(),
    val expiresAt: Long = 0L,
)

@Serializable
data class QuestionItem(
    val id: String,
    val question: String = "",
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
)

@Serializable
data class QuestionOption(
    val label: String = "",
    val description: String? = null,
)

@Serializable
data class QuestionAnswerPayload(
    val requestId: String,
    val answers: List<QuestionAnswerItem> = emptyList(),
)

@Serializable
data class QuestionAnswerItem(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

// ---------------------------------------------------------------------------
// Payloads celular -> PC
// ---------------------------------------------------------------------------

@Serializable
data class HelloAckPayload(
    val deviceName: String,
    val deviceId: String,
    val lastSeq: Long = 0L,
)

@Serializable
data class SubscribePayload(val cursor: Long)

/** Um workspace do Harness: o lugar onde uma sessao nasce. */
@Serializable
data class WorkspaceInfo(
    val id: String,
    val title: String = "",
    val path: String = "",
    /** Sessoes que ja vivem nele; o retrato delas chega por session.upsert. */
    val sessions: List<String> = emptyList(),
)

@Serializable
data class WorkspaceListPayload(val workspaces: List<WorkspaceInfo> = emptyList())

@Serializable
data class PromptSendPayload(
    val sessionId: String,
    val text: String,
    val mode: String = PromptMode.Followup,
)

/**
 * Pedido de sessao nova num workspace.
 *
 * Vai workspaceId quando o app ja conhece o workspace, e path quando e um caminho
 * novo — o PC resolve ou cria o workspace nesse caminho.
 */
@Serializable
data class SessionCreatePayload(
    val workspaceId: String? = null,
    val path: String? = null,
)

@Serializable
data class ApprovalDecidePayload(
    val requestId: String,
    val outcome: String,
    val remember: Boolean? = null,
)

@Serializable
data class SessionCancelPayload(val sessionId: String)

@Serializable
data class SessionSelectPayload(val sessionId: String)

@Serializable
data class PingPayload(val echo: String = "")

// ---------------------------------------------------------------------------
// Quadros já tipados
// ---------------------------------------------------------------------------

/** Quadro de entrada já convertido no payload do seu tipo. */
sealed interface IncomingFrame {
    val seq: Long
    val ts: Long
    val session: String?

    data class Hello(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: HelloPayload,
    ) : IncomingFrame

    data class SessionUpsert(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: SessionUpsertPayload,
    ) : IncomingFrame

    data class WorkspaceList(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: WorkspaceListPayload,
    ) : IncomingFrame

    data class SessionGone(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: SessionGonePayload,
    ) : IncomingFrame

    data class TurnEvent(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: TurnEventPayload,
    ) : IncomingFrame

    data class ApprovalRequest(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: ApprovalRequestPayload,
    ) : IncomingFrame

    data class ApprovalResolved(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: ApprovalResolvedPayload,
    ) : IncomingFrame

    data class DeskState(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: DeskStatePayload,
    ) : IncomingFrame

    data class Notice(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: NoticePayload,
    ) : IncomingFrame

    data class QuestionRequest(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: QuestionRequestPayload,
    ) : IncomingFrame

    data class QuestionResolved(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: QuestionResolvedPayload,
    ) : IncomingFrame

    data class ReplayDone(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: ReplayDonePayload,
    ) : IncomingFrame

    data class Pong(
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: PongPayload,
    ) : IncomingFrame

    /** Tipo que esta versão do app ainda não conhece: guardado, não descartado. */
    data class Unknown(
        val type: String,
        override val seq: Long,
        override val ts: Long,
        override val session: String?,
        val payload: JsonObject,
    ) : IncomingFrame
}

/**
 * Codec do protocolo: JSON puro, sem dependência de Android — dá para testar na JVM.
 */
object PhCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

    /** Converte o texto de um quadro no seu tipo. Devolve null se o envelope não
     *  for um JSON válido; tipos desconhecidos viram [IncomingFrame.Unknown]. */
    fun decode(raw: String): IncomingFrame? {
        val frame = runCatching { json.decodeFromString(Frame.serializer(), raw) }.getOrNull() ?: return null
        return decode(frame)
    }

    fun decode(frame: Frame): IncomingFrame {
        val seq = frame.seq
        val ts = frame.ts
        val session = frame.session
        return when (frame.type) {
            FrameType.Hello -> IncomingFrame.Hello(seq, ts, session, payload(frame, HelloPayload.serializer()))
            FrameType.SessionUpsert ->
                IncomingFrame.SessionUpsert(seq, ts, session, payload(frame, SessionUpsertPayload.serializer()))

            FrameType.WorkspaceList ->
                IncomingFrame.WorkspaceList(seq, ts, session, payload(frame, WorkspaceListPayload.serializer()))

            FrameType.SessionGone ->
                IncomingFrame.SessionGone(seq, ts, session, payload(frame, SessionGonePayload.serializer()))

            FrameType.TurnEvent ->
                IncomingFrame.TurnEvent(seq, ts, session, payload(frame, TurnEventPayload.serializer()))

            FrameType.ApprovalRequest ->
                IncomingFrame.ApprovalRequest(seq, ts, session, payload(frame, ApprovalRequestPayload.serializer()))

            FrameType.ApprovalResolved ->
                IncomingFrame.ApprovalResolved(seq, ts, session, payload(frame, ApprovalResolvedPayload.serializer()))

            FrameType.DeskState ->
                IncomingFrame.DeskState(seq, ts, session, payload(frame, DeskStatePayload.serializer()))

            FrameType.Notice ->
                IncomingFrame.Notice(seq, ts, session, payload(frame, NoticePayload.serializer()))

            FrameType.Pong -> IncomingFrame.Pong(seq, ts, session, payload(frame, PongPayload.serializer()))

            FrameType.QuestionRequest ->
                IncomingFrame.QuestionRequest(seq, ts, session, payload(frame, QuestionRequestPayload.serializer()))

            FrameType.QuestionResolved ->
                IncomingFrame.QuestionResolved(seq, ts, session, payload(frame, QuestionResolvedPayload.serializer()))

            FrameType.ReplayDone ->
                IncomingFrame.ReplayDone(seq, ts, session, payload(frame, ReplayDonePayload.serializer()))

            else -> IncomingFrame.Unknown(frame.type, seq, ts, session, frame.payload)
        }
    }

    /** Monta o texto de um quadro de saída. */
    fun encode(frame: Frame): String = json.encodeToString(Frame.serializer(), frame)

    /** Serializa um payload tipado na árvore JSON do envelope. */
    inline fun <reified T> payloadOf(value: T): JsonObject = json.encodeToJsonElement(value).jsonObject

    fun outbound(
        type: String,
        payload: JsonObject,
        session: String? = null,
        seq: Long = 0L,
        ts: Long = System.currentTimeMillis(),
    ): String = encode(
        Frame(
            v = PH_PROTOCOL_VERSION,
            seq = seq,
            ts = ts,
            type = type,
            session = session,
            payload = payload,
        ),
    )

    fun helloAck(deviceName: String, deviceId: String, lastSeq: Long): String =
        outbound(FrameType.HelloAck, payloadOf(HelloAckPayload(deviceName, deviceId, lastSeq)))

    fun subscribe(cursor: Long): String =
        outbound(FrameType.Subscribe, payloadOf(SubscribePayload(cursor)))

    fun promptSend(sessionId: String, text: String, mode: String = PromptMode.Followup): String =
        outbound(FrameType.PromptSend, payloadOf(PromptSendPayload(sessionId, text, mode)), session = sessionId)

    fun approvalDecide(requestId: String, outcome: String, remember: Boolean? = null): String =
        outbound(FrameType.ApprovalDecide, payloadOf(ApprovalDecidePayload(requestId, outcome, remember)))

    fun questionAnswer(requestId: String, answers: List<QuestionAnswerItem>): String =
        outbound(FrameType.QuestionAnswer, payloadOf(QuestionAnswerPayload(requestId, answers)))

    fun sessionCancel(sessionId: String): String =
        outbound(FrameType.SessionCancel, payloadOf(SessionCancelPayload(sessionId)), session = sessionId)

    fun sessionSelect(sessionId: String): String =
        outbound(FrameType.SessionSelect, payloadOf(SessionSelectPayload(sessionId)), session = sessionId)

    /** Pede ao PC a lista de workspaces do Harness. */
    fun workspaceList(): String = outbound(FrameType.WorkspaceList, JsonObject(emptyMap()))

    /** Abre sessao nova: num workspace conhecido ou num caminho. */
    fun sessionCreate(workspaceId: String? = null, path: String? = null): String =
        outbound(FrameType.SessionCreate, payloadOf(SessionCreatePayload(workspaceId, path)))

    fun ping(echo: String): String =
        outbound(FrameType.Ping, payloadOf(PingPayload(echo)))

    private fun <T> payload(frame: Frame, serializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, frame.payload)
}
