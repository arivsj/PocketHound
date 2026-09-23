package com.pockethound.app.core.session

import com.pockethound.app.core.model.PerguntaNaTela
import com.pockethound.app.core.model.QuestionRequestPayload

/**
 * A fila de perguntas do agente — a regra pura de quem entra e quem sai.
 *
 * Três verdades que só parecem triviais juntas:
 *
 * 1. **Resolvida sai** — o `question.resolved` (de qualquer tela) tira o
 *    cartão; fila que já foi respondida e continua de pé mente, como já diziam
 *    o hub do plugin ("o cartão sai do celular na hora") e o comentário do
 *    `answerQuestion` ("quem esvazia é o quadro question.resolved").
 * 2. **Resolvida não ressuscita** — reconexão, reinício do processo e
 *    renumeração do PC reentregam o `question.request` do buffer; sem a
 *    memória de resolvidas, o cartão voltava "de pé" com a mesma pergunta que
 *    já tinha resposta — o defeito de campo que deu origem a este arquivo.
 * 3. **A trava local não se apaga** — o celular que respondeu trava o cartão
 *    na ida; um reenvio do mesmo pedido não pode destravar.
 */
object FilaDePerguntas {

    /** Teto da memória de resolvidas — um uuid por pergunta, ~36 bytes cada. */
    const val LIMITE_RESOLVIDAS = 100

    /**
     * Um `question.request` chegou.
     *
     * @return a fila nova, ou **a mesma instância** quando o pedido não muda
     *   nada (replay de pergunta já resolvida, ou trava local a preservar) — o
     *   chamador usa a identidade para não reavisar o que não mudou.
     */
    fun receber(
        atual: List<PerguntaNaTela>,
        pedido: QuestionRequestPayload,
        resolvidas: List<String>,
    ): List<PerguntaNaTela> {
        if (pedido.requestId in resolvidas) return atual
        val existente = atual.firstOrNull { it.pedido.requestId == pedido.requestId }
        if (existente?.resposta != null) return atual
        return atual.filterNot { it.pedido.requestId == pedido.requestId } +
            PerguntaNaTela(pedido = pedido)
    }

    /** Um `question.resolved` chegou: o cartão sai, esteja ou não na fila. */
    fun resolver(atual: List<PerguntaNaTela>, requestId: String): List<PerguntaNaTela> =
        atual.filterNot { it.pedido.requestId == requestId }

    /**
     * Grava a resolvida na memória, mais recente na frente e com teto — é esta
     * lista que vai para o disco junto com o pedido rejeitado.
     */
    fun marcarResolvida(resolvidas: List<String>, requestId: String): List<String> {
        if (requestId.isBlank()) return resolvidas
        return (listOf(requestId) + resolvidas.filterNot { it == requestId })
            .take(LIMITE_RESOLVIDAS)
    }
}