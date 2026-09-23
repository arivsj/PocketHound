package com.pockethound.app.ui.questions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pockethound.app.core.model.QuestionRequestPayload
import com.pockethound.app.core.model.RespostaDaPergunta
import com.pockethound.app.ui.theme.PhOk
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhCard
import com.pockethound.app.ui.common.PhTextField
import com.pockethound.app.ui.common.PH_SEM_PRAZO_MS
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhWarn

/**
 * Cartão de pergunta do agente — dentro do chat, como o de aprovação.
 *
 * A ferramenta de pergunta do Harness chama o provedor da UI web direto, sem
 * gancho nenhum. O plugin passou a ENVOLVER esse provedor e a perguntar aqui
 * também, e quem responde primeiro vale. Sem este cartão, a pergunta aparecia só
 * no PC — ou, pior, virava um aviso na Torre sem lugar para responder.
 *
 * @param pedido a pergunta, com as opções que o agente ofereceu.
 * @param nowMs relógio da tela, para a contagem do prazo.
 * @param onResponder entrega a resposta escolhida.
 */
@Composable
fun CartaoDePergunta(
    pedido: QuestionRequestPayload,
    resposta: RespostaDaPergunta?,
    nowMs: Long,
    onResponder: (questionId: String, selecionadas: List<String>, textoLivre: String?) -> Unit,
) {
    val restante = (pedido.expiresAt - nowMs).coerceAtLeast(0L)
    val primeira = pedido.questions.firstOrNull() ?: return
    val respondida = resposta != null

    PhCard(
        modifier = Modifier.fillMaxWidth(),
        accent = PhWarn,
        glowing = restante in 1..20_000L,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhBadge(
                text = if (respondida) "respondida" else "pergunta",
                tone = if (respondida) PhTone.Ok else PhTone.Warn,
                glyph = true,
            )
            if (primeira.header != null) PhBadge(text = primeira.header, tone = PhTone.Neutral)
            Text(
                text = when {
                    resposta?.por == "desktop" -> "no PC"
                    respondida -> "aqui"
                    restante in 1..PH_SEM_PRAZO_MS -> (restante / 1000L).toString() + "s"
                    else -> "sem prazo"
                },
                color = PhTextDim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }

        Text(
            text = primeira.question,
            color = PhText,
            style = MaterialTheme.typography.bodyMedium,
        )

        // Respondida: só a ida e volta do próprio celular aparece aqui — o toque
        // trava o cartão na hora e o question.resolved (de qualquer tela) é quem
        // tira ele da fila. Sumir no toque, antes do resolved, mostraria a
        // pergunta de volta se a resposta não chegou ao PC.
        if (resposta != null) {
            val escolhido = resposta.selecionadas.firstOrNull()
            val mostrado = resposta.textoLivre?.takeIf { it.isNotBlank() } ?: escolhido
            Text(
                text = "resposta: " + (mostrado ?: "(sem texto)"),
                color = PhOk,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@PhCard
        }

        // Um toque numa opção já responde: no PC a pergunta também é assim, e
        // pedir "marque e confirme" aqui só criaria passos a mais no celular.
        primeira.options.forEach { opcao ->
            PhButton(
                text = opcao.label,
                onClick = { onResponder(primeira.id, listOf(opcao.label), null) },
                variant = PhButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            if (opcao.description != null) {
                Text(
                    text = opcao.description,
                    color = PhTextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // A opção de escrever: nem toda resposta cabe numa lista.
        var texto by remember(pedido.requestId) { mutableStateOf("") }
        PhTextField(
            value = texto,
            onValueChange = { texto = it },
            label = "ou escreva",
            placeholder = "Resposta em palavras suas…",
            singleLine = false,
            minLines = 1,
            maxLines = 3,
        )
        PhButton(
            text = "responder",
            onClick = { onResponder(primeira.id, emptyList(), texto.trim()) },
            enabled = texto.isNotBlank(),
        )
    }
}
