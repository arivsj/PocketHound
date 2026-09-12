package com.pockethound.app.ui.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockethound.app.core.model.ApprovalRequest
import com.pockethound.app.core.model.DecisaoDeAprovacao
import com.pockethound.app.core.model.EstadoDaDecisao
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.common.PhBar
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhCard
import com.pockethound.app.ui.common.PhScreenScaffold
import com.pockethound.app.ui.common.PhState
import com.pockethound.app.ui.common.PhStateKind
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.nav.RootViewModel
import com.pockethound.app.ui.theme.PhAmber
import com.pockethound.app.ui.theme.PhDanger
import com.pockethound.app.ui.theme.PhOk
import com.pockethound.app.ui.theme.PhSurface2
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhViolet
import kotlinx.coroutines.delay

@Composable
fun ApprovalsRoute(viewModel: RootViewModel = hiltViewModel()) {
    ApprovalsScreen(viewModel = viewModel)
}

/**
 * Aba Aprovar: a fila de aprovações, decisão com um toque e "não perguntar de novo".
 *
 * TODO(pockethound): a fila é de exemplo. Falta o quadro approval.request alimentar
 * esta lista, o approval.decide sair pelo transporte e o "remember" virar uma regra
 * no desk (toolName + digest dos argumentos, por sessão).
 */
@Composable
fun ApprovalsScreen(viewModel: RootViewModel) {
    val approvals by viewModel.approvals.collectAsStateWithLifecycle()
    val decisoes by viewModel.decisoes.collectAsStateWithLifecycle()
    val rememberFlags = remember { mutableStateMapOf<String, Boolean>() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Relógio de 1 s para a contagem regressiva das expirações.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    PhScreenScaffold(
        title = "Aprovar",
        subtitle = if (approvals.isEmpty()) "fila vazia" else approvals.size.toString() + " pendente(s)",
        topBarActions = {
            if (approvals.isNotEmpty()) {
                PhBadge(text = approvals.size.toString(), tone = PhTone.Danger, glyph = true)
            }
        },
    ) { innerPadding ->
        if (approvals.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                PhState(
                    message = "Nenhuma aprovação pendente. O Harness avisa aqui quando precisar de você.",
                    kind = PhStateKind.Empty,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = approvals, key = { it.requestId }) { request ->
                    ApprovalCard(
                        request = request,
                        nowMs = now,
                        decisao = decisoes[request.requestId],
                        remember = rememberFlags[request.requestId] == true,
                        onRememberChange = { checked -> rememberFlags[request.requestId] = checked },
                        onAllow = { viewModel.decide(request.requestId, true, rememberFlags[request.requestId] == true) },
                        onReject = { viewModel.decide(request.requestId, false, false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    request: ApprovalRequest,
    nowMs: Long,
    decisao: DecisaoDeAprovacao?,
    remember: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    val total = 90_000f
    val remaining = (request.expiresAt - nowMs).coerceAtLeast(0L)
    val progress = (remaining / total).coerceIn(0f, 1f)
    val urgent = remaining < 20_000L

    PhCard(
        modifier = Modifier.fillMaxWidth(),
        accent = if (urgent) PhDanger else PhTextDim,
        glowing = urgent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhBadge(text = request.toolName, tone = if (urgent) PhTone.Danger else PhTone.Warn, glyph = true)
            if (request.callId != null) {
                PhBadge(text = request.callId, tone = PhTone.Neutral)
            }
            Box(modifier = Modifier.weight(1f))
            Text(
                text = ((remaining / 1000L)).toString() + "s",
                color = if (urgent) PhDanger else PhTextDim,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (request.reason != null) {
            Text(
                text = request.reason,
                color = PhText,
                style = MaterialTheme.typography.bodySmall,
            )
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

        PhBar(
            progress = progress,
            color = if (urgent) PhDanger else PhAmber,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        // indistinguivel de "o comando nem saiu do aparelho" — e o usuario fica
        // tocando no escuro enquanto o pedido expira do outro lado.
        if (decisao != null) {
            val (cor, texto) = when (decisao.estado) {
                EstadoDaDecisao.Enviando -> PhTextDim to "enviando ao PC…"
                EstadoDaDecisao.Entregue -> PhOk to
                    decisao.detalhe + " · o Harness decide e confirma em seguida"

                EstadoDaDecisao.Recusada -> PhDanger to decisao.detalhe
            }
            Text(
                text = texto,
                color = cor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
