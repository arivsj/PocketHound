package com.pockethound.app.ui.theme

import androidx.compose.ui.graphics.Color

// Tokens exatos de docs/DESIGN.md §1. Mesma estrutura do sistema anterior,
// eixo cromático deslocado de ciano (#00FFF7) para violeta (#B36BFF).

// Base
val PhVoid = Color(0xFF06030E)
val PhBg = Color(0xFF0A0716)
val PhSurface = Color(0xFF140E28)
val PhSurface2 = Color(0xFF1C1436)
val PhSurface3 = Color(0xFF251A47)
val PhBorder = Color(0xFF2E2154)
val PhBorderHi = Color(0xFF4A3390)

// Acentos
val PhViolet = Color(0xFFB36BFF)
val PhVioletSoft = Color(0xFFD6B4FF)
val PhVioletDeep = Color(0xFF7B3FE4)
val PhMagenta = Color(0xFFE45CFF)
val PhIndigo = Color(0xFF7C7CFF)
val PhAmber = Color(0xFFFF8A3D)

// Semânticos
val PhOk = Color(0xFF35E39B)
val PhWarn = Color(0xFFFFB020)
val PhDanger = Color(0xFFFF2E6A)
val PhInfo = Color(0xFF6BA8FF)

// Texto
val PhText = Color(0xFFDCD2F5)
val PhTextDim = Color(0xFF8A7BB5)
val PhTextMute = Color(0xFF5B4F7D)

// Chuva de caracteres (--ph-rain / --ph-rain-head do DESIGN.md §1.5). O corpo é o
// próprio violeta da marca; a cabeça é o realce claro que puxa o rastro.
val PhRainBody = Color(0xFFB36BFF)
val PhRainHead = Color(0xFFF0E4FF)

// Canais RGB publicados uma vez: todo véu/glow é derivado deles em vez de
// repetir um literal hex espalhado pelo código (DESIGN.md §1.6).
const val PH_VIOLET_RGB = "179, 107, 255"
const val PH_MAGENTA_RGB = "228, 92, 255"
const val PH_DANGER_RGB = "255, 46, 106"
const val PH_AMBER_RGB = "255, 138, 61"
const val PH_OK_RGB = "53, 227, 155"
