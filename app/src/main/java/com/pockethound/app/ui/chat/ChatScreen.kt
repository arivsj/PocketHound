package com.pockethound.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockethound.app.core.model.Session
import com.pockethound.app.core.model.TurnItem
import com.pockethound.app.core.model.TurnItemKind
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhScreenScaffold
import com.pockethound.app.ui.common.PhState
import com.pockethound.app.ui.common.PhStateKind
import com.pockethound.app.ui.common.PhTextField
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.nav.RootViewModel
import com.pockethound.app.ui.theme.PhAmber
import com.pockethound.app.ui.theme.PhDanger
import com.pockethound.app.ui.theme.PhInfo
import com.pockethound.app.ui.theme.PhOk
import com.pockethound.app.ui.theme.PhSurface
import com.pockethound.app.ui.theme.PhSurface2
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhViolet
import com.pockethound.app.ui.theme.PhVioletSoft

@Composable
fun ChatRoute(viewModel: RootViewModel = hiltViewModel()) {
    ChatScreen(viewModel = viewModel)
}

/**
 * Aba Chat: transcrição ao vivo, envio de prompt, cancelar turno, trocar de sessão.
 *
 * TODO(pockethound): a transcrição vem do HoundRepository (dados de exemplo) e os
 * envios não saem do aparelho. Falta ligar ao transporte: prompt.send,
 * session.cancel, session.select e o consumo dos quadros turn.event.
 */
@Composable
fun ChatScreen(viewModel: RootViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val active: Session? = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
    val items = transcript[active?.id].orEmpty()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    PhScreenScaffold(
        title = "Chat",
        subtitle = active?.title ?: "nenhuma sessão",
        topBarActions = {
            PhBadge(
                text = if (link.isOnline) "ao vivo" else "offline",
                tone = if (link.isOnline) PhTone.Ok else PhTone.Danger,
                glyph = true,
                modifier = Modifier.padding(end = 8.dp),
            )
            PhButton(
                text = "parar",
                onClick = { viewModel.cancelTurn() },
                variant = PhButtonVariant.Danger,
                enabled = items.isNotEmpty(),
                icon = Icons.Filled.Close,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            SessionPickerRow(
                sessions = sessions,
                activeId = active?.id,
                onSelect = viewModel::selectSession,
            )

            if (items.isEmpty()) {
                PhState(
                    message = "Sem transcrição ainda. Escreva o primeiro pedido abaixo.",
                    kind = PhStateKind.Empty,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = items, key = { it.id }) { item ->
                        PhMessageBubble(item = item)
                    }
                }
            }

            Composer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    viewModel.sendPrompt(draft)
                    draft = ""
                },
            )
        }
    }
}

@Composable
private fun SessionPickerRow(
    sessions: List<Session>,
    activeId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sessions.forEach { session ->
            val selected = session.id == activeId
            PhBadge(
                text = session.title.ifBlank { session.id },
                tone = when {
                    selected -> PhTone.Violet
                    session.status.wire == "waiting" -> PhTone.Warn
                    else -> PhTone.Neutral
                },
                glyph = selected,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(session.id) },
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PhSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhTextField(
            value = value,
            onValueChange = onValueChange,
            label = "prompt",
            placeholder = "O que o Harness deve fazer?",
            singleLine = false,
            minLines = 1,
            maxLines = 5,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhButton(
                text = "enviar",
                onClick = onSend,
                enabled = value.isNotBlank(),
                icon = Icons.Filled.Send,
            )
            // TODO(pockethound): alternar followup/steer (PromptMode) no envio.
            PhButton(
                text = "steer",
                onClick = onSend,
                variant = PhButtonVariant.Ghost,
                enabled = false,
            )
        }
    }
}

/** Balão de conversa (componente só do celular, DESIGN.md §6.3). */
@Composable
private fun PhMessageBubble(item: TurnItem) {
    val isUser = item.kind == TurnItemKind.UserMessage
    val accent = when (item.kind) {
        TurnItemKind.UserMessage -> PhViolet
        TurnItemKind.AssistantMessage -> PhVioletSoft
        TurnItemKind.Reasoning -> PhInfo
        TurnItemKind.ToolCall -> PhAmber
        TurnItemKind.ToolResult -> if (item.ok == false) PhDanger else PhOk
        TurnItemKind.Notice -> PhTextDim
        TurnItemKind.Error -> PhDanger
    }
    val label = when (item.kind) {
        TurnItemKind.UserMessage -> "você"
        TurnItemKind.AssistantMessage -> "agente"
        TurnItemKind.Reasoning -> "raciocínio"
        TurnItemKind.ToolCall -> "ferramenta · " + (item.toolName ?: "")
        TurnItemKind.ToolResult -> "resultado · " + (item.toolName ?: "")
        TurnItemKind.Notice -> "aviso"
        TurnItemKind.Error -> "erro"
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isUser) PhViolet.copy(alpha = 0.10f) else PhSurface2)
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = label.uppercase(),
                    color = accent,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = item.text.ifBlank { "(vazio)" },
                    color = PhText,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
