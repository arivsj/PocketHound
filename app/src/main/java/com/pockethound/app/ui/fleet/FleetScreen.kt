package com.pockethound.app.ui.fleet

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockethound.app.core.model.LinkStatus
import com.pockethound.app.core.model.Session
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.common.PhBar
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhCard
import com.pockethound.app.ui.common.PhKeyValue
import com.pockethound.app.ui.common.PhSectionTitle
import com.pockethound.app.ui.common.PhScreenScaffold
import com.pockethound.app.ui.common.PhStatusDot
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.common.phUsageColor
import com.pockethound.app.ui.nav.RootViewModel
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhViolet

@Composable
fun FleetRoute(viewModel: RootViewModel = hiltViewModel()) {
    FleetScreen(viewModel = viewModel)
}

/**
 * Aba Frota: sessões vivas, estado do PC e transporte ativo.
 *
 * TODO(pockethound): desk.state, session.upsert e session.gone ainda não chegam —
 * os números são de exemplo e o botão "testar" só refaz a sonda local.
 */
@Composable
fun FleetScreen(viewModel: RootViewModel) {
    val link by viewModel.link.collectAsStateWithLifecycle()
    val desk by viewModel.deskState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val notices by viewModel.notices.collectAsStateWithLifecycle()

    PhScreenScaffold(
        title = "Frota",
        subtitle = pairing.pcName ?: "PC não pareado",
        topBarActions = {
            PhBadge(
                text = link.path,
                tone = if (link.status == LinkStatus.Online) PhTone.Ok else PhTone.Danger,
                glyph = true,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PhCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhStatusDot(
                            online = link.status == LinkStatus.Online,
                            busy = link.status == LinkStatus.Connecting,
                            label = when (link.status) {
                                LinkStatus.Online -> "ponte viva"
                                LinkStatus.Connecting -> "conectando"
                                LinkStatus.Offline -> "sem ponte"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        PhButton(
                            text = "testar",
                            onClick = { },
                            variant = PhButtonVariant.Ghost,
                        )
                    }
                    PhKeyValue(label = "transporte", value = pairing.transportMode.name)
                    PhKeyValue(label = "endereço", value = pairing.directBaseUrl.ifBlank { "não definido" })
                    PhKeyValue(label = "latência", value = link.latencyMs?.let { it.toString() + " ms" } ?: "—")
                    PhKeyValue(
                        label = "cursor",
                        value = pairing.lastSeq.toString(),
                        divider = false,
                    )
                }
            }

            item {
                PhCard(modifier = Modifier.fillMaxWidth()) {
                    PhSectionTitle(text = "estado do PC")
                    UsageRow("cpu", desk.cpu)
                    UsageRow("memória", desk.mem)
                    UsageRow("gpu", desk.gpu)
                    UsageRow("temperatura", desk.temp, suffix = "°C")
                    PhKeyValue(
                        label = "uptime",
                        value = desk.uptime?.let { formatUptime(it) } ?: "—",
                        divider = false,
                    )
                }
            }

            item {
                PhSectionTitle(
                    text = "sessões",
                    trailing = { PhBadge(text = sessions.size.toString(), tone = PhTone.Violet) },
                )
            }

            items(items = sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    active = session.id == activeId,
                    onClick = { viewModel.selectSession(session.id) },
                )
            }

            if (notices.isNotEmpty()) {
                item { PhSectionTitle(text = "avisos") }
                items(items = notices) { notice ->
                    PhCard(modifier = Modifier.fillMaxWidth()) {
                        PhBadge(
                            text = notice.level,
                            tone = when (notice.level) {
                                "success" -> PhTone.Ok
                                "warn" -> PhTone.Warn
                                "error" -> PhTone.Danger
                                else -> PhTone.Info
                            },
                            glyph = true,
                        )
                        Text(
                            text = notice.title,
                            color = PhText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (notice.body.isNotBlank()) {
                            Text(
                                text = notice.body,
                                color = PhTextDim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: Double?, suffix: String = "%") {
    val color = phUsageColor(value)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label.uppercase(),
                color = PhTextDim,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value?.let { it.toInt().toString() + suffix } ?: "—",
                color = color,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PhBar(progress = ((value ?: 0.0) / 100.0).toFloat(), color = color)
    }
}

@Composable
private fun SessionRow(session: Session, active: Boolean, onClick: () -> Unit) {
    PhCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        accent = if (active) PhViolet else com.pockethound.app.ui.theme.PhBorder,
        glowing = active,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.title.ifBlank { session.id },
                color = PhText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            PhBadge(
                text = session.status.wire,
                tone = when (session.status.wire) {
                    "running" -> PhTone.Ok
                    "waiting" -> PhTone.Warn
                    "error" -> PhTone.Danger
                    else -> PhTone.Neutral
                },
                glyph = true,
            )
        }
        Text(
            text = session.workspace.ifBlank { "sem workspace" },
            color = PhTextDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = session.id,
            color = PhTextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return hours.toString() + "h " + minutes.toString() + "min"
}
