package com.pockethound.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pockethound.app.R

/*
 * Três famílias, como no app Android anterior — e por um motivo diferente do
 * desktop, que usa só duas.
 *
 *   Orbitron       títulos e marca   — geométrica, futurista
 *   Rajdhani       rótulos e botões  — condensada, técnica, feita para caixa alta
 *   JetBrains Mono corpo e dados     — terminal
 *
 * O desktop não tem a Rajdhani porque lá os rótulos são Orbitron em caixa alta.
 * No celular a Rajdhani existe porque o espaço é curto: uma condensada em caixa
 * alta com tracking largo cabe onde a Orbitron quebraria linha.
 *
 * Os pesos pedidos são exatamente os que os arquivos carregam. O sistema
 * anterior pedia font-weight 800 a uma família que não tinha esse peso e o
 * navegador sintetizava o negrito — aqui isso não acontece.
 */

/** Orbitron: geométrica e futurista, para títulos e marca. */
val Orbitron = FontFamily(
    Font(R.font.orbitron_medium, FontWeight.Medium),
    Font(R.font.orbitron_bold, FontWeight.Bold),
)

/** Rajdhani: condensada e técnica, para rótulos, botões e navegação. */
val Rajdhani = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

/** JetBrains Mono: terminal moderno, para corpo, dados e valores. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

val PhTypography = Typography(
    // Títulos — Orbitron
    headlineMedium = TextStyle(
        fontFamily = Orbitron,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 1.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Orbitron,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 1.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Orbitron,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 0.8.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Orbitron,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 1.1.sp,
    ),
    // Rótulos — Rajdhani. Só o corpo declara lineHeight.
    titleSmall = TextStyle(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.6.sp,
    ),
    // Corpo — JetBrains Mono
    bodyLarge = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.8.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
    ),
)
