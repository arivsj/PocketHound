package com.pockethound.app.core.session

import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind

/**
 * Liga cada resposta do agente ao prompt que a pediu.
 *
 * ## O que existe (e o que nao existe) no fluxo
 *
 * O PC nao manda "esta resposta e do prompt X". O que ele manda e a linha do
 * tempo: a mensagem do usuario entra, a resposta vem depois. Entao a ligacao so
 * pode sair da ORDEM — e, num chat, ordem basta quase sempre:
 *
 *     voce: faz o build        <- o prompt
 *     agente: vou compilar     <- a resposta dele
 *
 * ## Quando a ordem nao basta
 *
 * Quando voce manda DOIS prompts antes de o primeiro ser respondido (a fila do
 * proximo turno), a tela mostra:
 *
 *     voce: faz o build
 *     voce: e roda os testes   <- entrou na fila, apareceu antes da resposta
 *     agente: vou compilar     <- resposta do PRIMEIRO
 *
 * Aqui a ordem mente: "e roda os testes" esta logo acima da resposta e nao foi
 * ele que a pediu. Nesse caso a funcao **nao rotula nada** — silencio e melhor
 * que apontar o prompt errado, porque o rotulo existe justamente para dar
 * certeza de quem pediu o que.
 *
 * Funcao pura: a regra pode ser testada sem tela, sem rede e sem Android.
 */
object PromptDaResposta {

    /**
     * Quais respostas ganham rotulo, e com que texto.
     *
     * @param itens transcricao da sessao, em ordem.
     * @return id do item -> texto do prompt que o pediu (so para os itens que
     *   devem mostrar o rotulo).
     */
    fun casar(itens: List<TurnItem>): Map<String, String> {
        val rotulos = mutableMapOf<String, String>()
        // Prompts vistos desde a ultima resposta: um so e atribuivel; dois ou
        // mais sao ambiguos.
        val pendentes = mutableListOf<String>()
        var turnoRotulado: Int? = null

        for (item in itens) {
            when (item.kind) {
                TurnItemKind.UserMessage -> {
                    pendentes += item.text
                    turnoRotulado = null
                }

                TurnItemKind.AssistantMessage -> {
                    // Um rotulo por turno: repetir em cada passo poluiria a tela.
                    if (item.turn != null && item.turn == turnoRotulado) continue
                    // Um turno CONSOME um prompt — o mais antigo da espera. E o que
                    // faz a fila andar: dois prompts na frente significam que este
                    // turno responde o primeiro, e o proximo responde o segundo.
                    val eraUnico = pendentes.size == 1
                    val prompt = if (pendentes.isEmpty()) null else pendentes.removeAt(0)
                    turnoRotulado = item.turn
                    // So rotula quando o dono e inequivoco.
                    if (eraUnico && prompt != null) rotulos[item.id] = prompt
                }

                else -> Unit
            }
        }
        return rotulos
    }
}
