package cl.relojnoche

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlin.math.cos
import kotlin.math.sin

/**
 * Esfera analógica estilo StandBy de iOS: números 1-12, marcas de minutos,
 * agujas redondeadas y segundero naranja de barrido continuo.
 */
@Composable
fun AnalogClock(settings: ClockSettings, colors: FaceColors, info: ClockInfo, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        val dial = min(maxWidth, maxHeight)
        val small = (dial * 0.05f).coerceIn(8.dp, 26.dp)
        Box(Modifier.size(dial), contentAlignment = Alignment.Center) {
            DialMarks(colors, Modifier.fillMaxSize())
            // Fecha y batería/alarma dentro de la esfera, como "complicaciones".
            val minute by rememberMinuteTick()
            if (settings.showDate) {
                val date = remember(zonedTime(minute).toLocalDate()) { formatSkeleton(minute, "EEEd") }
                DateLine(date, small, colors.secondary, Modifier.offset(y = dial * 0.2f))
            }
            if (settings.showInfo) {
                InfoRow(info, settings.use24h, minute, small, colors.secondary, Modifier.offset(y = -dial * 0.2f))
            }
            Hands(settings.analogSeconds, settings.smoothSeconds, colors, Modifier.fillMaxSize())
        }
    }
}

/** Parte fija: marcas y números. Solo se redibuja si cambia el tamaño o el color. */
@Composable
private fun DialMarks(colors: FaceColors, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val ink = inkRatios(LocalContext.current, FontWeight.ExtraBold)
    val numeralColor = colors.primary
    val hourMark = colors.secondary
    val minuteMark = colors.faint
    Spacer(
        modifier.drawWithCache {
            val r = size.minDimension / 2f
            val fontPx = r * 0.19f
            val style = clockTextStyle(FontWeight.ExtraBold, with(density) { fontPx.toSp() })
            val numerals = (1..12).map { measurer.measure(it.toString(), style) }
            // Centro vertical de la tinta de los números, medido desde arriba de su caja.
            val inkCenter = numerals[0].firstBaseline + (ink.digitTop + ink.digitBottom) / 2f * fontPx
            onDrawBehind {
                val c = center
                for (i in 0 until 60) {
                    val isHour = i % 5 == 0
                    val len = if (isHour) r * 0.075f else r * 0.04f
                    val w = if (isHour) r * 0.02f else r * 0.009f
                    rotate(i * 6f, c) {
                        drawLine(
                            color = if (isHour) hourMark else minuteMark,
                            start = Offset(c.x, c.y - r * 0.97f),
                            end = Offset(c.x, c.y - r * 0.97f + len),
                            strokeWidth = w,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                val nr = r * 0.73f
                numerals.forEachIndexed { idx, layout ->
                    val a = Math.toRadians(((idx + 1) * 30).toDouble())
                    val x = c.x + (nr * sin(a)).toFloat()
                    val y = c.y - (nr * cos(a)).toFloat()
                    drawText(
                        layout,
                        color = numeralColor,
                        topLeft = Offset(x - layout.size.width / 2f, y - inkCenter),
                    )
                }
            }
        },
    )
}

/** Agujas. Leen la hora en la fase de dibujo: solo se repinta esta capa. */
@Composable
private fun Hands(showSeconds: Boolean, smoothSeconds: Boolean, colors: FaceColors, modifier: Modifier) {
    // Solo refrescar rápido si hay segundero Y está en modo suave. Si no, el
    // reloj se redibuja 1 vez por segundo (o por minuto sin segundero): mucho
    // menos consumo y calor.
    val now by rememberTimeMillis(smooth = showSeconds && smoothSeconds, ticking = showSeconds)
    val main = colors.primary
    val sec = colors.seconds
    Spacer(
        modifier.drawBehind {
            val t = zonedTime(now)
            val s = t.second + t.nano / 1_000_000_000f
            val m = t.minute + s / 60f
            val h = (t.hour % 12) + m / 60f
            val r = size.minDimension / 2f
            val c = center
            hand(h * 30f, c, neck = r * 0.13f, length = r * 0.50f, stem = r * 0.024f, bar = r * 0.065f, color = main)
            hand(m * 6f, c, neck = r * 0.13f, length = r * 0.82f, stem = r * 0.024f, bar = r * 0.05f, color = main)
            drawCircle(main, radius = r * 0.048f, center = c)
            if (showSeconds) {
                rotate(s * 6f, c) {
                    drawLine(
                        color = sec,
                        start = Offset(c.x, c.y + r * 0.17f),
                        end = Offset(c.x, c.y - r * 0.9f),
                        strokeWidth = r * 0.013f,
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(sec, radius = r * 0.033f, center = c)
            }
            drawCircle(Color.Black, radius = r * 0.013f, center = c)
        },
    )
}

/** Aguja tipo iOS: un tallo fino junto al centro y luego una barra ancha. */
private fun DrawScope.hand(
    angle: Float,
    c: Offset,
    neck: Float,
    length: Float,
    stem: Float,
    bar: Float,
    color: Color,
) {
    rotate(angle, c) {
        drawLine(color, c, Offset(c.x, c.y - neck), stem, StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y - neck), Offset(c.x, c.y - length), bar, StrokeCap.Round)
    }
}
