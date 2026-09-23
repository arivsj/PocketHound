package com.pockethound.app.notificacao

import com.pockethound.app.core.model.ApprovalRequest
import com.pockethound.app.core.model.QuestionRequestPayload

/**
 * O que o sistema de notificação do Android precisa saber: publique isto, ou
 * retire aquela.
 *
 * Nasce no [com.pockethound.app.data.repo.HoundRepository] de propósito — é o
 * único ponto em que os quadros já passaram pela marca de replay e pelo filtro
 * de recuperação, e notificar direto do fluxo de quadros reavizaria o passado a
 * cada reconexão. É consumido pelo [NotificacaoService], que decide COMO avisar
 * (canal, ícone, toque); este arquivo é o contrato puro entre os dois.
 */
sealed interface Aviso {

    /**
     * Identidade estável da notificação: a mesma aprovação, a mesma pergunta e
     * a mesma sessão viram SEMPRE o mesmo id — o Android atualiza a notificação
     * existente em vez de encher a bandeja de duplicatas.
     */
    val id: String

    /** Mostre (ou atualize) a notificação de [id]. */
    data class Publicar(
        override val id: String,
        val canal: CanalNotificacao,
        val titulo: String,
        val corpo: String,
    ) : Aviso

    /** Retire a notificação de [id] — ela deixou de pedir alguma coisa. */
    data class Cancelar(override val id: String) : Aviso

    companion object {

        /** "O PC quer executar isto — você autoriza?" */
        fun deAprovacao(pedido: ApprovalRequest): Publicar = Publicar(
            id = idAprovacao(pedido.requestId),
            canal = CanalNotificacao.PEDIDOS,
            titulo = NotificacaoTexto.TITULO_APROVACAO,
            corpo = NotificacaoTexto.aprovacao(pedido.toolName, pedido.reason, pedido.argsPreview),
        )

        /** A pergunta do `pockethound_ask` esperando o celular. */
        fun dePergunta(pedido: QuestionRequestPayload): Publicar = Publicar(
            id = idPergunta(pedido.requestId),
            canal = CanalNotificacao.PEDIDOS,
            titulo = NotificacaoTexto.TITULO_PERGUNTA,
            corpo = NotificacaoTexto.pergunta(pedido.questions),
        )

        /**
         * O trabalho começado terminou.
         *
         * @param sessionId sessão alvo — vira o id, para o fim do turno seguinte
         *   substituir este em vez de acumular.
         * @param tituloSessao nome da sessão; nulo/embrulhado cai no texto genérico.
         */
        fun deTurno(sessionId: String, tituloSessao: String?): Publicar = Publicar(
            id = idTurno(sessionId),
            canal = CanalNotificacao.TRABALHO,
            titulo = NotificacaoTexto.TITULO_TURNO,
            corpo = NotificacaoTexto.turno(tituloSessao),
        )

        fun idAprovacao(requestId: String): String = "aprovacao:" + requestId
        fun idPergunta(requestId: String): String = "pergunta:" + requestId
        fun idTurno(sessionId: String): String = "turno:" + sessionId
    }
}

/**
 * Canais do Android (API 26+): a importância — e o direito de aparecer na hora
 * com a tela bloqueada — é propriedade DO CANAL, não da notificação.
 */
enum class CanalNotificacao {
    /**
     * Algo precisa de resposta humana agora (aprovação, pergunta). IMPORTANCE_HIGH:
     * aparece na hora, até na tela bloqueada — a aprovação tem janela de ~90 s.
     */
    PEDIDOS,

    /** O turno terminou. IMPORTANCE_DEFAULT: som na bandeja, sem interromper. */
    TRABALHO,

    /** A notificação persistente do serviço (o que mantém o processo vivo). */
    CONEXAO,
}
