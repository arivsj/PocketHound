package com.pockethound.app.core.session

import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.core.model.TurnKind

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
 * @param atual transcrição da sessão, já em ordem.
 * @param quadro quadro recebido do PC.
 * @return a transcrição nova, ou a mesma lista quando o quadro não muda a
 *   transcrição (estado da máquina, aprovação, replay).
 */
object TranscriptReducer {

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

            TurnKind.UserMessage -> atual + TurnItem(
                id = "u-${quadro.seq}",
                kind = TurnItemKind.UserMessage,
                text = payload.text.orEmpty(),
                timestamp = quadro.ts,
            )

            TurnKind.ToolCall -> atual + TurnItem(
                id = "c-${payload.callId ?: quadro.seq}",
                kind = TurnItemKind.ToolCall,
                text = payload.args?.toString().orEmpty(),
                toolName = payload.name,
                timestamp = quadro.ts,
                turn = payload.turn,
                step = payload.step,
            )

            TurnKind.ToolResult -> atual + TurnItem(
                id = "r-${payload.callId ?: quadro.seq}",
                kind = if (payload.isError == true) TurnItemKind.Error else TurnItemKind.ToolResult,
                text = payload.text.orEmpty(),
                ok = payload.isError != true,
                timestamp = quadro.ts,
                turn = payload.turn,
                step = payload.step,
            )

            // O turno acabou: nada pode continuar "recebendo deltas", senão o
            // cursor da tela pisca para sempre.
            TurnKind.TurnEnd -> atual.map { if (it.streaming) it.copy(streaming = false) else it }

            TurnKind.TurnStart, TurnKind.StepStart, TurnKind.StepEnd, TurnKind.TodoWrite -> atual

            else -> atual
        }
    }

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
            restante + TurnItem(
                id = "m-${turn}-${step}",
                kind = TurnItemKind.AssistantMessage,
                text = texto,
                turn = turn,
                step = step,
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
        if (posicao < 0) return consolidado + raciocinio
        return consolidado.toMutableList().also { it.add(posicao, raciocinio) }
    }
}
