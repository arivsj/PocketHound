package com.pockethound.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockethound.app.core.transport.TransportMode
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhCard
import com.pockethound.app.ui.common.PhKeyValue
import com.pockethound.app.ui.common.PhScreenScaffold
import com.pockethound.app.ui.common.PhSectionTitle
import com.pockethound.app.ui.common.PhTextField
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.matrix.PhRainBackground
import com.pockethound.app.ui.nav.RootViewModel
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhViolet

@Composable
fun SettingsRoute(
    onUnpaired: () -> Unit,
    viewModel: RootViewModel = hiltViewModel(),
) {
    SettingsScreen(onUnpaired = onUnpaired, viewModel = viewModel)
}

/**
 * Aba Ajustes: pareamento, transporte, prazo de aprovação e limpeza de dados.
 *
 * TODO(pockethound): salvar de verdade (SettingsStorage) e o token no SecureStore —
 * hoje só o modo de transporte é persistido, pelo HoundRepository.
 */
@Composable
fun SettingsScreen(
    onUnpaired: () -> Unit,
    viewModel: RootViewModel,
) {
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    var directUrl by remember { mutableStateOf(pairing.directBaseUrl) }
    var deviceName by remember { mutableStateOf(pairing.deviceName) }
    var timeout by remember { mutableStateOf(pairing.pendingApprovalTimeoutSeconds) }

    // A preferência chega de forma assíncrona: quando ela carrega, os campos vazios
    // passam a refletir o valor salvo (sem sobrescrever o que o usuário já digitou).
    LaunchedEffect(pairing.deviceId) {
        if (directUrl.isBlank()) directUrl = pairing.directBaseUrl
        if (deviceName.isBlank()) deviceName = pairing.deviceName
        timeout = pairing.pendingApprovalTimeoutSeconds
    }

    PhScreenScaffold(title = "Ajustes", subtitle = pairing.pcName ?: "PC não pareado") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PhCard(modifier = Modifier.fillMaxWidth()) {
                    PhSectionTitle(
                        text = "pareamento",
                        trailing = {
                            PhBadge(
                                text = if (pairing.isPaired) "pareado" else "solto",
                                tone = if (pairing.isPaired) PhTone.Ok else PhTone.Danger,
                                glyph = true,
                            )
                        },
                    )
                    PhKeyValue(label = "pc", value = pairing.pcName ?: "—")
                    PhKeyValue(label = "device id", value = pairing.deviceId ?: "—")
                    PhKeyValue(
                        label = "token",
                        value = if (pairing.isPaired) "guardado no SecureStore (Tink)" else "—",
                        divider = false,
                    )
                    PhTextField(
                        value = directUrl,
                        onValueChange = { directUrl = it },
                        label = "endereço direto",
                        // O exemplo usa a porta padrão do servidor do celular
                        // (ver DEFAULT_CONFIG.port no PocketHound desk). Só o QR
                        // é autoritativo: este campo existe para o caso de o PC
                        // estar em rede fixa e você não querer parear de novo.
                        placeholder = "http://192.168.0.10:7411",
                    )
                    PhButton(
                        text = "salvar endereço",
                        onClick = { },
                        enabled = directUrl.isNotBlank(),
                    )
                }
            }

            item {
                PhCard(modifier = Modifier.fillMaxWidth()) {
                    PhSectionTitle(text = "transporte")
                    Text(
                        text = "AUTO sonda o caminho direto com 1,5 s de limite e cai para P2P.",
                        color = PhTextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransportMode.entries.forEach { mode ->
                            PhButton(
                                text = mode.name.lowercase().replace('_', ' '),
                                onClick = { viewModel.setTransportMode(mode) },
                                variant = if (mode == pairing.transportMode) {
                                    PhButtonVariant.Primary
                                } else {
                                    PhButtonVariant.Ghost
                                },
                            )
                        }
                    }
                }
            }

            item {
                PhCard(modifier = Modifier.fillMaxWidth()) {
                    PhSectionTitle(text = "aprovações")
                    Text(
                        text = "Prazo que o plugin espera antes de devolver a decisão ao terminal.",
                        color = PhTextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = timeout.toString() + " s",
                            color = PhViolet,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        PhButton(
                            text = "−15",
                            onClick = { timeout = (timeout - 15).coerceAtLeast(15) },
                            variant = PhButtonVariant.Ghost,
                        )
                        PhButton(
                            text = "+15",
                            onClick = { timeout = (timeout + 15).coerceAtMost(600) },
                            variant = PhButtonVariant.Ghost,
                        )
                    }
                }
            }

            item {
                PhCard(modifier = Modifier.fillMaxWidth()) {
                    PhSectionTitle(text = "dispositivo")
                    PhTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = "nome do celular",
                        placeholder = "PocketHound",
                    )
                    PhButton(
                        text = "salvar nome",
                        onClick = { },
                        enabled = deviceName.isNotBlank(),
                    )
                }
            }

            item {
                PhCard(modifier = Modifier.fillMaxWidth()) {
                    PhSectionTitle(text = "identidade visual")
                    Text(
                        text = "A chuva violeta do PocketHound, na cor do estado do PC.",
                        color = PhTextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(com.pockethound.app.ui.theme.PhVoid),
                    ) {
                        PhRainBackground(opacity = 0.85f)
                    }
                }
            }

            item {
                PhCard(modifier = Modifier.fillMaxWidth(), accent = com.pockethound.app.ui.theme.PhDanger) {
                    PhSectionTitle(text = "dados")
                    Text(
                        text = "Desparear apaga o vínculo e o token; limpar tudo apaga também as preferências.",
                        color = PhTextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PhButton(
                            text = "desparear",
                            onClick = {
                                viewModel.unpair()
                                onUnpaired()
                            },
                            variant = PhButtonVariant.Danger,
                            enabled = pairing.isPaired,
                        )
                        PhButton(
                            text = "limpar tudo",
                            onClick = { viewModel.unpair() },
                            variant = PhButtonVariant.Danger,
                        )
                    }
                }
            }
        }
    }
}
