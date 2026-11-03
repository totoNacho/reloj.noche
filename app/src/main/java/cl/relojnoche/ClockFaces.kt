package cl.relojnoche

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Datos chicos que acompañan al reloj. */
@Immutable
data class ClockInfo(val battery: BatteryInfo?, val nextAlarmMillis: Long?)

/** Elige la esfera según el estilo. */
@Composable
fun ClockFace(
    style: ClockStyle,
    settings: ClockSettings,
    colors: FaceColors,
    info: ClockInfo,
    modifier: Modifier = Modifier,
) {
    when (style) {
        ClockStyle.DIGITAL -> DigitalClock(settings, colors, info, modifier)
        ClockStyle.ANALOG -> AnalogClock(settings, colors, info, modifier)
        ClockStyle.FALLING -> FallingClock(settings, colors, info, modifier, DigitMotion.FALL)
        ClockStyle.RAINBOW -> FallingClock(settings, colors, info, modifier, DigitMotion.FALL_COLOR)
    }
}

/** Tamaño de los textos chicos, proporcional a la pantalla. */
fun smallTextSize(w: Dp, h: Dp): Dp = (androidx.compose.ui.unit.min(w, h) * 0.065f).coerceIn(9.dp, 30.dp)

@Composable
fun DateLine(text: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val style = infoTextStyle(with(LocalDensity.current) { size.toSp() })
    Box(modifier.height(size * 1.5f), contentAlignment = Alignment.Center) {
        Glyph(text, style, color)
    }
}

@Composable
fun InfoRow(
    info: ClockInfo,
    use24h: Boolean,
    now: Long,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val style = infoTextStyle(with(LocalDensity.current) { size.toSp() })
    Row(
        modifier.height(size * 1.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(size),
    ) {
        info.nextAlarmMillis?.let { alarm ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_alarm),
                    contentDescription = "Próxima alarma",
                    tint = color,
                    modifier = Modifier.size(size),
                )
                Spacer(Modifier.width(size * 0.3f))
                Glyph(alarmText(alarm, now, use24h), style, color)
            }
        }
        info.battery?.let { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (b.isPlugged) {
                    Icon(
                        painterResource(R.drawable.ic_bolt),
                        contentDescription = "Cargando",
                        tint = color,
                        modifier = Modifier.size(size),
                    )
                    Spacer(Modifier.width(size * 0.15f))
                }
                Glyph("${b.percent}%", style, color)
            }
        }
    }
}

// --- Digital -----------------------------------------------------------------

/**
 * Dígitos grandes y redondeados como el StandBy "Digital" de iOS.
 * Horizontal: HH:MM en una línea. Vertical: horas sobre minutos.
 */
@Composable
fun DigitalClock(settings: ClockSettings, colors: FaceColors, info: ClockInfo, modifier: Modifier = Modifier) {
    val now by rememberTimeMillis(smooth = false, ticking = settings.digitalSeconds)
    val t = zonedTime(now)
    val hh = hourText(t.hour, settings.use24h)
    val mm = two(t.minute)
    val ss = two(t.second)
    val date = remember(t.toLocalDate()) { formatSkeleton(now, "EEEEdMMMM") }
    val density = LocalDensity.current
    val ref = rememberDigitMetrics(clockTextStyle(FontWeight.ExtraBold, with(density) { 100.dp.toSp() }))

    BoxWithConstraints(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val small = smallTextSize(maxWidth, maxHeight)
        val linesH = (if (settings.showDate) small * 1.5f else 0.dp) +
            (if (settings.showInfo) small * 1.5f else 0.dp)
        val landscape = maxWidth >= maxHeight
        val secK = if (landscape) 0.3f else 0.38f

        val scale = if (landscape) {
            val secW = if (settings.digitalSeconds) ref.digitWidth * (2 * secK + 0.12f) else 0.dp
            val unitW = ref.digitWidth * 4 + ref.colonWidth + secW
            min(maxWidth * 0.94f / unitW, (maxHeight - linesH) * 0.9f / ref.cellHeight)
        } else {
            val rows = 2.1f + if (settings.digitalSeconds) secK + 0.05f else 0f
            min(maxWidth * 0.9f / (ref.digitWidth * 2), (maxHeight - linesH) * 0.92f / (ref.cellHeight * rows))
        }
        val style = clockTextStyle(FontWeight.ExtraBold, with(density) { (100.dp * scale).toSp() })
        val m = ref * scale
        val secStyle = clockTextStyle(FontWeight.ExtraBold, with(density) { (100.dp * scale * secK).toSp() })
        val sm = ref * (scale * secK)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (settings.showDate) DateLine(date, small, colors.secondary)
            if (landscape) {
                Row {
                    ClockDigits("$hh:$mm", style, m, colors.primary, DigitMotion.ROLL)
                    if (settings.digitalSeconds) {
                        Spacer(Modifier.width(m.digitWidth * 0.12f))
                        // Segundos chicos, alineados por la base de los dígitos grandes.
                        ClockDigits(
                            ss, secStyle, sm, colors.secondary, DigitMotion.ROLL,
                            Modifier.padding(top = m.inkBottom - sm.inkBottom),
                        )
                    }
                }
            } else {
                ClockDigits(hh, style, m, colors.primary, DigitMotion.ROLL)
                Spacer(Modifier.height(m.cellHeight * 0.1f))
                ClockDigits(mm, style, m, colors.primary, DigitMotion.ROLL)
                if (settings.digitalSeconds) {
                    Spacer(Modifier.height(m.cellHeight * 0.05f))
                    ClockDigits(ss, secStyle, sm, colors.secondary, DigitMotion.ROLL)
                }
            }
            if (settings.showInfo) InfoRow(info, settings.use24h, now, small, colors.secondary)
        }
    }
}

// --- Caída ---------------------------------------------------------------------

private const val FALL_ROW = 1.5f    // alto de cada fila respecto de la celda
private const val FALL_EDGE = 0.16f  // borde que se desvanece arriba y abajo

/**
 * Horas, minutos y segundos que van cayendo: cada dígito que cambia cae
 * desde arriba y rebota, y el valor anterior se desploma y desaparece.
 */
@Composable
fun FallingClock(
    settings: ClockSettings,
    colors: FaceColors,
    info: ClockInfo,
    modifier: Modifier = Modifier,
    motion: DigitMotion = DigitMotion.FALL,
) {
    // La caída muestra siempre segundos, así que refresca cada segundo.
    val now by rememberTimeMillis(smooth = false, ticking = true)
    val t = zonedTime(now)
    val hh = hourText(t.hour, settings.use24h)
    val mm = two(t.minute)
    val ss = two(t.second)
    val date = remember(t.toLocalDate()) { formatSkeleton(now, "EEEEdMMMM") }
    val density = LocalDensity.current
    val ref = rememberDigitMetrics(clockTextStyle(FontWeight.Black, with(density) { 100.dp.toSp() }))

    BoxWithConstraints(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val small = smallTextSize(maxWidth, maxHeight)
        val linesH = (if (settings.showDate) small * 1.5f else 0.dp) +
            (if (settings.showInfo) small * 1.5f else 0.dp)
        val landscape = maxWidth >= maxHeight

        val scale = if (landscape) {
            val unitW = ref.digitWidth * 6 + ref.colonWidth * 2
            min(maxWidth * 0.96f / unitW, (maxHeight - linesH) / (ref.cellHeight * FALL_ROW))
        } else {
            min(maxWidth * 0.9f / (ref.digitWidth * 2), (maxHeight - linesH) / (ref.cellHeight * FALL_ROW * 3))
        }
        val style = clockTextStyle(FontWeight.Black, with(density) { (100.dp * scale).toSp() })
        val m = ref * scale
        val rowModifier = Modifier
            .height(m.cellHeight * FALL_ROW)
            .verticalFadingEdges(FALL_EDGE)
            .padding(horizontal = m.digitWidth * 0.25f)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (settings.showDate) DateLine(date, small, colors.secondary)
            if (landscape) {
                Box(rowModifier, contentAlignment = Alignment.Center) {
                    ClockDigits(
                        "$hh:$mm:$ss", style, m, colors.primary, motion,
                        colonColor = colors.secondary,
                    )
                }
            } else {
                listOf(hh, mm, ss).forEachIndexed { row, part ->
                    Box(rowModifier, contentAlignment = Alignment.Center) {
                        ClockDigits(
                            part, style, m, colors.primary, motion,
                            enterDelayBaseMs = row * 180,
                        )
                    }
                }
            }
            if (settings.showInfo) InfoRow(info, settings.use24h, now, small, colors.secondary)
        }
    }
}
