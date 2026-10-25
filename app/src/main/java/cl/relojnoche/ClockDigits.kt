package cl.relojnoche

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInQuad
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Margen arriba/abajo de la tinta del dígito dentro de su celda (fracción del alto de tinta). */
private const val INK_PAD = 0.08f

/**
 * Medidas de una celda de dígito para un estilo dado. La celda se ajusta a la
 * tinta del dígito (no a la caja de texto, que en Nunito es casi el doble).
 */
@Immutable
data class DigitMetrics(
    val digitWidth: Dp,    // el más ancho de 0-9: todas las celdas iguales, nada "baila"
    val colonWidth: Dp,
    val cellHeight: Dp,
    val inkHeight: Dp,
    val digitTextTop: Dp,  // dónde poner la caja de texto para centrar la tinta del dígito
    val colonTextTop: Dp,  // idem para los dos puntos
) {
    /** Distancia desde arriba de la celda hasta la base de los dígitos. */
    val inkBottom: Dp get() = cellHeight - inkHeight * INK_PAD

    operator fun times(k: Float) = DigitMetrics(
        digitWidth * k, colonWidth * k, cellHeight * k, inkHeight * k, digitTextTop * k, colonTextTop * k,
    )
}

@Composable
fun rememberDigitMetrics(style: TextStyle): DigitMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val context = LocalContext.current
    return remember(style, density, measurer) {
        val ink = inkRatios(context, style.fontWeight)
        val digits = (0..9).map { measurer.measure(it.toString(), style) }
        val colon = measurer.measure(":", style)
        with(density) {
            val f = style.fontSize.toPx()
            val baseline = digits[0].firstBaseline
            val inkH = (ink.digitBottom - ink.digitTop) * f
            val cellH = inkH * (1 + 2 * INK_PAD)
            DigitMetrics(
                digitWidth = digits.maxOf { it.size.width }.toDp(),
                colonWidth = colon.size.width.toDp(),
                cellHeight = cellH.toDp(),
                inkHeight = inkH.toDp(),
                digitTextTop = (INK_PAD * inkH - (baseline + ink.digitTop * f)).toDp(),
                colonTextTop = (cellH / 2f - (colon.firstBaseline + ink.colonCenter * f)).toDp(),
            )
        }
    }
}

/** Coloca la caja de texto (más alta que la celda) para que la tinta quede centrada. */
private fun Modifier.placeInCell(textTop: Dp): Modifier =
    this.wrapContentSize(Alignment.TopCenter, unbounded = true).offset(y = textTop)

enum class DigitMotion { STATIC, ROLL, FALL }

/**
 * Dibuja una hora tipo "09:41" con una celda de ancho fijo por carácter.
 * Las celdas se identifican desde la derecha, así minutos y segundos no se
 * reinician cuando la hora pasa de 1 a 2 dígitos (formato 12 h).
 */
@Composable
fun ClockDigits(
    text: String,
    style: TextStyle,
    metrics: DigitMetrics,
    color: Color,
    motion: DigitMotion,
    modifier: Modifier = Modifier,
    colonColor: Color = color,
    enterDelayBaseMs: Int = 0,
) {
    Row(modifier) {
        val n = text.length
        text.forEachIndexed { i, ch ->
            key(n - i) {
                val w = if (ch == ':') metrics.colonWidth else metrics.digitWidth
                Box(Modifier.size(w, metrics.cellHeight)) {
                    when {
                        ch == ':' -> Glyph(":", style, colonColor, Modifier.placeInCell(metrics.colonTextTop))
                        motion == DigitMotion.FALL -> FallingGlyph(
                            ch, style, color, metrics.cellHeight, metrics.digitTextTop,
                            enterDelayBaseMs + i * 70,
                        )
                        motion == DigitMotion.ROLL -> RollingGlyph(ch, style, color, metrics.digitTextTop)
                        else -> Glyph(ch.toString(), style, color, Modifier.placeInCell(metrics.digitTextTop))
                    }
                }
            }
        }
    }
}

@Composable
fun Glyph(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = 1,
        softWrap = false,
    )
}

/** Cambio suave tipo iOS: el dígito nuevo sube desde abajo mientras el viejo se va. */
@Composable
private fun RollingGlyph(ch: Char, style: TextStyle, color: Color, textTop: Dp) {
    AnimatedContent(
        targetState = ch,
        modifier = Modifier.placeInCell(textTop),
        transitionSpec = {
            (slideInVertically(tween(450, easing = EaseOutCubic)) { it / 3 } + fadeIn(tween(350)))
                .togetherWith(slideOutVertically(tween(450, easing = EaseOutCubic)) { -it / 3 } + fadeOut(tween(220)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.TopCenter,
        label = "digit",
    ) { c ->
        Glyph(c.toString(), style, color)
    }
}

// --- Caída -------------------------------------------------------------------

private const val DROP_START = -1.45f  // fracción del alto de celda (arriba, fuera de vista)
private const val FALL_DISTANCE = 1.45f
private const val IMPACT_MS = 430
private const val LEAVE_MS = 520

/** Un dígito en pantalla con sus propias animaciones (entra cayendo, luego se va cayendo). */
private class FallItem(val ch: Char) {
    val y = Animatable(DROP_START)  // posición de entrada, en altos de celda
    val fall = Animatable(0f)        // progreso de la salida 0..1
    val squash = Animatable(0f)      // aplastado al tocar el piso
    var tilt = 1f
    var leaving by mutableStateOf(false)
}

/** Cae con gravedad, golpea el "piso" y da dos rebotes cada vez más chicos. */
private val DropSpec: AnimationSpec<Float> = keyframes {
    durationMillis = 840
    DROP_START at 0 using EaseInQuad
    0f at IMPACT_MS using EaseOutQuad
    -0.13f at 575 using EaseInQuad
    0f at 710 using EaseOutQuad
    -0.035f at 775 using EaseInQuad
    0f at 840
}

/**
 * Un dígito que, al cambiar, deja caer el valor viejo (se inclina y se
 * desvanece) mientras el nuevo cae desde arriba y rebota al llegar.
 * La primera vez que aparece también cae, con un pequeño retraso por
 * posición para que los dígitos "lluevan" de izquierda a derecha.
 */
@Composable
private fun FallingGlyph(
    ch: Char,
    style: TextStyle,
    color: Color,
    cellHeight: Dp,
    textTop: Dp,
    enterDelayMs: Int,
) {
    val h = with(LocalDensity.current) { cellHeight.toPx() }
    // Cada valor es un objeto propio con sus animaciones: el nuevo nace ya
    // escondido arriba y el viejo sigue visible hasta que empieza a caer, así
    // nunca hay un cuadro en blanco entre uno y otro.
    val items = remember { mutableStateListOf<FallItem>() }

    LaunchedEffect(ch) {
        val previous = items.lastOrNull { !it.leaving }
        if (previous?.ch == ch) return@LaunchedEffect
        val first = previous == null
        val item = FallItem(ch)
        items.add(item)
        coroutineScope {
            if (previous != null) {
                launch {
                    try {
                        previous.tilt = if (Random.nextBoolean()) 1f else -1f
                        previous.leaving = true
                        previous.fall.animateTo(1f, tween(LEAVE_MS, easing = EaseInQuad))
                    } finally {
                        items.remove(previous)
                    }
                }
            }
            launch {
                if (first) delay(enterDelayMs.toLong())
                item.y.animateTo(0f, DropSpec)
            }
            launch {
                delay((if (first) enterDelayMs else 0) + IMPACT_MS.toLong())
                item.squash.animateTo(1f, tween(70, easing = EaseOutQuad))
                item.squash.animateTo(0f, tween(240, easing = EaseOutQuad))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        items.forEach { item ->
            key(item) {
                Glyph(
                    item.ch.toString(), style, color,
                    Modifier
                        .placeInCell(textTop)
                        .graphicsLayer {
                            if (item.leaving) {
                                // Se desploma desde donde estaba, se inclina y se apaga.
                                val p = item.fall.value
                                translationY = (item.y.value + p * FALL_DISTANCE) * h
                                rotationZ = item.tilt * 18f * p
                                alpha = (1f - p * 1.1f).coerceIn(0f, 1f)
                            } else {
                                translationY = item.y.value * h
                                val s = item.squash.value
                                scaleY = 1f - 0.10f * s
                                scaleX = 1f + 0.05f * s
                                transformOrigin = TransformOrigin(0.5f, 0.85f)
                            }
                        },
                )
            }
        }
    }
}

/**
 * Recorta el contenido a su caja y desvanece los bordes de arriba y abajo,
 * así los dígitos aparecen y desaparecen suavemente al caer.
 */
fun Modifier.verticalFadingEdges(edge: Float): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                edge to Color.Black,
                1f - edge to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
