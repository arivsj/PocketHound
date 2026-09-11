package com.pockethound.app.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pockethound.app.core.model.PairingPayload
import com.pockethound.app.ui.common.PhBadge
import com.pockethound.app.ui.common.PhButton
import com.pockethound.app.ui.common.PhButtonVariant
import com.pockethound.app.ui.common.PhCard
import com.pockethound.app.ui.common.PhKeyValue
import com.pockethound.app.ui.common.PhScreenScaffold
import com.pockethound.app.ui.common.PhSecureScreen
import com.pockethound.app.ui.common.PhSectionTitle
import com.pockethound.app.ui.common.PhState
import com.pockethound.app.ui.common.PhStateKind
import com.pockethound.app.ui.common.PhTextField
import com.pockethound.app.ui.common.PhTone
import com.pockethound.app.ui.matrix.PhRainBackground
import com.pockethound.app.ui.nav.RootViewModel
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhVoid

/**
 * Payload de exemplo, para exercitar a tela sem um PC por perto.
 *
 * Valores SINTETICOS: um ticket de iroh de verdade e uma credencial de acesso ao
 * no, e nao pode morar num repositorio.
 */
private const val PAYLOAD_EXEMPLO =
    "pockethound://pair?v=1&n=pop-os" +
        "&t=endpointEXEMPLOnaoEUmTicketDeVerdade0000000000000000000000000000" +
        "&k=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
        "&a=http%3A%2F%2F192.168.0.10%3A7411&c=123456" +
        "&x=9999999999999"

@Composable
fun PairingRoute(
    onPaired: () -> Unit,
    viewModel: RootViewModel = hiltViewModel(),
) {
    PairingScreen(onPaired = onPaired, viewModel = viewModel)
}

/**
 * Tela de pareamento: lê o QR do PocketHound desk e guarda o vínculo.
 *
 * A tela inteira é FLAG_SECURE: o payload carrega o token do PC e não pode aparecer
 * em print nem no seletor de apps recentes (ARQUITETURA.md §8).
 *
 * TODO(pockethound): trocar o campo de colar pelo leitor de QR com CameraX + ZXing
 * (as dependências já estão no build.gradle.kts) e fazer o handshake `hello`/`hello.ack`
 * para receber o token e gravá-lo no SecureStore.
 */
@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    viewModel: RootViewModel,
) {
    PhSecureScreen(enabled = true)

    var raw by remember { mutableStateOf("") }
    val parsed = remember(raw) { PairingPayload.parse(raw) }
    val invalid = raw.isNotBlank() && parsed == null

    // Endereço e código são o caminho que funciona hoje, sem QR: o usuário lê os
    // dois na tela do PC. Quando o QR chegar, ele preenche os dois sozinho.
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val estado by viewModel.pairingState.collectAsState()

    // O QR preenche os campos quando traz os dois.
    LaunchedEffect(parsed) {
        parsed?.address?.let { if (address.isBlank()) address = it }
        parsed?.code?.let { if (code.isBlank()) code = it }
    }

    LaunchedEffect(estado.done) {
        if (estado.done) onPaired()
    }

    Box(modifier = Modifier.fillMaxSize().background(PhVoid)) {
        PhRainBackground(opacity = 0.35f)

        PhScreenScaffold(title = "Parear", subtitle = "PocketHound desk") { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    PhCard(modifier = Modifier.fillMaxWidth(), glowing = true) {
                        PhSectionTitle(
                            text = "PocketHound",
                            trailing = { PhBadge(text = "v1", tone = PhTone.Violet, glyph = true) },
                        )
                        Text(
                            text = "No PC, abra o PocketHound desk e gere o QR de pareamento. " +
                                "O código vale 120 s e carrega o token do PC.",
                            color = PhText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                item {
                    PhCard(modifier = Modifier.fillMaxWidth(), glowing = true) {
                        PhSectionTitle(text = "dados do PC")
                        PhTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = "endereço",
                            placeholder = "http://192.168.0.10:7411",
                        )
                        PhTextField(
                            value = code,
                            onValueChange = { entrada ->
                                // Só dígitos e no máximo 6: o campo não deve
                                // aceitar o que o PC jamais aceitaria.
                                code = entrada.filter { it.isDigit() }.take(6)
                            },
                            label = "código de 6 dígitos",
                            placeholder = "000000",
                        )
                    }
                }

                if (estado.error != null) {
                    item {
                        PhState(message = estado.error!!, kind = PhStateKind.Error)
                    }
                }

                item {
                    PhCard(modifier = Modifier.fillMaxWidth()) {
                        PhSectionTitle(text = "código do QR")
                        PhTextField(
                            value = raw,
                            onValueChange = { raw = it },
                            label = "payload",
                            placeholder = "pockethound://pair?v=1&n=…",
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                        )
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PhButton(
                                text = if (estado.working) "pareando…" else "parear",
                                onClick = {
                                    // O código é o que autoriza. O ticket diz por
                                    // onde ir — e com ele o pareamento funciona
                                    // mesmo com o PC noutra rede.
                                    val payload = parsed
                                    viewModel.pair(
                                        address = address.ifBlank { payload?.address.orEmpty() },
                                        code = code.ifBlank { payload?.code.orEmpty() },
                                        ticket = payload?.ticket.orEmpty(),
                                    )
                                },
                                enabled = !estado.working &&
                                    code.trim().length == 6 &&
                                    (parsed?.ticket?.isNotBlank() == true || address.isNotBlank()),
                            )
                            PhButton(
                                text = "usar exemplo",
                                onClick = { raw = PAYLOAD_EXEMPLO },
                                variant = PhButtonVariant.Ghost,
                            )
                        }
                    }
                }

                if (invalid) {
                    item {
                        PhState(
                            message = "Esquema inválido. Esperado pockethound://pair?v=1&n=…&t=…&k=…&x=…",
                            kind = PhStateKind.Error,
                        )
                    }
                }

                if (parsed != null) {
                    item {
                        PhCard(modifier = Modifier.fillMaxWidth()) {
                            PhSectionTitle(
                                text = "payload lido",
                                trailing = {
                                    PhBadge(
                                        text = if (parsed.isExpired()) "expirado" else "válido",
                                        tone = if (parsed.isExpired()) PhTone.Danger else PhTone.Ok,
                                        glyph = true,
                                    )
                                },
                            )
                            PhKeyValue(label = "versão", value = parsed.version.toString())
                            PhKeyValue(label = "pc", value = parsed.pcName ?: "—")
                            PhKeyValue(label = "chave", value = parsed.key.take(24) + "…")
                            PhKeyValue(label = "ticket", value = parsed.ticket.take(24) + "…")
                            PhKeyValue(
                                label = "expira em",
                                value = ((parsed.expiresAt - System.currentTimeMillis()) / 1000L)
                                    .coerceAtLeast(0L)
                                    .toString() + " s",
                                divider = false,
                            )
                        }
                    }
                }

                item {
                    PhCard(modifier = Modifier.fillMaxWidth()) {
                        PhSectionTitle(text = "ler QR com a câmera")
                        Text(
                            text = "TODO(pockethound): leitor CameraX + ZXing. As dependências já " +
                                "estão declaradas; falta a permissão de câmera e o analisador de imagem.",
                            color = PhTextDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(PhVoid),
                        ) {
                            PhRainBackground(opacity = 0.6f)
                        }
                        PhButton(
                            text = "abrir câmera",
                            onClick = { },
                            variant = PhButtonVariant.Ghost,
                            enabled = false,
                        )
                    }
                }
            }
        }
    }
}
