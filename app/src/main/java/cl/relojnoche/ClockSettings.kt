package cl.relojnoche

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estilos de reloj, en el mismo orden en que se pasan deslizando. */
enum class ClockStyle(val label: String) {
    DIGITAL("Digital"),
    ANALOG("Analógico"),
    FALLING("Caída"),
}

/** Cuándo usar el modo noche: mantiene el color elegido y baja aún más el brillo. */
enum class NightMode(val label: String, val description: String) {
    AUTO("Automático", "Cuando la habitación está oscura (sensor de luz)"),
    ALWAYS("Siempre", "Siempre con el brillo más bajo"),
    NEVER("Nunca", "Nunca bajar más el brillo"),
}

@Immutable
data class ClockSettings(
    val style: ClockStyle = ClockStyle.DIGITAL,
    val colorIndex: Int = 0,
    val use24h: Boolean = true,
    val digitalSeconds: Boolean = false,
    val analogSeconds: Boolean = true,
    val showDate: Boolean = true,
    val showInfo: Boolean = true,
    val nightMode: NightMode = NightMode.AUTO,
    /**
     * Brillo de la pantalla SIEMPRE que el reloj está activo (0..1 del brillo
     * del sistema). Bajo por defecto: un reloj de velador no necesita pantalla
     * a tope, y así no calienta ni encandila.
     */
    val brightness: Float = 0.10f,
    /** Brillo (más bajo) cuando además está en modo noche. */
    val nightBrightness: Float = 0.02f,
    /**
     * Atenuación extra: una capa negra encima del reloj (0 = nada, 1 = casi
     * negro). Sirve para oscurecer más allá del brillo mínimo del hardware,
     * que en algunos teléfonos sigue siendo alto.
     */
    val dimLevel: Float = 0.0f,
    /**
     * Segundero suave (~30 fps). Apagado por defecto: el barrido continuo
     * mantiene la pantalla redibujándose y es la causa principal de que el
     * teléfono se caliente. Apagado, el segundero avanza de a 1 segundo.
     */
    val smoothSeconds: Boolean = false,
    /** Mueve el reloj unos pocos dp cada minuto para no marcar pantallas OLED. */
    val burnInShift: Boolean = true,
    /** Protector de pantalla: no mostrarse si la carga es por cable. */
    val onlyWireless: Boolean = false,
    /** App: cerrar el reloj al sacar el teléfono del cargador. */
    val closeOnUnplug: Boolean = true,
    /** Protector de pantalla: mostrarse solo dentro de una franja horaria y días. */
    val scheduleEnabled: Boolean = false,
    /** Inicio de la franja, en minutos desde medianoche (0..1439). */
    val scheduleStart: Int = 22 * 60,
    /** Fin de la franja, en minutos desde medianoche. Si es <= inicio, cruza medianoche. */
    val scheduleEnd: Int = 7 * 60,
    /** Días activos como bitmask: bit 0 = lunes ... bit 6 = domingo. */
    val scheduleDays: Int = ALL_DAYS,
    val setupSeen: Boolean = false,
)

const val ALL_DAYS = 0b111_1111

/** true si el instante dado cae dentro de la franja y en un día activo. */
fun ClockSettings.isWithinSchedule(millis: Long): Boolean {
    if (!scheduleEnabled) return true
    val t = zonedTime(millis)
    val minutes = t.hour * 60 + t.minute
    // DayOfWeek: 1 = lunes ... 7 = domingo  ->  bit 0..6.
    val todayBit = t.dayOfWeek.value - 1
    val crossesMidnight = scheduleEnd <= scheduleStart
    val inWindow: Boolean
    // El día de referencia es el día en que EMPIEZA la franja. Así una franja
    // nocturna (p. ej. vie 22:00 -> sáb 07:00) cuenta como viernes.
    val startDayBit: Int
    if (!crossesMidnight) {
        inWindow = minutes in scheduleStart until scheduleEnd
        startDayBit = todayBit
    } else {
        val afterStart = minutes >= scheduleStart   // misma noche
        val beforeEnd = minutes < scheduleEnd       // madrugada del día siguiente
        inWindow = afterStart || beforeEnd
        // Si estamos en la madrugada, la franja empezó "ayer".
        startDayBit = if (beforeEnd && !afterStart) (todayBit + 6) % 7 else todayBit
    }
    if (!inWindow) return false
    return (scheduleDays shr startDayBit) and 1 == 1
}

/** Nombres cortos de los días, en orden lunes..domingo. */
val DayLabels = listOf("L", "M", "M", "J", "V", "S", "D")

const val NIGHT_BRIGHTNESS_MIN = 0.005f
const val NIGHT_BRIGHTNESS_MAX = 0.30f
const val BRIGHTNESS_MIN = 0.01f
const val BRIGHTNESS_MAX = 1.0f
const val DIM_MAX = 0.85f

/**
 * Brillo efectivo (0..1) a aplicar a la ventana según el estado actual.
 * En modo noche usa el menor entre el brillo normal y el nocturno.
 */
fun ClockSettings.effectiveBrightness(night: Boolean): Float =
    if (night) minOf(brightness, nightBrightness) else brightness

/**
 * Ajustes persistidos en SharedPreferences y expuestos como StateFlow, para
 * que el reloj (app y protector de pantalla) reaccione al instante.
 * Todo se usa desde el hilo principal.
 */
object SettingsStore {
    private const val PREFS = "reloj_noche"
    private const val K_STYLE = "style"
    private const val K_COLOR = "color"
    private const val K_24H = "use24h"
    private const val K_DIGITAL_SECONDS = "digital_seconds"
    private const val K_ANALOG_SECONDS = "analog_seconds"
    private const val K_DATE = "show_date"
    private const val K_INFO = "show_info"
    private const val K_NIGHT = "night_mode"
    private const val K_BRIGHTNESS = "brightness"
    private const val K_NIGHT_BRIGHTNESS = "night_brightness"
    private const val K_DIM = "dim_level"
    private const val K_SMOOTH_SECONDS = "smooth_seconds"
    private const val K_BURN_IN = "burn_in_shift"
    private const val K_ONLY_WIRELESS = "only_wireless"
    private const val K_CLOSE_UNPLUG = "close_on_unplug"
    private const val K_SCHED_ENABLED = "schedule_enabled"
    private const val K_SCHED_START = "schedule_start"
    private const val K_SCHED_END = "schedule_end"
    private const val K_SCHED_DAYS = "schedule_days"
    private const val K_SETUP_SEEN = "setup_seen"

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(ClockSettings())
    val state: StateFlow<ClockSettings> = _state.asStateFlow()
    val current: ClockSettings get() = _state.value

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val d = ClockSettings()
        _state.value = ClockSettings(
            style = enumOr(p.getString(K_STYLE, null), d.style),
            colorIndex = p.getInt(K_COLOR, d.colorIndex).coerceIn(0, Palette.lastIndex),
            // Por defecto, el mismo formato que usa el teléfono.
            use24h = p.getBoolean(K_24H, DateFormat.is24HourFormat(context)),
            digitalSeconds = p.getBoolean(K_DIGITAL_SECONDS, d.digitalSeconds),
            analogSeconds = p.getBoolean(K_ANALOG_SECONDS, d.analogSeconds),
            showDate = p.getBoolean(K_DATE, d.showDate),
            showInfo = p.getBoolean(K_INFO, d.showInfo),
            nightMode = enumOr(p.getString(K_NIGHT, null), d.nightMode),
            brightness = p.getFloat(K_BRIGHTNESS, d.brightness)
                .coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX),
            nightBrightness = p.getFloat(K_NIGHT_BRIGHTNESS, d.nightBrightness)
                .coerceIn(NIGHT_BRIGHTNESS_MIN, NIGHT_BRIGHTNESS_MAX),
            dimLevel = p.getFloat(K_DIM, d.dimLevel).coerceIn(0f, DIM_MAX),
            smoothSeconds = p.getBoolean(K_SMOOTH_SECONDS, d.smoothSeconds),
            burnInShift = p.getBoolean(K_BURN_IN, d.burnInShift),
            onlyWireless = p.getBoolean(K_ONLY_WIRELESS, d.onlyWireless),
            closeOnUnplug = p.getBoolean(K_CLOSE_UNPLUG, d.closeOnUnplug),
            scheduleEnabled = p.getBoolean(K_SCHED_ENABLED, d.scheduleEnabled),
            scheduleStart = p.getInt(K_SCHED_START, d.scheduleStart).coerceIn(0, 1439),
            scheduleEnd = p.getInt(K_SCHED_END, d.scheduleEnd).coerceIn(0, 1439),
            scheduleDays = (p.getInt(K_SCHED_DAYS, d.scheduleDays) and ALL_DAYS),
            setupSeen = p.getBoolean(K_SETUP_SEEN, d.setupSeen),
        )
    }

    fun update(transform: (ClockSettings) -> ClockSettings) {
        val old = _state.value
        val s = transform(old)
        if (s == old) return
        _state.value = s
        prefs?.edit()
            ?.putString(K_STYLE, s.style.name)
            ?.putInt(K_COLOR, s.colorIndex)
            ?.putBoolean(K_24H, s.use24h)
            ?.putBoolean(K_DIGITAL_SECONDS, s.digitalSeconds)
            ?.putBoolean(K_ANALOG_SECONDS, s.analogSeconds)
            ?.putBoolean(K_DATE, s.showDate)
            ?.putBoolean(K_INFO, s.showInfo)
            ?.putString(K_NIGHT, s.nightMode.name)
            ?.putFloat(K_BRIGHTNESS, s.brightness)
            ?.putFloat(K_NIGHT_BRIGHTNESS, s.nightBrightness)
            ?.putFloat(K_DIM, s.dimLevel)
            ?.putBoolean(K_SMOOTH_SECONDS, s.smoothSeconds)
            ?.putBoolean(K_BURN_IN, s.burnInShift)
            ?.putBoolean(K_ONLY_WIRELESS, s.onlyWireless)
            ?.putBoolean(K_CLOSE_UNPLUG, s.closeOnUnplug)
            ?.putBoolean(K_SCHED_ENABLED, s.scheduleEnabled)
            ?.putInt(K_SCHED_START, s.scheduleStart)
            ?.putInt(K_SCHED_END, s.scheduleEnd)
            ?.putInt(K_SCHED_DAYS, s.scheduleDays)
            ?.putBoolean(K_SETUP_SEEN, s.setupSeen)
            ?.apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}

// --- Colores ----------------------------------------------------------------

data class NamedColor(val name: String, val color: Color)

/** Colores del sistema de iOS en modo oscuro. */
val Palette = listOf(
    NamedColor("Blanco", Color(0xFFFFFFFF)),
    NamedColor("Rojo", Color(0xFFFF453A)),
    NamedColor("Naranja", Color(0xFFFF9F0A)),
    NamedColor("Amarillo", Color(0xFFFFD60A)),
    NamedColor("Verde", Color(0xFF30D158)),
    NamedColor("Menta", Color(0xFF66D4CF)),
    NamedColor("Cian", Color(0xFF64D2FF)),
    NamedColor("Azul", Color(0xFF0A84FF)),
    NamedColor("Índigo", Color(0xFF5E5CE6)),
    NamedColor("Morado", Color(0xFFBF5AF2)),
    NamedColor("Rosa", Color(0xFFFF375F)),
)

/** Colores ya resueltos para pintar una esfera. */
@Immutable
data class FaceColors(
    val primary: Color,    // dígitos y agujas
    val secondary: Color,  // fecha, batería, dos puntos, marcas de hora
    val faint: Color,      // marcas de minutos
    val seconds: Color,    // segundero
)

/**
 * Colores de la esfera, a partir del color elegido. El modo noche NO cambia el
 * color: solo baja el brillo de la pantalla (ver effectiveBrightness).
 */
fun faceColors(colorIndex: Int): FaceColors {
    val c = Palette.getOrElse(colorIndex) { Palette[0] }.color
    // Segundero naranja como en iOS; si el color elegido ya es cálido, rojo.
    val secondHand = if (colorIndex == 2 || colorIndex == 3) Color(0xFFFF453A) else Color(0xFFFF9F0A)
    return FaceColors(
        primary = c,
        secondary = c.copy(alpha = 0.62f),
        faint = c.copy(alpha = 0.25f),
        seconds = secondHand,
    )
}
