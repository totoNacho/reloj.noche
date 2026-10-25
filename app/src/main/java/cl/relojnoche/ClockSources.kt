package cl.relojnoche

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// --- Hora -------------------------------------------------------------------

/** De dónde sale la hora actual (se puede reemplazar para pruebas). */
val LocalTimeSource = staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

/**
 * Hora actual en milisegundos.
 * smooth=true: ~30 veces por segundo (segundero continuo).
 * smooth=false: justo al cambiar cada segundo.
 */
@Composable
fun rememberTimeMillis(smooth: Boolean): State<Long> {
    val source = LocalTimeSource.current
    return produceState(initialValue = source(), smooth, source) {
        while (true) {
            val now = source()
            value = now
            delay(if (smooth) 33L else (1000L - now % 1000L).coerceAtLeast(5L))
        }
    }
}

/** Cambia una vez por minuto (para cosas lentas: alarma, modo noche por hora). */
@Composable
fun rememberMinuteTick(): State<Long> {
    val source = LocalTimeSource.current
    return produceState(initialValue = source(), source) {
        while (true) {
            val now = source()
            value = now
            delay((60_000L - now % 60_000L).coerceAtLeast(5L))
        }
    }
}

fun zonedTime(millis: Long): ZonedDateTime =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

/** "07" en 24 h; "7" en 12 h (sin cero adelante, como iOS). */
fun hourText(hour24: Int, use24h: Boolean): String =
    if (use24h) two(hour24) else (((hour24 + 11) % 12) + 1).toString()

/** Fecha según el idioma del teléfono, p. ej. skeleton "EEEEdMMMM" -> "Domingo, 25 de octubre". */
fun formatSkeleton(millis: Long, skeleton: String, locale: Locale = Locale.getDefault()): String =
    android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale)
        .format(Date(millis))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

fun alarmText(alarmMillis: Long, nowMillis: Long, use24h: Boolean): String {
    val t = zonedTime(alarmMillis)
    val time = "${hourText(t.hour, use24h)}:${two(t.minute)}"
    return if (alarmMillis - nowMillis < 24 * 3_600_000L) time
    else "${formatSkeleton(alarmMillis, "EEE")} $time"
}

/** Noche "por reloj" cuando no hay sensor de luz (o aún no hay lectura). */
fun isNightHour(millis: Long): Boolean {
    val h = zonedTime(millis).hour
    return h >= 21 || h < 7
}

// --- Batería ------------------------------------------------------------------

@Immutable
data class BatteryInfo(val percent: Int, val plugged: Int, val charging: Boolean) {
    val isPlugged: Boolean get() = plugged != 0
    val isWireless: Boolean get() = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
}

fun Intent.toBatteryInfo(): BatteryInfo? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return BatteryInfo(
        percent = (level * 100f / scale).roundToInt(),
        plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

/** Lectura puntual del estado de la batería (intent "sticky" del sistema). */
fun readBattery(context: Context): BatteryInfo? =
    ContextCompat.registerReceiver(
        context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )?.toBatteryInfo()

@Composable
fun rememberBatteryInfo(): State<BatteryInfo?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<BatteryInfo?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state.value = intent.toBatteryInfo()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { state.value = it.toBatteryInfo() }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

// --- Próxima alarma -------------------------------------------------------------

fun nextAlarmMillis(context: Context): Long? =
    runCatching {
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
    }.getOrNull()

// --- Sensor de luz (modo noche automático) -------------------------------------

private const val DARK_ENTER_LUX = 6f   // por debajo: habitación oscura
private const val DARK_EXIT_LUX = 20f   // por encima: vuelve el color normal
private const val LIGHT_DEBOUNCE_MS = 2500L

/**
 * true = habitación oscura; null = sin sensor de luz (o sin lectura todavía).
 * Con histéresis y una espera de 2,5 s para que una mano o una sombra no
 * hagan parpadear el modo noche.
 */
@Composable
fun rememberRoomIsDark(enabled: Boolean): State<Boolean?> {
    val context = LocalContext.current
    return produceState<Boolean?>(initialValue = null, enabled, context) {
        if (!enabled) return@produceState
        val sm = context.getSystemService(SensorManager::class.java) ?: return@produceState
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return@produceState
        val luxFlow = callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(event.values[0])
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { sm.unregisterListener(listener) }
        }
        var dark: Boolean? = null
        var pendingSince = 0L
        luxFlow.collectLatest { lux ->
            val cur = dark
            if (cur == null) {
                dark = lux < DARK_ENTER_LUX
                value = dark
                return@collectLatest
            }
            val wantDark = if (cur) lux < DARK_EXIT_LUX else lux < DARK_ENTER_LUX
            if (wantDark == cur) {
                pendingSince = 0L
                return@collectLatest
            }
            val now = SystemClock.elapsedRealtime()
            if (pendingSince == 0L) pendingSince = now
            val remaining = LIGHT_DEBOUNCE_MS - (now - pendingSince)
            if (remaining > 0) delay(remaining)
            dark = wantDark
            value = wantDark
            pendingSince = 0L
        }
    }
}

// --- Protección contra marcas en OLED -------------------------------------------

private val BurnInSteps = listOf(
    0 to 0, 6 to -4, -5 to 5, 4 to 7, -7 to -5, 8 to 2, -3 to -8, 2 to 8, -8 to 1,
)

/** Desplaza el contenido unos dp cada minuto, con una transición lenta. */
@Composable
fun burnInModifier(enabled: Boolean): Modifier {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            step = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(60_000L)
            step = (step + 1) % BurnInSteps.size
        }
    }
    val (tx, ty) = BurnInSteps[step]
    val x = animateDpAsState(tx.dp, tween(3000), label = "burnInX")
    val y = animateDpAsState(ty.dp, tween(3000), label = "burnInY")
    return Modifier.offset { IntOffset(x.value.roundToPx(), y.value.roundToPx()) }
}
