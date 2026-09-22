package com.pockethound.app.core.session

import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind

/**
 * A cauda de um replay grande: o que ainda é conversa, e não arquivo.
 *
 * ## O problema
 *
 * Quando o celular volta depois de um tempo fora — app fechado, tela apagada,
 * rede trocada —, o PC reenvia o buffer dele: ate milhares de quadros, horas de
 * conversa. Aplicar isso na tela e errado de um jeito que se sente: a conversa
 * enche de coisa velha, a rolagem pula e o que interessa (o fim) afunda.
 *
 * A regra aqui e a do usuario: **se nao viu, ja foi**. A tela do celular mostra
 * o presente — as ultimas [MENSAGENS] mensagens da conversa, com os passos que
 * vieram depois da mais antiga delas. O resto do replay e descartado.
 *
 * ## Por que dois limites
 *
 * Só por mensagem nao basta: um unico turno de trabalho pode ter dezenas de
 * linhas de ferramenta depois da ultima mensagem, e a cauda viraria o mesmo
 * despejo. O teto de itens e a rede de seguranca; o corte por mensagem garante
 * que o fio da conversa (o que voce disse, o que ele respondeu) esteja sempre
 * legivel. Vale o mais apertado dos dois.
 *
 * Funcao pura de proposito: e regra de produto, e da para testar sem tela, sem
 * rede e sem Android.
 */
object CaudaDoReplay {

    /** Quantas mensagens de conversa a cauda guarda. */
    const val MENSAGENS = 10

    /** Teto de linhas na cauda, contando passos de ferramenta e raciocinio. */
    const val MAX_ITENS = 60

    /**
     * Corta o replay para o que ainda interessa.
     *
     * @param itens transcricao montada a partir do replay, em ordem.
     * @param mensagens quantas mensagens de conversa guardar.
     * @param maxItens teto de linhas.
     * @return a cauda, que pode ser a propria lista quando ela ja e pequena.
     */
    fun cortar(
        itens: List<TurnItem>,
        mensagens: Int = MENSAGENS,
        maxItens: Int = MAX_ITENS,
    ): List<TurnItem> {
        if (itens.isEmpty()) return itens
        // Nada a cortar: a mesma lista, sem copia. E o caso comum — o replay de
        // uma queda de rede de dois segundos, que nao pode perder nada.
        if (itens.size <= maxItens && itens.count { it.kind.ehConversa() } <= mensagens) return itens

        // De tras para frente: acha onde comeca a decima mensagem contando do fim.
        var vistas = 0
        var corte = -1
        for (indice in itens.indices.reversed()) {
            if (!itens[indice].kind.ehConversa()) continue
            vistas += 1
            if (vistas > mensagens) break
            corte = indice
        }

        val porMensagem = if (corte >= 0) itens.subList(corte, itens.size).toList() else emptyList()
        val porTamanho = if (itens.size > maxItens) itens.subList(itens.size - maxItens, itens.size).toList() else itens

        return when {
            // Nenhuma mensagem no meio (so passos): vale o teto de linhas.
            porMensagem.isEmpty() -> porTamanho
            // O mais apertado dos dois limites manda.
            porMensagem.size <= porTamanho.size -> porMensagem
            else -> porTamanho
        }
    }

    /** Esta linha e conversa (o que se le) ou passo (o que se consulta)? */
    private fun TurnItemKind.ehConversa(): Boolean = when (this) {
        TurnItemKind.UserMessage, TurnItemKind.AssistantMessage, TurnItemKind.Notice -> true
        TurnItemKind.Reasoning, TurnItemKind.ToolCall, TurnItemKind.ToolResult, TurnItemKind.Error -> false
    }
}
