package com.pockethound.app.ui.common

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pockethound.app.ui.theme.PhAmber
import com.pockethound.app.ui.theme.PhBg
import com.pockethound.app.ui.theme.PhBorder
import com.pockethound.app.ui.theme.PhDanger
import com.pockethound.app.ui.theme.PhInfo
import com.pockethound.app.ui.theme.PhMagenta
import com.pockethound.app.ui.theme.PhOk
import com.pockethound.app.ui.theme.PhSurface
import com.pockethound.app.ui.theme.PhSurface2
import com.pockethound.app.ui.theme.PhText
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhTextMute
import com.pockethound.app.ui.theme.PhViolet
import com.pockethound.app.ui.theme.PhVioletSoft
import com.pockethound.app.ui.theme.PhWarn

// ---------------------------------------------------------------------------
// Base
// ---------------------------------------------------------------------------

private const val PRESS_SCALE_BUTTON = 0.95f
private const val PRESS_SCALE_CARD = 0.97f
private val HEADER_HEIGHT = 48.dp

/** Glow derivado da cor: a sombra colorida é o "neon" do design system. */
fun Modifier.phGlow(
    color: Color = PhViolet,
    elevation: Dp = 14.dp,
    alpha: Float = 0.30f,
): Modifier = this.shadow(
        elevation = elevation,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        ambientColor = color.copy(alpha = alpha),
        spotColor = color.copy(alpha = alpha),
    )

/** Encolhe enquanto o dedo está pressionando e devolve com mola. */
@Composable
fun Modifier.phPress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    scale: Float = PRESS_SCALE_BUTTON,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val current by animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium),
        label = "phPressScale",
    )
    return this.graphicsLayer {
        scaleX = current
        scaleY = current
    }
}

/** Cor de uso: violeta em repouso, âmbar sob carga, perigo em faixa crítica. */
fun phUsageColor(percent: Double?): Color = when {
    percent == null -> PhTextDim
    percent >= 85.0 -> PhDanger
    percent >= 60.0 -> PhAmber
    else -> PhViolet
}

// ---------------------------------------------------------------------------
// Estrutura de tela
// ---------------------------------------------------------------------------

/** Cabeçalho compacto (48 dp) no lugar do TopAppBar do Material (64 dp). */
@Composable
fun PhScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    topBarActions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = PhBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(PhSurface2, PhSurface)))
                    .statusBarsPadding()
                    .height(HEADER_HEIGHT)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = PhViolet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = PhTextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                topBarActions()
            }
        },
        content = content,
    )
}

/** FLAG_SECURE: a tela do QR de pareamento não pode aparecer em print/recents. */
@Composable
fun PhSecureScreen(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val activity = view.context as? Activity
        if (enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

// ---------------------------------------------------------------------------
// PhCard
// ---------------------------------------------------------------------------

/**
 * Superfície de card (.ph-card do DESIGN.md §6.1).
 *
 * Com [onClick] o card vira clicável e a borda acende em **magenta** no toque —
 * o card estático acende em violeta.
 */
@Composable
fun PhCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color = PhBorder,
    glowing: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickable = onClick != null
    val active = pressed && clickable

    val borderColor by animateColorAsState(
        targetValue = when {
            active -> PhMagenta
            glowing -> PhViolet
            else -> accent
        },
        animationSpec = tween(durationMillis = 140),
        label = "phCardBorder",
    )
    val container by animateColorAsState(
        targetValue = if (active) PhSurface2 else PhSurface,
        animationSpec = tween(durationMillis = 140),
        label = "phCardContainer",
    )

    var cardModifier = modifier
    if (glowing || active) {
        cardModifier = cardModifier.phGlow(color = if (active) PhMagenta else PhViolet, elevation = 12.dp)
    }
    if (clickable) {
        cardModifier = cardModifier
            .phPress(interaction, enabled = true, scale = PRESS_SCALE_CARD)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = null) { onClick?.invoke() }
    }

    Card(
        modifier = cardModifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = contentModifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// PhButton
// ---------------------------------------------------------------------------

/**
 * A partir daqui um prazo deixa de ser contagem regressiva.
 *
 * Os cartões de aprovação e de pergunta podem chegar SEM prazo: quando a tela do
 * PC pergunta ao mesmo tempo, o cartão do celular vive enquanto a pergunta viver.
 * O PC manda então o maior valor que o Node aceita (2^31-1 ms, quase 25 dias) —
 * número que não ajuda ninguém e faria a tela escrever "2147483s".
 */
const val PH_SEM_PRAZO_MS = 5 * 60_000L

enum class PhButtonVariant { Primary, Danger, Ghost }

/**
 * Botões .ph-btn / --danger / --ghost (DESIGN.md §5.4).
 *
 * Com [text] vazio o botão fica só com o ícone — é o caso do "atualizar" da
 * barra do chat, onde três rótulos não cabem na largura de um celular. Quem
 * usa assim PRECISA passar [description]: sem ela o ícone não tem nome nenhum
 * para quem lê a tela por um leitor.
 *
 * @param text rótulo; vazio deixa só o ícone.
 * @param onClick toque.
 * @param modifier modificador de layout.
 * @param variant cor e forma do botão.
 * @param enabled se responde ao toque.
 * @param loading troca o ícone pela rodinha e trava o toque.
 * @param icon ícone opcional.
 * @param description nome do ícone para acessibilidade.
 */
@Composable
fun PhButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PhButtonVariant = PhButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    description: String? = null,
    /**
     * Tamanho de badge: mesmo vestido do [PhBadge] (padding vertical 3,
     * labelSmall, ícone 14) para os dois conviverem na barra do topo sem um
     * parecer solto ao lado do outro. Padrão false — quem já usou o botão
     * grande não muda de cara.
     */
    small: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val active = enabled && !loading
    val tone = when (variant) {
        PhButtonVariant.Primary -> PhViolet
        PhButtonVariant.Danger -> PhDanger
        PhButtonVariant.Ghost -> PhTextDim
    }
    val shared = modifier.phPress(interaction, active, PRESS_SCALE_BUTTON)

    val label: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (small) 12.dp else 14.dp),
                strokeWidth = 2.dp,
                color = tone,
            )
            if (text.isNotBlank()) Spacer(Modifier.width(if (small) 6.dp else 10.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tone,
                modifier = Modifier.size(if (small) 14.dp else 16.dp),
            )
            if (text.isNotBlank()) Spacer(Modifier.width(if (small) 6.dp else 8.dp))
        }
        if (text.isNotBlank()) {
            Text(
                text = text.uppercase(),
                style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                color = tone,
            )
        }
    }



    if (small) {
        // O traje do PhBadge de propósito — e É FORA dos botões do Material:
        // TextButton/OutlinedButton impõem contentPadding e minHeight próprios
        // (~40 dp) que não deixam encolher nem com o param small. Pílula igual à
        // do "ao vivo": clip, fundo tingido, borda 1 dp e o mesmo padding 10/3 —
        // a altura nasce a mesma do badge, sem forçar medida de fora.
        Row(
            modifier = shared
                .clip(MaterialTheme.shapes.small)
                .background(if (active) tone.copy(alpha = 0.14f) else PhSurface2)
                .border(
                    1.dp,
                    if (active) tone.copy(alpha = 0.45f) else PhBorder,
                    MaterialTheme.shapes.small,
                )
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = active,
                    onClickLabel = description,
                    onClick = onClick,
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            label()
        }
        return
    }

    when (variant) {
        PhButtonVariant.Ghost -> TextButton(
            onClick = onClick,
            modifier = shared,
            enabled = active,
            shape = MaterialTheme.shapes.extraSmall,
            interactionSource = interaction,
            content = label,
        )

        PhButtonVariant.Danger -> OutlinedButton(
            onClick = onClick,
            modifier = shared,
            enabled = active,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = PhDanger,
                disabledContentColor = PhTextMute,
            ),
            border = BorderStroke(1.dp, if (active) PhDanger.copy(alpha = 0.6f) else PhBorder),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            interactionSource = interaction,
            content = label,
        )

        PhButtonVariant.Primary -> Button(
            onClick = onClick,
            modifier = shared,
            enabled = active,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = PhViolet.copy(alpha = 0.16f),
                contentColor = PhViolet,
                disabledContainerColor = PhSurface2,
                disabledContentColor = PhTextMute,
            ),
            border = BorderStroke(1.dp, if (active) PhViolet.copy(alpha = 0.55f) else PhBorder),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            interactionSource = interaction,
            content = label,
        )
    }
}

/**
 * Botão redondo, só com ícone — é o enviar do chat, ao lado do campo.

 * Redondo e sem rótulo porque ele vive AO LADO do campo, e não embaixo: um botão
 * de largura fixa come a linha inteira que o texto precisa. Sem texto, quem lê a
 * tela depende de [description] — por isso ela não tem valor padrão.
 *
 * @param icon ícone do botão.
 * @param description nome do ícone para acessibilidade.
 * @param onClick toque.
 * @param modifier modificador de layout.
 * @param enabled se responde ao toque.
 * @param tone cor do ícone e do anel.
 */
@Composable
fun PhIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color = PhViolet,
) {
    val interaction = remember { MutableInteractionSource() }
    // Desenhado a mao, e nao com `IconButton` do Material: o botao do Material
    // impoe o proprio tamanho e o proprio alvo de toque, e o circulo sairia com
    // um diametro que nao e o pedido. Aqui o tamanho e o do design system.
    Box(
        modifier = modifier
            .size(48.dp)
            .phPress(interaction, enabled, PRESS_SCALE_BUTTON)
            .clip(CircleShape)
            .background(if (enabled) tone.copy(alpha = 0.16f) else PhSurface2)
            .border(1.dp, if (enabled) tone.copy(alpha = 0.55f) else PhBorder, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tone else PhTextMute,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// PhBadge / PhStatusDot
// ---------------------------------------------------------------------------

enum class PhTone { Violet, Magenta, Ok, Warn, Danger, Info, Neutral }

fun phToneColor(tone: PhTone): Color = when (tone) {
    PhTone.Violet -> PhViolet
    PhTone.Magenta -> PhMagenta
    PhTone.Ok -> PhOk
    PhTone.Warn -> PhWarn
    PhTone.Danger -> PhDanger
    PhTone.Info -> PhInfo
    PhTone.Neutral -> PhTextDim
}

/** Glifo do estado: a cor nunca é o único sinal (DESIGN.md §8). */
fun phToneGlyph(tone: PhTone): String = when (tone) {
    PhTone.Ok -> "\u25CF"
    PhTone.Warn, PhTone.Magenta -> "\u25B2"
    PhTone.Danger -> "\u2715"
    PhTone.Info -> "\u25C6"
    PhTone.Violet -> "\u25C6"
    PhTone.Neutral -> "\u25CB"
}

@Composable
fun PhBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: PhTone = PhTone.Violet,
    glyph: Boolean = false,
) {
    val color = phToneColor(tone)
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (glyph) {
            Text(text = phToneGlyph(tone), color = color, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = text.uppercase(),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** Indicador de status (.ph-status): bolinha + rótulo. */
@Composable
fun PhStatusDot(
    online: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
    busy: Boolean = false,
    tone: PhTone? = null,
) {
    val color = tone?.let(::phToneColor) ?: when {
        busy -> PhAmber
        online -> PhOk
        else -> PhDanger
    }
    val text = label ?: when {
        busy -> "ocupado"
        online -> "online"
        else -> "offline"
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(MaterialTheme.shapes.small)
                .background(color)
                .phGlow(color = color, elevation = 8.dp, alpha = 0.5f),
        )
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// PhSectionTitle / PhBar / PhState
// ---------------------------------------------------------------------------

@Composable
fun PhSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = PhText,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Barra de progresso (.ph-bar): trilho em surface-2 e preenchimento no acento. */
@Composable
fun PhBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = PhViolet,
    height: Dp = 6.dp,
) {
    val value = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(PhSurface2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(value)
                .height(height)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.65f), color)))
                .phGlow(color = color, elevation = 8.dp, alpha = 0.35f),
        )
    }
}

enum class PhStateKind { Empty, Loading, Error }

/** Estado vazio / carregando / erro no lugar do texto solto "Sem dados". */
@Composable
fun PhState(
    message: String,
    modifier: Modifier = Modifier,
    kind: PhStateKind = PhStateKind.Empty,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tone = when (kind) {
        PhStateKind.Empty -> PhTone.Neutral
        PhStateKind.Loading -> PhTone.Violet
        PhStateKind.Error -> PhTone.Danger
    }
    val color = phToneColor(tone)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (kind == PhStateKind.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = PhViolet,
            )
        } else {
            Text(
                text = phToneGlyph(tone),
                color = color,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Text(
            text = message,
            color = if (kind == PhStateKind.Error) PhDanger else PhTextDim,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            PhButton(text = actionLabel, onClick = onAction, variant = PhButtonVariant.Ghost)
        }
    }
}

// ---------------------------------------------------------------------------
// PhTextField
// ---------------------------------------------------------------------------

/** Campo de texto (.ph-input): borda padrão em repouso, violeta com glow no foco. */
@Composable
fun PhTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = PhTextDim,
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = PhText,
                fontWeight = FontWeight.Normal,
            ),
            // Lambda sempre presente: evita depender de inferência de lambda
            // composable anulável (o hint vazio não ocupa espaço).
            placeholder = {
                val hint = placeholder
                if (hint != null) {
                    Text(text = hint, color = PhTextMute, style = MaterialTheme.typography.bodySmall)
                }
            },
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = PhText,
                unfocusedTextColor = PhText,
                disabledTextColor = PhTextMute,
                cursorColor = PhViolet,
                focusedBorderColor = PhViolet,
                unfocusedBorderColor = PhBorder,
                disabledBorderColor = PhBorder,
                errorBorderColor = PhDanger,
                focusedContainerColor = PhBg,
                unfocusedContainerColor = PhBg,
                disabledContainerColor = PhSurface2,
                focusedPlaceholderColor = PhTextMute,
                unfocusedPlaceholderColor = PhTextMute,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Auxiliares de composição
// ---------------------------------------------------------------------------

/** Linha "rótulo → valor" alinhada nas duas pontas, com o valor em mono. */
@Composable
fun PhKeyValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PhVioletSoft,
    divider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = PhTextDim,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (divider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(PhBorder.copy(alpha = 0.45f)),
            )
        }
    }
}

/** Separador fino, para blocos densos. */
@Composable
fun PhDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PhBorder.copy(alpha = 0.5f)),
    )
}

/** Fundo com a chuva de caracteres atrás do conteúdo de uma tela. */
@Composable
fun PhScreenBackground(
    modifier: Modifier = Modifier,
    opacity: Float = 0.12f,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(PhBg)) {
        com.pockethound.app.ui.matrix.PhRainBackground(opacity = opacity)
        content()
    }
}
