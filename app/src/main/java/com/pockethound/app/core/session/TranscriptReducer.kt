package com.pockethound.app.core.session

import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.model.TurnKind

// A regra de como uma chamada vira texto legivel mora no ToolSummary: aqui so
// dobramos o quadro na transcricao.

/**
 * Dobra um quadro do PC na transcrição de uma sessão.
 *
 * É uma função pura de propósito: a regra de juntar deltas é a parte da UI que
 * mais erra, e aqui ela pode ser testada sem tela, sem rede e sem Android.
 *
 * ## O problema que ele resolve
 *
 * O PC emite o texto do agente **em pedaços** — um quadro `text.delta` por
 * rajada de tokens, agrupados numa janela de 40 ms. Uma resposta de três
 * parágrafos chega em dezenas de quadros. Se cada um virasse um balão, a tela
 * ficaria com dezenas de balões de duas palavras.
 *
 * A regra: um delta **continua** o balão anterior se ele é do mesmo turno e do
 * mesmo passo e ainda está aberto. Quando o PC manda o `text.done`, ele traz o
 * texto consolidado e **substitui** os deltas — assim o markdown final fica
 * certo, sem os cortes que a montagem por pedaços produz.
 *
 * ## E o mesmo quadro duas vezes
 *
 * Todo item que nasce de um quadro tem **id derivado do quadro** (o `seq`, o
 * `callId`, o turno e o passo), e um id que já está na conversa não entra de
 * novo. É o que torna esta função segura para o reenvio pedido à mão: o PC
 * devolve o buffer inteiro e só o que faltava muda a tela.
 *
 * @param atual transcrição da sessão, já em ordem.
 * @param quadro quadro recebido do PC.
 * @return a transcrição nova, ou a mesma lista quando o quadro não muda a
 *   transcrição (estado da máquina, aprovação, replay).
 */
object TranscriptReducer {

    /**
     * Prefixo dos itens que nascem no celular antes do eco do PC.
     *
     * O eco existe para a mensagem aparecer no instante do toque, sem esperar a
     * ida e volta; ele é substituído pelo item do PC quando o `user.message`
     * chega (ver `tirarEcoLocal`).
     */
    const val ECO_LOCAL = "local-"

    fun reduce(atual: List<TurnItem>, quadro: IncomingFrame): List<TurnItem> {
        if (quadro !is IncomingFrame.TurnEvent) return atual
        val payload = quadro.payload
        return when (payload.kind) {
            TurnKind.TextDelta -> acumular(atual, payload, TurnItemKind.AssistantMessage)
            TurnKind.ReasoningDelta -> acumular(atual, payload, TurnItemKind.Reasoning)

            TurnKind.TextDone -> fechar(
                atual,
                payload.turn,
                payload.step,
                // O consolidado vence: é o texto que o modelo de fato produziu,
                // sem os cortes da montagem por pedaços.
                payload.text?.takeIf { it.isNotBlank() },
                contexto = payload.reasoning?.takeIf { it.isNotBlank() },
            )

            TurnKind.UserMessage -> {
                val novo = TurnItem(
                    id = "u-${quadro.seq}",
                    kind = TurnItemKind.UserMessage,
                    text = payload.text.orEmpty(),
                    timestamp = quadro.ts,
                )
                // O PC devolve o MESMO texto que o app já mostrou como eco local
                // quando você tocou em enviar. Os dois têm ids diferentes (o eco
                // nasce aqui, o do PC vem do `user.message`), então o `juntar` —
                // que deduplica por id — não os reconhece como o mesmo item, e a
                // sua mensagem aparecia duas vezes na tela.
                juntar(tirarEcoLocal(atual, novo.text), novo)
            }

            // A chamada vira uma LINHA legivel, no formato do Harness: rotulo
            // ("Comando", "Leitura") e assunto. O JSON dos argumentos vai para o
            // detalhe, que so abre no toque — na linha ele esconderia justamente
            // a frase que o modelo escreveu para ser lida.
            TurnKind.ToolCall -> {
                val linha = ToolSummary.of(payload.name.orEmpty(), payload.args)
                juntar(
                    atual,
                    TurnItem(
                        id = "c-${payload.callId ?: quadro.seq}",
                        kind = TurnItemKind.ToolCall,
                        text = linha.subject,
                        label = linha.label,
                        subject = linha.subject,
                        detail = ToolSummary.pretty(payload.args),
                        toolName = payload.name,
                        callId = payload.callId,
                        timestamp = quadro.ts,
                        turn = payload.turn,
                        step = payload.step,
                    ),
                )
            }

            // O resultado casa com a chamada pelo callId: e o que da nome a linha
            // ("resultado · " vazio era o que aparecia antes) e o que permite
            // mostrar o assunto da chamada junto do desfecho.
            TurnKind.ToolResult -> {
                val chamada = payload.callId?.let { id ->
                    atual.lastOrNull { it.kind == TurnItemKind.ToolCall && it.callId == id }
                }
                val falhou = payload.isError == true
                juntar(
                    atual,
                    TurnItem(
                        id = "r-${payload.callId ?: quadro.seq}",
                        kind = if (falhou) TurnItemKind.Error else TurnItemKind.ToolResult,
                        text = ToolSummary.resultSubject(payload.text.orEmpty()),
                        detail = payload.text.orEmpty(),
                        // Sem a chamada (resultado orfao, replay cortado) o rotulo
                        // generico ainda e melhor que uma linha sem nome nenhum.
                        label = chamada?.label ?: ToolSummary.label(payload.name.orEmpty()),
                        subject = chamada?.subject,
                        toolName = chamada?.toolName ?: payload.name,
                        callId = payload.callId,
                        ok = !falhou,
                        timestamp = quadro.ts,
                        turn = payload.turn,
                        step = payload.step,
                    ),
                )
            }

            // O turno acabou: nada pode continuar "recebendo deltas", senão o
            // cursor da tela pisca para sempre.
            TurnKind.TurnEnd -> atual.map { if (it.streaming) it.copy(streaming = false) else it }

            TurnKind.TurnStart, TurnKind.StepStart, TurnKind.StepEnd, TurnKind.TodoWrite -> atual

            else -> atual
        }
    }

    /**
     * Tira o eco local que corresponde a este texto, se houver.
     *
     * O casamento é por TEXTO, e só para o eco local: é o único caso em que dois
     * itens diferentes são a mesma mensagem. Um por vez, e do fim para o começo,
     * para que duas mensagens iguais mandadas de propósito continuem sendo duas —
     * cada eco local casa com um eco do PC, na ordem.
     *
     * Limite conhecido: o PC corta textos muito longos antes de mandar, então um
     * prompt gigante (mais de 12 mil caracteres) pode não casar e continuar
     * aparecendo duas vezes. É raro e não vale complicar a regra por isso.
     *
     * @param atual conversa antes do eco do PC.
     * @param texto texto que o PC devolveu.
     * @return a conversa sem o eco local correspondente, quando havia.
     */
    private fun tirarEcoLocal(atual: List<TurnItem>, texto: String): List<TurnItem> {
        if (texto.isBlank()) return atual
        val indice = atual.indexOfLast { it.id.startsWith(ECO_LOCAL) && it.text == texto }
        if (indice < 0) return atual
        return atual.toMutableList().also { it.removeAt(indice) }
    }

    /**
     * Junta um item novo, sem repetir id.
     *
     * O reenvio que o botão "atualizar" pede (e a reconexão depois de um replay
     * cortado) traz de volta quadros que já estão na conversa. Item repetido não
     * é só feio: o id é a CHAVE de cada linha da lista, e chave repetida derruba
     * a tela inteira.
     *
     * @param atual conversa antes do item.
     * @param item item a juntar.
     * @return a conversa com o item, ou a mesma lista quando ele já estava lá.
     */
    private fun juntar(atual: List<TurnItem>, item: TurnItem): List<TurnItem> =
        if (atual.any { it.id == item.id }) atual else atual + item

    /** Continua o balão aberto do mesmo passo, ou abre um novo. */
    private fun acumular(
        atual: List<TurnItem>,
        payload: com.pockethound.app.core.model.TurnEventPayload,
        tipo: TurnItemKind,
    ): List<TurnItem> {
        val texto = payload.text.orEmpty()
        if (texto.isEmpty()) return atual

        val ultimo = atual.lastOrNull()
        val continua = ultimo != null &&
            ultimo.kind == tipo &&
            ultimo.streaming &&
            ultimo.turn == payload.turn &&
            ultimo.step == payload.step

        return if (continua) {
            atual.dropLast(1) + ultimo!!.copy(text = ultimo.text + texto)
        } else {
            atual + TurnItem(
                id = "d-${payload.turn}-${payload.step}-${payload.index ?: 0}-${atual.size}",
                kind = tipo,
                text = texto,
                timestamp = payload.at ?: System.currentTimeMillis(),
                turn = payload.turn,
                step = payload.step,
                streaming = true,
            )
        }
    }

    /** Fecha o passo, trocando o texto acumulado pelo consolidado. */
    private fun fechar(
        atual: List<TurnItem>,
        turn: Int?,
        step: Int?,
        texto: String?,
        contexto: String?,
    ): List<TurnItem> {
        val restante = atual.map {
            if (it.streaming && it.turn == turn && it.step == step) it.copy(streaming = false) else it
        }
        if (texto == null) return restante

        // Substitui o balão montado por deltas pelo texto consolidado.
        val indice = restante.indexOfLast {
            it.kind == TurnItemKind.AssistantMessage && it.turn == turn && it.step == step
        }
        val consolidado = if (indice >= 0) {
            restante.toMutableList().also {
                it[indice] = it[indice].copy(text = texto, streaming = false)
            }
        } else {
            juntar(
                restante,
                TurnItem(
                    id = "m-${turn}-${step}",
                    kind = TurnItemKind.AssistantMessage,
                    text = texto,
                    turn = turn,
                    step = step,
                ),
            )
        }

        // O raciocínio vem junto no fechamento; sem isto ele se perderia, porque
        // os deltas dele já foram fechados acima.
        if (contexto == null) return consolidado
        if (consolidado.any { it.kind == TurnItemKind.Reasoning && it.turn == turn && it.step == step && it.text == contexto }) {
            return consolidado
        }

        // **Antes** da resposta, não depois: o modelo pensa e então responde.
        // Ler o raciocínio embaixo do texto que ele produziu inverte a
        // causalidade na tela.
        val posicao = consolidado.indexOfFirst {
            it.kind == TurnItemKind.AssistantMessage && it.turn == turn && it.step == step
        }
        val raciocinio = TurnItem(
            id = "t-${turn}-${step}",
            kind = TurnItemKind.Reasoning,
            text = contexto,
            turn = turn,
            step = step,
        )
        if (posicao < 0) return juntar(consolidado, raciocinio)
        return consolidado.toMutableList().also { it.add(posicao, raciocinio) }
    }
}
