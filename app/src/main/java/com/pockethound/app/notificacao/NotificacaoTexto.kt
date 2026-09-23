package com.pockethound.app.notificacao

import com.pockethound.app.core.model.QuestionItem

/**
 * Os textos das notificações — puros, sem Android nenhum, para poderem ser
 * testados na JVM como o resto das regras deste app.
 */
object NotificacaoTexto {

    const val TITULO_APROVACAO = "Autorização necessária"
    const val TITULO_PERGUNTA = "O Harness perguntou"
    const val TITULO_TURNO = "Trabalho concluído"

    /** Teto do corpo: notificação é para ser lida de relance, não para tela. */
    const val LIMITE_CORPO = 160

    /**
     * Corpo de um pedido de aprovação.
     *
     * O motivo escrito por quem pediu é a melhor frase que existe ("escreve
     * fora do workspace"); sem motivo, sobram os argumentos, que pelo menos
     * dizem o QUÊ está sendo executado.
     *
     * @param toolName ferramenta pedida (bash, write, …).
     * @param reason frase humana do pedido, quando o Harness mandou uma.
     * @param args prévia dos argumentos, já formatada pelo chamador.
     */
    fun aprovacao(toolName: String, reason: String?, args: String): String {
        val ferramenta = toolName.trim().ifBlank { "Uma ferramenta" }
        val motivo = reason?.trim().orEmpty()
        val base = when {
            motivo.isNotEmpty() -> ferramenta + ": " + motivo
            args.isNotBlank() -> ferramenta + " pede: " + args
            else -> "O Harness quer executar " + ferramenta
        }
        return truncar(base)
    }

    /**
     * Corpo de uma pergunta do agente. Das várias perguntas de um só pedido, a
     * notificação mostra a primeira e conta as demais — o cartão na tela é que
     * mostra tudo.
     */
    fun pergunta(perguntas: List<QuestionItem>): String {
        val primeira = perguntas.firstOrNull()?.question?.trim().orEmpty()
        val base = if (primeira.isEmpty()) "Uma pergunta espera sua resposta." else primeira
        val sobras = if (perguntas.size > 1) " (+" + (perguntas.size - 1) + ")" else ""
        return truncar(base + sobras)
    }

    /**
     * Corpo do fim de turno. Sem nome de sessão o texto não promete o que não se
     * sabe — e prometer "a conversa" seria mentira em vez de informativo.
     */
    fun turno(tituloSessao: String?): String {
        val nome = tituloSessao?.trim().orEmpty()
        return if (nome.isEmpty()) {
            "O turno terminou — toque para ver o resultado."
        } else {
            "“" + nome + "” terminou o turno."
        }
    }

    /**
     * Colapsa o texto em uma linha e corta em [limite] caracteres com reticências.
     *
     * O corte recua um passo quando cairia no meio de um par surrogate (emoji
     * vindo de um argumento JSON): o pedaço sobrando seria um substituto
     * inválido no lugar da mensagem.
     */
    fun truncar(texto: String, limite: Int = LIMITE_CORPO): String {
        val limpo = colapsar(texto)
        if (limpo.length <= limite) return limpo
        var corte = (limite - 1).coerceAtLeast(0)
        if (corte > 0 && limpo[corte - 1].isHighSurrogate()) corte -= 1
        return limpo.take(corte).trimEnd() + "…"
    }

    /**
     * Um JSON de argumentos vem com quebras e recuos; uma frase vem quebrada em
     * várias linhas. Notificação é uma linha só.
     */
    private fun colapsar(texto: String): String =
        texto.replace(Regex("\\s+"), " ").trim()
}
