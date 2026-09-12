package com.pockethound.app.ui.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockethound.app.core.model.Session
import com.pockethound.app.core.model.SessionStatus
import com.pockethound.app.core.model.Workspace
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhCard
import com.pockethound.app.ui.common.PhScreenScaffold
import com.pockethound.app.ui.common.PhState
import com.pockethound.app.ui.common.PhStateKind
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.nav.RootViewModel
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim

@Composable
fun WorkspacesRoute(
    viewModel: RootViewModel = hiltViewModel(),
    onIrParaChat: () -> Unit = {},
) {
    WorkspacesScreen(viewModel = viewModel, onIrParaChat = onIrParaChat)
}

/**
 * Aba Sessões: ONDE trabalhar.
 *
 * A ideia do app fora de casa é esta tela. O workspace é o lugar (uma pasta do
 * PC) e a sessão é a conversa que roda nele. Como sessão não muda de pasta,
 * "trocar de workspace" é abrir sessão nova dentro do outro — por isso cada
 * workspace tem o seu botão de abrir sessão, e as sessões que já vivem nele
 * aparecem embaixo, prontas para receber o próximo prompt.
 *
 * @param viewModel estado compartilhado pelas abas.
 */
@Composable
fun WorkspacesScreen(viewModel: RootViewModel, onIrParaChat: () -> Unit = {}) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val recado by viewModel.criandoSessao.collectAsStateWithLifecycle()
    val recadoWorkspaces by viewModel.recadoWorkspaces.collectAsStateWithLifecycle()

    PhScreenScaffold(
        title = "Sessões",
        subtitle = when {
            workspaces.isNotEmpty() -> workspaces.size.toString() + " workspace(s)"
            recadoWorkspaces != null -> recadoWorkspaces
            else -> "carregando…"
        },
        topBarActions = {
            PhButton(
                text = "atualizar",
                onClick = { viewModel.pedirWorkspaces() },
                variant = PhButtonVariant.Ghost,
            )
        },
    ) { innerPadding ->
        if (workspaces.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                PhState(
                    message = if (recadoWorkspaces != null) {
                        // O recado vem primeiro: "a lista não chegou" pode ser o
                        // pedido que não saiu ou a resposta que não voltou, e as
                        // duas ficam idênticas sem esta linha.
                        recadoWorkspaces.orEmpty()
                    } else {
                        "Nenhum workspace ainda. Toque em ATUALIZAR — a lista vem do Harness, " +
                            "e é a mesma que aparece na barra lateral do PC."
                    },
                    kind = PhStateKind.Empty,
                    actionLabel = "buscar workspaces",
                    onAction = { viewModel.pedirWorkspaces() },
                )
            }
            return@PhScreenScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (recado != null) {
                item(key = "recado") {
                    PhCard(modifier = Modifier.fillMaxWidth(), accent = com.pockethound.app.ui.theme.PhDanger) {
                        Text(
                            text = "Não consegui abrir a sessão",
                            color = PhText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(text = recado.orEmpty(), color = PhTextDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            items(items = workspaces, key = { it.id }) { workspace ->
                CartaoDeWorkspace(
                    workspace = workspace,
                    // TODAS as sessões do workspace, e não só as que o app já
                    // recebeu por session.upsert: as antigas (frias) são justamente
                    // as que se quer retomar de fora de casa, e o PC sabe resumir
                    // uma sessão fria quando o prompt chega. Filtrá-las deixava a
                    // lista com uma sessão só — a que já estava aberta, cujo botão
                    // fica desabilitado. Tocar nele não fazia nada, e parecia que a
                    // troca de sessão estava quebrada.
                    sessions = workspace.sessions.map { id ->
                        val conhecida = sessions.firstOrNull { it.id == id }
                        LinhaDeSessao(
                            id = id,
                            titulo = conhecida?.title?.ifBlank { id } ?: id,
                            viva = conhecida?.status == SessionStatus.Running,
                            conhecida = conhecida != null,
                        )
                    },
                    activeId = activeId,
                    onAbrirSessao = { viewModel.criarSessao(workspace.id) },
                    onEscolherSessao = { id ->
                        viewModel.selectSession(id)
                        // Escolher sessao E querer conversar nela: volta para o chat
                        // ja apontando para a sessao nova.
                        onIrParaChat()
                    },
                )
            }
        }
    }
}

/**
 * Uma sessão como a lista de workspaces precisa dela.
 *
 * Conhecida = false é uma conversa que o app ainda não viu nesta conexão: ela
 * existe no disco do PC, e o Harness a resume quando o primeiro prompt chega.
 *
 * @param id identificador da sessão.
 * @param titulo o que mostrar na linha.
 * @param viva se o agente está trabalhando agora.
 * @param conhecida se o app já recebeu o retrato dela.
 */
data class LinhaDeSessao(
    val id: String,
    val titulo: String,
    val viva: Boolean,
    val conhecida: Boolean,
)

@Composable
private fun CartaoDeWorkspace(
    workspace: Workspace,
    sessions: List<LinhaDeSessao>,
    activeId: String?,
    onAbrirSessao: () -> Unit,
    onEscolherSessao: (String) -> Unit,
) {
    PhCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhBadge(text = workspace.pasta, tone = PhTone.Violet, glyph = true)
            Text(
                text = workspace.title.ifBlank { workspace.path },
                color = PhText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = workspace.path,
            color = PhTextDim,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (sessions.isEmpty()) {
            Text(
                text = "Nenhuma sessão neste workspace ainda.",
                color = PhTextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            sessions.forEach { sessao ->
                val ativa = sessao.id == activeId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PhBadge(
                        text = when {
                            sessao.viva -> "trabalhando"
                            sessao.conhecida -> "parada"
                            else -> "fria"
                        },
                        tone = when {
                            sessao.viva -> PhTone.Ok
                            sessao.conhecida -> PhTone.Neutral
                            else -> PhTone.Warn
                        },
                        glyph = ativa,
                    )
                    Text(
                        text = sessao.titulo,
                        color = if (ativa) PhText else PhTextDim,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    PhButton(
                        text = if (ativa) "aberta" else "usar",
                        onClick = { onEscolherSessao(sessao.id) },
                        variant = PhButtonVariant.Ghost,
                        enabled = !ativa,
                    )
                }
            }
        }

        PhButton(text = "abrir sessão aqui", onClick = onAbrirSessao)
    }
}
