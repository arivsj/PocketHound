package com.pockethound.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/*
 * Mapeamento COMPLETO dos papéis do Material 3 — e este é o ponto.
 *
 * O app anterior mapeava 12 de ~30 papéis e deixava o resto no padrão do
 * Material. O resultado: AlertDialog, OutlinedTextField, LinearProgressIndicator,
 * FilterChip, RadioButton e CircularProgressIndicator renderizavam com o
 * roxo-acinzentado de fábrica, brigando com a identidade em cada diálogo.
 *
 * Aqui todo papel que a UI pode tocar aponta para um token da paleta. Se um
 * componente do Material aparecer numa tela nova, ele já nasce violeta.
 */

// Superfícies em cinco degraus — é o que o Material 3 moderno usa para
// hierarquia no lugar da elevação (que neste sistema é zero em todo lugar).
private val PhColorScheme = darkColorScheme(
    primary = PhViolet,
    onPrimary = PhVoid,
    primaryContainer = PhVioletDeep,
    onPrimaryContainer = PhVioletSoft,
    inversePrimary = PhVioletDeep,

    secondary = PhMagenta,
    onSecondary = PhVoid,
    secondaryContainer = PhSurface3,
    onSecondaryContainer = PhMagenta,

    tertiary = PhAmber,
    onTertiary = PhVoid,
    tertiaryContainer = PhSurface3,
    onTertiaryContainer = PhAmber,

    background = PhBg,
    onBackground = PhText,
    surface = PhSurface,
    onSurface = PhText,
    surfaceVariant = PhSurface2,
    onSurfaceVariant = PhTextDim,
    surfaceTint = PhViolet,

    surfaceContainerLowest = PhVoid,
    surfaceContainerLow = PhSurface,
    surfaceContainer = PhSurface,
    surfaceContainerHigh = PhSurface2,
    surfaceContainerHighest = PhSurface3,
    surfaceBright = PhSurface3,
    surfaceDim = PhBg,

    inverseSurface = PhText,
    inverseOnSurface = PhBg,

    outline = PhBorder,
    outlineVariant = PhBorderHi,
    error = PhDanger,
    onError = PhVoid,
    errorContainer = PhSurface3,
    onErrorContainer = PhDanger,

    scrim = PhVoid,
)

// Uma escala única de cantos para o app inteiro: cards 16, balões 10, barra 22.
private val PhShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PocketHoundTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhColorScheme,
        typography = PhTypography,
        shapes = PhShapes,
        content = content,
    )
}
