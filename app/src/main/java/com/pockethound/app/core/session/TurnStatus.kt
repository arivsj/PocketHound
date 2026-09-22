package com.pockethound.app.core.session

import com.pockethound.app.core.model.IncomingFrame
import com.pockethound.app.core.model.TurnKind

/**
 * Estado do turno de uma sessao — o que a tela precisa para dizer que ALGO esta
 * acontecendo.
 *
 * Sem isto o chat mostra mensagens soltas e o usuario nao sabe se o agente esta
 * trabalhando, parado, ou esperando a vez atras de outra coisa. O Harness no
 * navegador mostra isso o tempo todo ("Running", o tempo decorrido, quantas
 * mensagens estao na fila); aqui e a mesma ideia, do tamanho de um celular.
 *
 * @param running se ha turno aberto agora.
 * @param turn numero do turno em curso.
 * @param startedAt quando o turno em curso comecou (ms), zero quando parado.
 * @param steps passos ja vistos no turno em curso.
 * @param turns maior numero de turno ja visto nesta sessao.
 * @param queued mensagens na fila, como o PC informou.
 * @param tokensIn tokens de entrada somados nesta sessao.
 * @param tokensOut tokens de saida somados nesta sessao.
 * @param custoUsd gasto da sessao em dolar, como o PC calculou.
 * @param custoUsdPico quanto desse gasto caiu em horario de pico.
 * @param contextoUsado tokens que a proxima requisicao vai levar.
 * @param contextoJanela janela de contexto do modelo.
 * @param modelo id do modelo que esta valendo nesta sessao, como o PC informou.
 */
data class TurnStatus(
    val running: Boolean = false,
    val turn: Int? = null,
    val startedAt: Long = 0L,
    val steps: Int = 0,
    val turns: Int = 0,
    val queued: Int = 0,
    val tokensIn: Long = 0L,
    val tokensOut: Long = 0L,
    val custoUsd: Double = 0.0,
    val custoUsdPico: Double = 0.0,
    val entrada: Long = 0L,
    val saida: Long = 0L,
    val cache: Long = 0L,
    val contextoUsado: Long = 0L,
    val contextoJanela: Long = 0L,
    val modelo: String? = null,
) {
    /** Ha quanto tempo o turno esta aberto, do ponto de vista de [agora]. */
    fun decorrido(agora: Long): Long =
        if (running && startedAt > 0L) (agora - startedAt).coerceAtLeast(0L) else 0L

    /** O contexto esta enchendo? Nulo enquanto o PC nao informou a janela. */
    val contextoPct: Double?
        get() = if (contextoJanela > 0L) contextoUsado.toDouble() / contextoJanela.toDouble() * 100.0 else null

    /** Ha o que mostrar no rodape? (gasto ou contexto) */
    val temRetrato: Boolean
        get() = custoUsd > 0.0 || contextoJanela > 0L
}

/**
 * Dobra os quadros do PC no [TurnStatus] de uma sessao.
 *
 * Pura e sem relogio proprio: o instante vem do proprio quadro, entao o mesmo
 * log produz sempre o mesmo estado — e o teste nao precisa esperar.
 */
object TurnStatusReducer {

    /**
     * Aplica um quadro ao estado.
     *
     * @param atual estado da sessao antes do quadro.
     * @param quadro quadro recebido do PC.
     * @return o estado depois do quadro.
     */
    fun fold(atual: TurnStatus, quadro: IncomingFrame): TurnStatus {
        if (quadro !is IncomingFrame.TurnEvent) return atual
        val payload = quadro.payload
        return when (payload.kind) {
            TurnKind.TurnStart -> atual.copy(
                running = true,
                turn = payload.turn ?: atual.turn,
                startedAt = if (payload.turn != atual.turn) quadro.ts else atual.startedAt,
                steps = if (payload.turn != atual.turn) 0 else atual.steps,
                turns = maxOf(atual.turns, payload.turn ?: 0),
            )

            // Qualquer sinal de trabalho DENTRO de um turno prova que ele esta
            // aberto — inclusive quando o quadro de abertura se perdeu no replay
            // ou foi descartado na contrapressao. Sem isto o relogio e o "passo N"
            // ficavam velhos com o agente trabalhando, que e a pior hora para a
            // tela mentir.
            TurnKind.StepStart -> atual.copy(
                steps = maxOf(atual.steps, payload.step ?: 0),
                running = true,
                turn = payload.turn ?: atual.turn,
                startedAt = if (atual.running) atual.startedAt else quadro.ts,
            )

            TurnKind.TextDelta, TurnKind.ReasoningDelta, TurnKind.ToolCall -> atual.copy(
                running = true,
                turn = payload.turn ?: atual.turn,
                startedAt = if (atual.running) atual.startedAt else quadro.ts,
            )

            // O turno fechou: o agente nao esta mais trabalhando NESTA sessao. Sem
            // isto a tela ficaria girando para sempre quando o passo morresse sem
            // fechar turno — que e justamente o caso da sessao travada.
            TurnKind.TurnEnd -> atual.copy(running = false, startedAt = 0L, steps = 0)

            TurnKind.TextDone -> atual.copy(
                tokensIn = atual.tokensIn + (payload.usage?.input ?: 0).toLong(),
                tokensOut = atual.tokensOut + (payload.usage?.output ?: 0).toLong(),
            )

            TurnKind.Inbox -> atual.copy(queued = payload.queued ?: atual.queued)

            // O retrato do rodape vem do PC, que le as projecoes do Harness. Cada
            // campo so entra quando veio: o PC pode saber o contexto e nao o
            // preco (perfil sem o plugin de custo), e um nulo nao pode apagar o
            // que ja estava na tela.
            TurnKind.Stats -> atual.copy(
                custoUsd = payload.usd ?: atual.custoUsd,
                custoUsdPico = payload.usdPico ?: atual.custoUsdPico,
                entrada = payload.entrada ?: atual.entrada,
                saida = payload.saida ?: atual.saida,
                cache = payload.cache ?: atual.cache,
                contextoUsado = payload.contextoUsado ?: atual.contextoUsado,
                contextoJanela = payload.contextoJanela ?: atual.contextoJanela,
                modelo = payload.modelo ?: atual.modelo,
            )

            else -> atual
        }
    }

    /**
     * Marca a sessao como parada quando o PC diz que ela nao esta mais viva.
     *
     * @param atual estado da sessao.
     * @param viva se o PC considera a sessao viva agora.
     * @return estado ajustado.
     */
    fun sessao(atual: TurnStatus, viva: Boolean): TurnStatus =
        if (!viva) atual.copy(running = false, startedAt = 0L) else atual
}
