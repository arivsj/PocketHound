package com.pockethound.app.core.session

import com.pockethound.app.core.model.TurnKind

/**
 * O que a tela precisa contar sobre um prompt que ja saiu do aparelho.
 *
 * Sem isto, um prompt aceito pelo PC e nunca respondido deixa a tela MUDA: a
 * linha do usuario aparece (ela e eco local) e nada mais acontece. O usuario nao
 * tem como distinguir "o modelo esta pensando" de "a sessao travou".
 */
enum class PromptAck {
    /** Nada pendente: o PC ja deu sinal de vida, ou ninguem mandou prompt. */
    None,

    /** Entregue e ainda dentro do prazo. */
    Waiting,

    /** Entregue, prazo estourado, e NENHUM quadro dessa sessao chegou. */
    Silent,

    /** A sessao abriu turno/passo e parou sem produzir resposta. */
    Stalled,
}

/** Retrato do prompt em voo, para a tela desenhar o aviso. */
data class PromptStatus(
    val ack: PromptAck = PromptAck.None,
    val sessionId: String? = null,
    val text: String = "",
    /** Ha quanto tempo o prompt saiu do aparelho. */
    val waitedMs: Long = 0L,
    /** A sessao ja estava ocupada quando o prompt saiu: a espera e esperada. */
    val queued: Boolean = false,
    /**
     * O prompt foi marcado como "furar fila".
     *
     * Nao e o mesmo que [queued]: ele nao espera o turno atual terminar, e sim
     * entra no meio dele, no proximo passo. O aviso da tela diz isso — antes
     * dizia "entrou na fila do proximo turno" para uma mensagem que nao entrou
     * em fila nenhuma.
     */
    val furarFila: Boolean = false,
)

/**
 * Vigia o prompt enviado ate o PC provar que esta trabalhando nele.
 *
 * ## O que conta como prova
 *
 * Nao basta chegar QUALQUER quadro. No caso real que originou isto, o PC mandou
 * `turn.start`, `step.start` e `step.end` em seis segundos e morreu — o turno
 * abriu, nada foi produzido e a sessao ficou travada com o turno aberto. Um vigia
 * que se satisfizesse com "chegou quadro" nao veria nada de errado.
 *
 * Por isso a distincao: quadro de **abertura** (turno, passo, o eco da sua
 * mensagem) prova que o PC recebeu; quadro de **producao** (texto, raciocinio,
 * ferramenta, fim de turno) prova que o modelo esta trabalhando. O primeiro caso
 * sozinho, passado o prazo, e exatamente o sintoma de sessao travada.
 *
 * ## Prazos
 *
 * O prazo e maior quando a sessao JA estava ocupada: ai o prompt entra na fila
 * do proximo turno e a demora e legitima, nao sintoma de nada.
 *
 * A classe e pura e recebe o relogio por parametro — da para testar a passagem
 * do tempo sem esperar por ela.
 *
 * @param answerWindowMs prazo para a sessao ociosa produzir resposta.
 * @param queueWindowMs prazo para a sessao que ja estava ocupada.
 */
class PromptWatchdog(
    private val answerWindowMs: Long = ANSWER_WINDOW_MS,
    private val queueWindowMs: Long = QUEUE_WINDOW_MS,
) {
    private var sessionId: String? = null
    private var text: String = ""
    private var sentAt: Long = 0L

    /** Chegou algum quadro da sessao — qualquer um. */
    private var sawFrame: Boolean = false

    /** A sessao estava ocupada quando o prompt saiu. */
    private var busy: Boolean = false

    /** O usuario marcou "furar fila" neste envio. */
    private var furouFila: Boolean = false

    /**
     * Registra o prompt que acabou de ser aceito pelo PC.
     *
     * @param sessionId sessao de destino.
     * @param text o que foi enviado.
     * @param queued se a sessao ja estava trabalhando.
     * @param now instante do envio.
     * @param furarFila se foi enviado como prioridade (entra no turno em curso).
     * @return o estado inicial do acompanhamento.
     */
    fun sent(sessionId: String, text: String, queued: Boolean, now: Long, furarFila: Boolean = false): PromptStatus {
        this.sessionId = sessionId
        this.text = text
        this.sentAt = now
        this.sawFrame = false
        this.busy = queued
        this.furouFila = furarFila
        return status(now)
    }

    /**
     * Informa um quadro vindo do PC.
     *
     * Quadro de outra sessao nao diz nada sobre este prompt e e ignorado.
     *
     * @param session sessao do quadro.
     * @param kind tipo do evento de turno (`turn.event`), quando houver.
     * @param now instante da chegada.
     * @return o estado depois do quadro.
     */
    fun frame(session: String?, kind: String?, now: Long): PromptStatus {
        val alvo = sessionId ?: return PromptStatus()
        if (session != alvo) return status(now)
        if (kind != null && produziuResposta(kind)) return clear()
        sawFrame = true
        return status(now)
    }

    /** Reavalia o prazo. Idempotente: chamar de novo nao muda o que ja venceu. */
    fun tick(now: Long): PromptStatus = status(now)

    /** Esquece o prompt em voo (pareamento desfeito, sessao escolhida de novo). */
    fun clear(): PromptStatus {
        sessionId = null
        text = ""
        sentAt = 0L
        sawFrame = false
        busy = false
        furouFila = false
        return PromptStatus()
    }

    /** Estado atual, sem efeito colateral. */
    fun status(now: Long): PromptStatus {
        val alvo = sessionId ?: return PromptStatus()
        val waited = (now - sentAt).coerceAtLeast(0L)
        // Furar fila tambem tem prazo longo, por outro motivo: a resposta comeca
        // no proximo passo, e um passo com ferramenta demorada leva minutos.
        val janela = if (busy || furouFila) queueWindowMs else answerWindowMs
        val ack = when {
            waited < janela -> PromptAck.Waiting
            sawFrame -> PromptAck.Stalled
            else -> PromptAck.Silent
        }
        return PromptStatus(
            ack = ack,
            sessionId = alvo,
            text = text,
            waitedMs = waited,
            queued = busy,
            furarFila = furouFila,
        )
    }

    companion object {
        /** Prazo de uma sessao ociosa: o primeiro token costuma vir em segundos. */
        const val ANSWER_WINDOW_MS = 60_000L

        /** Prazo de uma sessao ja ocupada: o prompt esta atras de um turno. */
        const val QUEUE_WINDOW_MS = 180_000L

        /**
         * O quadro prova que o modelo esta produzindo?
         *
         * Abertura de turno e de passo NAO provam: foi exatamente essa a
         * assinatura da sessao que travou.
         *
         * @param kind tipo do evento de turno.
         * @return se conta como producao.
         */
        fun produziuResposta(kind: String): Boolean = when (kind) {
            TurnKind.TextDelta,
            TurnKind.TextDone,
            TurnKind.ReasoningDelta,
            TurnKind.ToolCall,
            TurnKind.ToolResult,
            TurnKind.TurnEnd,
            -> true

            else -> false
        }
    }
}
