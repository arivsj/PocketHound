package com.pockethound.app.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pockethound.app.core.model.PhTab
import com.pockethound.app.ui.theme.Orbitron
import com.pockethound.app.ui.theme.Rajdhani
import com.pockethound.app.ui.theme.PhDanger
import com.pockethound.app.ui.theme.PhMagenta
import com.pockethound.app.ui.theme.PhSurface
import com.pockethound.app.ui.theme.PhSurface2
import com.pockethound.app.ui.theme.PhTextDim
import com.pockethound.app.ui.theme.PhViolet
import com.pockethound.app.ui.theme.PhVoid

/** Item da barra inferior: rota, rótulo e ícone. */
data class PhNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/** As 4 abas do DESIGN.md §7.2, na ordem. */
val phNavItems: List<PhNavItem> = listOf(
    PhNavItem(PhTab.Chat.route, PhTab.Chat.label, Icons.Filled.Forum),
    PhNavItem(PhTab.Approvals.route, PhTab.Approvals.label, Icons.Filled.HowToReg),
    PhNavItem(PhTab.Fleet.route, PhTab.Fleet.label, Icons.Filled.Dns),
    PhNavItem(PhTab.Settings.route, PhTab.Settings.label, Icons.Filled.Settings),
)

private val BAR_HEIGHT = 62.dp
private val ICON_SIZE = 22.dp

/**
 * Menu inferior: ícones, "pill" de seleção que desliza entre os itens e brilho
 * violeta pulsante. Quem chama só informa a rota atual e recebe o clique.
 */
@Composable
fun PhBottomBar(
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    approvalCount: Int = 0,
) {
    val items = phNavItems
    if (items.isEmpty()) return

    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }
    val animatedIndex = animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "phNavIndex",
    )
    val selectionAlpha = animateFloatAsState(
        targetValue = if (selectedIndex >= 0) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "phNavSelectionAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(PhSurface2, PhSurface)))
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            PhViolet.copy(alpha = 0.55f),
                            PhMagenta.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT),
        ) {
            val itemWidth = maxWidth / items.size

            Box(
                modifier = Modifier
                    .width(itemWidth)
                    .height(BAR_HEIGHT)
                    .offset { IntOffset((itemWidth * animatedIndex.value).roundToPx(), 0) }
                    .alpha(selectionAlpha.value),
            ) {
                NeonSelection(itemWidth)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                items.forEach { item ->
                    BarItem(
                        item = item,
                        selected = item.route == selectedRoute,
                        badge = if (item.route == PhTab.Approvals.route) approvalCount else 0,
                        onClick = { onSelect(item.route) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NeonSelection(width: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "phNavPulse")
    val pulse = transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.36f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phNavPulseAlpha",
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(width * 0.8f)
                .height(BAR_HEIGHT)
                // O pulso é infinito: lido dentro do graphicsLayer, só redesenha a
                // camada e não recompõe a barra a cada quadro.
                .graphicsLayer { alpha = pulse.value }
                .background(Brush.radialGradient(colors = listOf(PhViolet, Color.Transparent))),
        )
        Box(
            modifier = Modifier
                .width(width * 0.78f)
                .height(BAR_HEIGHT - 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            PhViolet.copy(alpha = 0.18f),
                            PhMagenta.copy(alpha = 0.05f),
                        ),
                    ),
                )
                .border(1.dp, PhViolet.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
        )
    }
}

@Composable
private fun BarItem(
    item: PhNavItem,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.85f
            selected -> 1.14f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
        label = "phNavScale",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) PhViolet else PhTextDim,
        animationSpec = tween(durationMillis = 220),
        label = "phNavIconColor",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = iconColor,
                modifier = Modifier
                    .size(ICON_SIZE)
                    .scale(scale),
            )
            if (badge > 0) {
                // Badge de pendência pulsante: o mesmo phBadge do desktop.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-6).dp)
                        .size(16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(PhDanger),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badge.coerceAtMost(9).toString(),
                        color = PhVoid,
                        fontFamily = Orbitron,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        // Rajdhani, não Orbitron: a barra tem quatro rótulos lado a lado e a
        // condensada em caixa alta cabe onde a geométrica quebraria linha.
        Text(
            text = item.label,
            color = iconColor,
            fontFamily = Rajdhani,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(if (selected) 1f else 0.75f),
        )
    }
}
