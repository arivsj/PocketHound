package com.pockethound.app.core.model

import com.pockethound.app.core.transport.TransportMode

/** Estado do pareamento e das preferências de conexão, lido do SettingsStorage. */
data class SessionSnapshot(
    val deviceId: String? = null,
    val deviceName: String = DEFAULT_DEVICE_NAME,
    val pcName: String? = null,
    val directBaseUrl: String = "",
    val p2pTicket: String = "",
    val transportMode: TransportMode = TransportMode.AUTO,
    val lastSeq: Long = 0L,
    val pendingApprovalTimeoutSeconds: Int = DEFAULT_APPROVAL_TIMEOUT_SECONDS,
) {
    val isPaired: Boolean = !deviceId.isNullOrBlank()

    companion object {
        const val DEFAULT_DEVICE_NAME = "PocketHound"
        const val DEFAULT_APPROVAL_TIMEOUT_SECONDS = 90
    }
}

/** Sessão do Harness como a UI do celular enxerga. */
data class Session(
    val id: String,
    val title: String = "",
    val workspace: String = "",
    val status: SessionStatus = SessionStatus.Idle,
    /** `subagent` quando a sessão é de um subagente do Harness. */
    val origin: String? = null,
    /** Profundidade na árvore de delegação. */
    val depth: Int? = null,
    /** Eventos no log; nulo nas sessões que existem só no disco. */
    val events: Long? = null,
    /** Último sinal de vida visto pelo PC (ms); nulo no que existe só no disco. */
    val lastSeen: Long? = null,
    /** Quando a sessão nasceu (ms). */
    val createdAt: Long? = null,
) {
    /** Subagente trabalhando em segundo plano — a tela pode agrupar à parte. */
    val isSubagent: Boolean get() = origin == "subagent"

    fun toInfo(): SessionInfo = SessionInfo(
        id = id,
        title = title,
        workspace = workspace,
        status = status.wire,
        lastSeen = lastSeen,
        createdAt = createdAt,
    )
}

enum class SessionStatus(val wire: String) {
    Idle("idle"),
    Running("running"),
    Waiting("waiting"),
    Error("error"),
    Offline("offline"),
    ;

    companion object {
        fun fromWire(value: String?): SessionStatus =
            entries.firstOrNull { it.wire == value } ?: Idle
    }
}

/** Aprovação pendente na fila do celular. */
data class ApprovalRequest(
    val requestId: String,
    val toolName: String,
    val callId: String? = null,
    val reason: String? = null,
    val argsPreview: String = "",
    val sessionId: String? = null,
    val expiresAt: Long = 0L,
)

/**
 * O que aconteceu com uma decisão de aprovação que saiu do celular.
 *
 * Existe porque "toquei e não aconteceu nada" é indistinguível de "toquei e o
 * comando nem saiu daqui". Sem separar as duas coisas, o usuário fica tocando no
 * escuro — e quem for consertar fica adivinhando de que lado está o defeito.
 */
data class DecisaoDeAprovacao(
    val estado: EstadoDaDecisao,
    /** Explicação curta, com o código HTTP ou o motivo da falha. */
    val detalhe: String = "",
)

enum class EstadoDaDecisao {
    /** O comando está saindo do aparelho. */
    Enviando,

    /** Chegou ao PC. O PC ainda pode recusar por conta própria. */
    Entregue,

    /** Não saiu, ou saiu e o PC recusou na porta. */
    Recusada,
}

/** Uma linha da transcrição de uma sessão. */
data class TurnItem(
    val id: String,
    val kind: TurnItemKind,
    val text: String = "",
    val toolName: String? = null,
    val ok: Boolean? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** Turno e passo de origem — é o que permite juntar os deltas do mesmo
     *  passo sem misturar com o passo anterior. Nulo no que nasce no celular. */
    val turn: Int? = null,
    val step: Int? = null,
    /** Ainda recebendo deltas. A tela usa isto para mostrar o cursor piscando. */
    val streaming: Boolean = false,
    /**
     * Rótulo humano da linha ("Código", "Comando", "Pensou", "Leitura").
     *
     * É o que o Harness escreve no navegador — e o que substitui o JSON cru de
     * argumentos, que esconde justamente a frase que o modelo escreveu para você.
     */
    val label: String? = null,
    /** Assunto de uma linha: o que a ação faz, em palavras. */
    val subject: String? = null,
    /** Corpo completo, para o painel que abre no toque (argumentos ou resultado). */
    val detail: String? = null,
    /** Chamada que originou este item — é o que casa o resultado com a chamada. */
    val callId: String? = null,
)

enum class TurnItemKind {
    UserMessage,
    AssistantMessage,
    Reasoning,
    ToolCall,
    ToolResult,
    Notice,
    Error,
}

/** Estado do PC, atualizado a cada 2 s pelo quadro desk.state. */
data class DeskState(
    val cpu: Double? = null,
    val mem: Double? = null,
    val gpu: Double? = null,
    val temp: Double? = null,
    val load: Double? = null,
    val uptime: Long? = null,
) {
    companion object {
        /**
         * Traduz o retrato que o desk manda para o que a tela mostra.
         *
         * Os nomes não batem de propósito: o desk fala em unidades do sistema
         * (`memUsedMb`, `temperatureC`) e a tela fala em percentual. Fazer a
         * conta aqui, uma vez, evita que cada tela invente a sua.
         */
        fun fromPayload(payload: com.pockethound.app.core.model.DeskStatePayload): DeskState {
            val memPct = if (payload.memTotalMb > 0) {
                payload.memUsedMb.toDouble() / payload.memTotalMb.toDouble() * 100.0
            } else {
                null
            }
            return DeskState(
                cpu = payload.cpuPercent,
                mem = memPct,
                // A carga de 1 minuto normalizada pelo número de núcleos: 1.0 é
                // "todos os núcleos ocupados". Sem isso, uma máquina de 16
                // núcleos pareceria ociosa com carga 8.
                load = payload.loadAvg.firstOrNull()?.let { valor ->
                    if (payload.cores > 0) valor / payload.cores else valor
                },
                temp = payload.temperatureC,
                uptime = payload.uptimeSeconds,
            )
        }
    }

    /**
     * Carga do PC de 0 a 1 — decide a cor da chuva: violeta em repouso, âmbar sob
     * carga, perigo em faixa crítica (DESIGN.md §5.5).
     */
    val loadFactor: Float
        get() {
            val valores = listOfNotNull(cpu, mem, gpu).map { (it / 100.0).coerceIn(0.0, 1.0) }
            if (valores.isEmpty()) return 0f
            return valores.max().toFloat()
        }
}

/** Aviso curto vindo do PC (notice). */
data class Notice(
    val level: String,
    val title: String,
    val body: String = "",
)

/** Estado do link com o PC, mostrado na aba Frota e na chuva de fundo. */
data class LinkState(
    val status: LinkStatus,
    val path: String,
    val latencyMs: Long? = null,
    val reason: String? = null,
) {
    val isOnline: Boolean get() = status == LinkStatus.Online
}

enum class LinkStatus {
    Offline,
    Connecting,
    Online,
}

/** Rótulos das 4 abas da barra inferior (DESIGN.md §7.2). */
enum class PhTab(val route: String, val label: String) {
    Chat("chat", "Chat"),
    Approvals("approvals", "Aprovar"),
    Fleet("fleet", "Frota"),
    Settings("settings", "Ajustes"),
}
