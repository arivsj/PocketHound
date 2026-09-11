package com.pockethound.app.ui.matrix

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.pockethound.app.core.model.DeskState
import com.pockethound.app.ui.theme.PhAmber
import com.pockethound.app.ui.theme.PhRainBody
import com.pockethound.app.ui.theme.PhRainHead
import com.pockethound.app.ui.theme.PhDanger
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** Katakana + dígitos + símbolos, como na chuva do filme. */
private const val GLIFOS =
    "アァカサタナハマヤラワイキシチニヒミリウクスツヌフムユルエケセテネヘメレオコソトノホモヨロ" +
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<>/|*+=#$%&@"

private const val TRAIL_LENGTH = 16
private const val GLYPH_SWAP_MS = 90L
private const val WORD_DURATION_MS = 2200L
private const val WORD_FADE_MS = 420L
private const val MAX_WORDS = 3

/** Quantos quadros por segundo a chuva desenha. */
const val RAIN_FPS = 30

/** Palavras que se formam onde a chuva esbarra no cartão. */
private val RAIN_WORDS = listOf("GO AWAY", "DANGER", "GET OUT", "STAY BACK", "DENIED", "TURN BACK")

/**
 * Cor da chuva conforme a carga do PC (DESIGN.md §5.5): violeta em repouso, âmbar
 * sob carga, perigo em faixa crítica. O corpo é [PhRainBody], a cabeça [PhRainHead].
 */
fun rainColorFor(loadFactor: Float): Color = when {
    loadFactor >= 0.85f -> PhDanger
    loadFactor >= 0.60f -> PhAmber
    else -> PhRainBody
}

fun rainColorFor(deskState: DeskState?): Color = rainColorFor(deskState?.loadFactor ?: 0f)

private fun Color.argbWith(alpha: Float): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt().coerceIn(0, 255),
    (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255),
    (blue * 255f).toInt().coerceIn(0, 255),
)

private class RainWord(val text: String) {
    var ageMs = 0L

    fun alpha(): Float = when {
        ageMs < WORD_FADE_MS -> ageMs.toFloat() / WORD_FADE_MS
        ageMs > WORD_DURATION_MS ->
            max(0f, 1f - (ageMs - WORD_DURATION_MS).toFloat() / WORD_FADE_MS)

        else -> 1f
    }

    fun finished(): Boolean = ageMs > WORD_DURATION_MS + WORD_FADE_MS
}

private class RainColumn(
    val index: Int,
    var x: Float,
    var y: Float,
    var speed: Float,
    private val seed: Int,
) {
    private var totalAge = 0L
    var word: RainWord? = null
    var exitAlpha = 1f

    /** Posição antes do último passo: detecta o cruzamento da borda do cartão. */
    var previousY = y
    private var firstPosition = true

    /** Glifo da célula: muda com o tempo, sem guardar buffer. */
    fun glyph(cell: Int, timeMs: Long): Char {
        val slice = timeMs / GLYPH_SWAP_MS
        val h = (index * 73856093) xor (cell * 19349663) xor (slice.toInt() * 83492791)
        val i = (h % GLIFOS.length + GLIFOS.length) % GLIFOS.length
        return GLIFOS[i]
    }

    fun update(deltaMs: Float, totalHeight: Float, card: Rect, canSpell: Boolean) {
        word?.let { it.ageMs += deltaMs.toLong() }
        if (word?.finished() == true) {
            word = null
            // Volta a cair logo acima do cartão para bater nele de novo em poucos
            // segundos; sem isso a coluna levaria quase um minuto para dar a volta.
            if (canSpell) {
                y = card.top - 90f - Random(seed + totalAge).nextInt(60, 520)
                previousY = y
            }
        }
        if (word == null) {
            previousY = y
            y += speed * deltaMs / 1000f
            if (y > totalHeight + 200f) {
                y = -Random(seed + y.toInt()).nextInt(80, 700).toFloat()
                previousY = y
            }
        }
        if (firstPosition) {
            firstPosition = false
            previousY = y
        }
        totalAge += deltaMs.toLong()
    }
}

/**
 * Motor da chuva de caracteres.
 *
 * O estado das colunas vive fora do Compose para não recompor a cada quadro: o
 * desenho lê o tempo de um State e só redesenha.
 */
internal class RainEngine {
    private var columns: Array<RainColumn> = emptyArray()
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var cell = 0f
    private var lastTime = 0L
    private val random = Random(20260910)

    private val brush = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val wordBrush = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0f
    }

    val cellSize: Float get() = cell

    fun configure(width: Float, height: Float, preferredCell: Float) {
        if (width <= 0f || height <= 0f || preferredCell <= 0f) return
        if (abs(width - lastWidth) < 1f && abs(height - lastHeight) < 1f &&
            abs(preferredCell - cell) < 1f
        ) {
            return
        }
        lastWidth = width
        lastHeight = height
        cell = preferredCell
        brush.textSize = cell * 0.86f
        wordBrush.textSize = cell * 0.82f

        val count = max(1, (width / cell).toInt())
        columns = Array(count) { i ->
            RainColumn(
                index = i,
                x = (i + 0.5f) * cell,
                y = random.nextFloat() * height,
                speed = cell * (2.2f + random.nextFloat() * 4.6f),
                seed = i * 7919,
            )
        }
    }

    private fun advance(timeMs: Long, height: Float, card: Rect) {
        if (lastTime == 0L) {
            lastTime = timeMs
            return
        }
        val delta = (timeMs - lastTime).coerceIn(0L, 50L).toFloat()
        lastTime = timeMs
        val canSpell = card.height > 0f
        columns.forEach { it.update(delta, height, card, canSpell) }
    }

    /**
     * Registra o impacto da chuva na borda de cima do cartão: a coluna para de cair
     * e passa a soletrar uma palavra de aviso no ponto exato da batida.
     */
    private fun registerImpacts(card: Rect) {
        if (card.width <= 0f) return
        val active = columns.count { it.word != null }
        columns.forEach { column ->
            if (column.word != null) return@forEach
            if (column.x < card.left || column.x > card.right) return@forEach
            if (column.previousY < card.top && column.y >= card.top) {
                if (active >= MAX_WORDS) return@forEach
                val near = columns.any { other ->
                    other !== column && other.word != null && abs(other.x - column.x) < cell * 4f
                }
                if (near) return@forEach
                column.word = RainWord(RAIN_WORDS.random(random))
                column.y = card.top
            }
        }
    }

    /** 0 = chuva normal, 1 = apagada. Sai do centro para as pontas. */
    private fun applyExit(progress: Float, width: Float) {
        if (progress <= 0f) {
            columns.forEach { it.exitAlpha = 1f }
            return
        }
        val center = width / 2f
        columns.forEach { column ->
            val distance = abs(column.x - center) / center
            val start = distance * 0.55f
            val local = ((progress - start) / 0.45f).coerceIn(0f, 1f)
            column.exitAlpha = 1f - local
        }
    }

    fun draw(
        scope: DrawScope,
        timeMs: Long,
        card: Rect,
        exitProgress: Float,
        bodyColor: Color,
    ) {
        val width = scope.size.width
        val height = scope.size.height
        configure(width, height, cell.takeIf { it > 0f } ?: (width / 42f))
        advance(timeMs, height, card)
        registerImpacts(card)
        applyExit(exitProgress, width)

        val canvas = scope.drawContext.canvas.nativeCanvas
        if (cell <= 0f) return

        columns.forEach { column ->
            if (column.exitAlpha <= 0.01f) return@forEach
            var i = 0
            while (i < TRAIL_LENGTH) {
                val y = column.y - i * cell
                if (y < -cell || y > height + cell) {
                    i++
                    continue
                }
                val insideCard = card.contains(Offset(column.x, y))
                val fade = 1f - i / TRAIL_LENGTH.toFloat()
                // Sobre o cartão a chuva fica discreta para não sujar o texto.
                val damping = if (insideCard) 0.22f else 1f
                val alpha = (fade * fade * 0.95f + if (i == 0) 0.05f else 0f) *
                    column.exitAlpha * damping
                if (alpha <= 0.02f) {
                    i++
                    continue
                }
                // A cabeça é clara e puxa o rastro (DESIGN.md §1.5).
                brush.color = (if (i == 0) PhRainHead else bodyColor).argbWith(alpha)
                val glyph = if (i == 0) column.glyph(0, timeMs) else column.glyph(i, timeMs - i * 130L)
                canvas.drawText(glyph.toString(), column.x, y, brush)
                i++
            }

            column.word?.let { word ->
                val alpha = word.alpha() * column.exitAlpha
                if (alpha > 0.02f) {
                    wordBrush.color = bodyColor.argbWith(alpha)
                    word.text.forEachIndexed { letterIndex, letter ->
                        if (letter != ' ') {
                            canvas.drawText(
                                letter.toString(),
                                column.x,
                                card.top + cell * (letterIndex + 1.2f),
                                wordBrush,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Relógio da chuva: entrega o tempo em ms no máximo [fps] vezes por segundo.
 *
 * O withFrameNanos continua acordando a cada quadro do display, mas o State só muda
 * quando o intervalo alvo passa — e é a mudança do State que dispara o redesenho.
 * Como a chuva anda em colunas de caracteres discretos, 30 quadros por segundo não
 * muda o que se vê e corta pela metade o trabalho de desenho.
 */
@Composable
fun rememberRainClock(fps: Int = RAIN_FPS): State<Long> {
    val time = remember { mutableStateOf(0L) }
    LaunchedEffect(fps) {
        val interval = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)
        var last = 0L
        while (true) {
            withFrameNanos { nanos ->
                val ms = nanos / 1_000_000L
                if (ms - last >= interval) {
                    last = ms
                    time.value = ms
                }
            }
        }
    }
    return time
}

/**
 * A chuva violeta como pano de fundo de uma tela.
 *
 * TODO(pockethound): a contagem regressiva em glifos da tela de bloqueio do projeto
 * antigo não foi portada — ela não existe no PocketHound.
 */
@Composable
fun PhRainBackground(
    modifier: Modifier = Modifier,
    opacity: Float = 0.5f,
    deskState: DeskState? = null,
) {
    PhRain(modifier = modifier, opacity = opacity, deskState = deskState)
}

/**
 * Canvas da chuva. [card] marca a área onde a chuva "bate" e soletra as palavras de
 * aviso; [exitProgress] apaga a chuva do centro para as pontas.
 */
@Composable
fun PhRain(
    modifier: Modifier = Modifier,
    opacity: Float = 0.5f,
    deskState: DeskState? = null,
    card: Rect = Rect.Zero,
    exitProgress: Float = 0f,
    cellSize: Float? = null,
) {
    val engine = remember { RainEngine() }
    val frame = rememberRainClock()
    val bodyColor = rainColorFor(deskState)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = opacity },
    ) {
        if (cellSize != null) {
            engine.configure(size.width, size.height, cellSize)
        }
        engine.draw(
            scope = this,
            timeMs = frame.value,
            card = card,
            exitProgress = exitProgress,
            bodyColor = bodyColor,
        )
    }
}
