package com.pockethound.app.ui.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.pockethound.app.core.model.ApprovalRequest
import com.pockethound.app.core.model.DecisaoDeAprovacao
import com.pockethound.app.core.model.EstadoDaDecisao
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.common.PhBar
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhCard
import com.pockethound.app.ui.common.PH_SEM_PRAZO_MS
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.theme.PhAmber
import com.pockethound.app.ui.theme.PhDanger
import com.pockethound.app.ui.theme.PhOk
import com.pockethound.app.ui.theme.PhSurface2
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhViolet

/**
 * Cartão de aprovação — agora dentro do chat.
 *
 * Ele nasceu numa aba própria e mudou de lugar por um motivo prático: a decisão
 * acontece no meio do trabalho, e decidir olhando a linha que pediu a permissão é
 * melhor do que decidir numa tela separada, sem o contexto na frente. A aba que
 * ele ocupava virou a escolha de workspace e sessão.
 *
 * @param request o pedido do Harness.
 * @param nowMs relógio da tela, para a contagem do prazo.
 * @param decisao o que houve com o toque, quando já houve.
 * @param remember estado do "não perguntar de novo".
 * @param onRememberChange alterna o "não perguntar de novo".
 * @param onAllow aprova uma vez.
 * @param onReject rejeita.
 */
@Composable
fun CartaoDeAprovacao(
    request: ApprovalRequest,
    nowMs: Long,
    decisao: DecisaoDeAprovacao?,
    remember: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    // Sem prazo, não há contagem regressiva para mostrar: o cartão fica até
    // alguém responder — na tela do PC ou aqui.
    val semPrazo = request.expiresAt - nowMs > PH_SEM_PRAZO_MS
    val total = 90_000f
    val remaining = (request.expiresAt - nowMs).coerceAtLeast(0L)
    val progress = if (semPrazo) 1f else (remaining / total).coerceIn(0f, 1f)
    val urgent = !semPrazo && remaining < 20_000L

    PhCard(
        modifier = Modifier.fillMaxWidth(),
        accent = if (urgent) PhDanger else PhAmber,
        glowing = urgent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhBadge(text = "aprovar", tone = if (urgent) PhTone.Danger else PhTone.Warn, glyph = true)
            PhBadge(text = request.toolName, tone = PhTone.Neutral)
            Box(modifier = Modifier.weight(1f))
            Text(
                text = if (semPrazo) "sem prazo" else (remaining / 1000L).toString() + "s",
                color = if (urgent) PhDanger else PhTextDim,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (request.reason != null) {
            Text(text = request.reason, color = PhText, style = MaterialTheme.typography.bodySmall)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(PhSurface2, PhSurface2)))
                .border(1.dp, PhTextDim.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            Text(
                text = request.argsPreview.ifBlank { "(sem argumentos)" },
                color = PhTextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        PhBar(progress = progress, color = if (urgent) PhDanger else PhAmber)

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "não perguntar de novo",
                color = PhTextDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = remember,
                onCheckedChange = onRememberChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PhViolet,
                    checkedTrackColor = PhViolet.copy(alpha = 0.35f),
                    uncheckedThumbColor = PhTextDim,
                    uncheckedTrackColor = PhSurface2,
                    uncheckedBorderColor = PhTextDim,
                ),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhButton(text = "aprovar", onClick = onAllow)
            PhButton(text = "rejeitar", onClick = onReject, variant = PhButtonVariant.Danger)
        }

        // O que aconteceu com o toque. Sem esta linha, "toquei e nada mudou" e
        // indistinguivel de "o comando nem saiu do aparelho".
        if (decisao != null) {
            val (cor, texto) = when (decisao.estado) {
                EstadoDaDecisao.Enviando -> PhTextDim to "enviando ao PC…"
                EstadoDaDecisao.Entregue -> PhOk to
                    decisao.detalhe + " · o Harness decide e confirma em seguida"

                EstadoDaDecisao.Recusada -> PhDanger to decisao.detalhe
            }
            Text(text = texto, color = cor, style = MaterialTheme.typography.labelSmall)
        }
    }
}
