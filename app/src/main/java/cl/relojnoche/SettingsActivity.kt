package cl.relojnoche

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Ajustes del reloj y guía para activarlo como protector de pantalla. */
class SettingsActivity : ComponentActivity() {

    private var dreamStatus by mutableStateOf<DreamStatus?>(null)
    // Cambia al volver a la pantalla, para re-evaluar el permiso de superposición.
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        SettingsStore.update { it.copy(setupSeen = true) }
        setContent {
            RelojTheme {
                // Leer resumeTick aquí fuerza recomponer al volver de Ajustes.
                @Suppress("UNUSED_EXPRESSION") resumeTick
                SettingsScreen(
                    dreamStatus = dreamStatus,
                    onOpenClock = ::openClock,
                    onOpenDreamSettings = ::openDreamSettings,
                    onPreviewBrightness = ::previewBrightness,
                    onRequestOverlay = ::requestOverlayPermission,
                    onIgnoreBatteryOptimization = ::requestIgnoreBatteryOptimization,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dreamStatus = DreamStatus.read(this)
        resumeTick++
    }

    /** Pide excluir la app de la optimización de batería (clave en Samsung). */
    private fun requestIgnoreBatteryOptimization() {
        // Primero intenta el diálogo directo; si no, abre la lista del sistema.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Lleva al usuario a la pantalla del sistema para conceder "mostrar sobre otras apps". */
    private fun requestOverlayPermission() {
        if (canDrawOverlays(this)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName"),
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    private fun openClock() {
        startActivity(Intent(this, ClockActivity::class.java))
        finish()
    }

    /** Ajustes > Pantalla > Protector de pantalla (con plan B si el fabricante no lo expone). */
    private fun openDreamSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_DREAM_SETTINGS),
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    /** Mientras se mueve la barra de brillo nocturno, la pantalla toma ese brillo. */
    private fun previewBrightness(value: Float?) {
        window.attributes = window.attributes.apply {
            screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}

/** Estado del protector de pantalla, si el sistema deja leerlo. */
data class DreamStatus(val enabled: Boolean, val ours: Boolean, val whenCharging: Boolean) {
    val ready: Boolean get() = enabled && ours && whenCharging

    companion object {
        fun read(context: Context): DreamStatus? = runCatching {
            val cr = context.contentResolver
            val enabled = Settings.Secure.getInt(cr, "screensaver_enabled", -1)
            val components = Settings.Secure.getString(cr, "screensaver_components")
            val onCharge = Settings.Secure.getInt(cr, "screensaver_activate_on_sleep", -1)
            if (enabled == -1 && components == null) {
                null
            } else {
                DreamStatus(
                    enabled = enabled == 1,
                    ours = components?.contains(context.packageName) == true,
                    whenCharging = onCharge != 0,
                )
            }
        }.getOrNull()
    }
}

private val SettingsColors = darkColorScheme(
    primary = Color(0xFFFF9F0A),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2A10),
    onPrimaryContainer = Color(0xFFFFD08A),
    secondaryContainer = Color(0xFF3A2A10),
    onSecondaryContainer = Color(0xFFFFD08A),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    outline = Color(0xFF48484A),
    outlineVariant = Color(0xFF2C2C2E),
)

@Composable
fun RelojTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SettingsColors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    dreamStatus: DreamStatus?,
    onOpenClock: () -> Unit,
    onOpenDreamSettings: () -> Unit,
    onPreviewBrightness: (Float?) -> Unit,
    onRequestOverlay: () -> Unit,
    onIgnoreBatteryOptimization: () -> Unit,
) {
    val s by SettingsStore.state.collectAsState()
    val context = LocalContext.current
    fun set(transform: (ClockSettings) -> ClockSettings) = SettingsStore.update(transform)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Reloj de Noche") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Vista previa en vivo del estilo elegido.
            val battery by rememberBatteryInfo()
            val alarm = remember { nextAlarmMillis(context) }
            val shape = RoundedCornerShape(22.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(shape)
                    .background(Color.Black)
                    .border(1.dp, Color(0xFF2C2C2E), shape),
            ) {
                key(s.style) {
                    ClockFace(
                        s.style, s,
                        faceColors(s.colorIndex),
                        ClockInfo(battery, alarm),
                        Modifier.fillMaxSize(),
                    )
                }
            }
            Button(onClick = onOpenClock, modifier = Modifier.fillMaxWidth()) {
                Text("Abrir el reloj ahora")
            }

            Section("Estilo") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                    val styles = ClockStyle.entries
                    styles.forEachIndexed { i, st ->
                        SegmentedButton(
                            selected = s.style == st,
                            onClick = { set { it.copy(style = st) } },
                            shape = SegmentedButtonDefaults.itemShape(i, styles.size),
                        ) { Text(st.label) }
                    }
                }
                Hint("En el reloj también puedes cambiar de estilo deslizando hacia los lados.")
            }

            Section("Color") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Palette.forEachIndexed { i, c ->
                        val selected = s.colorIndex == i
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    set { it.copy(colorIndex = i) }
                                }
                                .semantics { contentDescription = c.name }
                                .border(
                                    2.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape,
                                )
                                .padding(5.dp)
                                .clip(CircleShape)
                                .background(c.color),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check, contentDescription = null,
                                    tint = if (c.color.luminance() > 0.5f) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }

            }

            Section("Pantalla") {
                SwitchRow("Formato 24 horas", null, s.use24h) { v -> set { it.copy(use24h = v) } }
                SwitchRow("Segundos en el reloj digital", null, s.digitalSeconds) { v ->
                    set { it.copy(digitalSeconds = v) }
                }
                SwitchRow("Segundero en el analógico", null, s.analogSeconds) { v ->
                    set { it.copy(analogSeconds = v) }
                }
                SwitchRow(
                    "Segundero suave",
                    "Movimiento continuo. Consume más batería y calienta; déjalo apagado para un tictac normal",
                    s.smoothSeconds,
                ) { v -> set { it.copy(smoothSeconds = v) } }
                SwitchRow("Mostrar la fecha", null, s.showDate) { v -> set { it.copy(showDate = v) } }
                SwitchRow("Mostrar batería y próxima alarma", null, s.showInfo) { v ->
                    set { it.copy(showInfo = v) }
                }
                SwitchRow(
                    "Proteger la pantalla",
                    "Mueve el reloj unos milímetros cada minuto para que no quede marcado en pantallas OLED",
                    s.burnInShift,
                ) { v -> set { it.copy(burnInShift = v) } }
            }

            Section("Brillo") {
                BrightnessSlider(
                    "Brillo de la pantalla",
                    s.brightness, BRIGHTNESS_MIN..BRIGHTNESS_MAX,
                    onPreview = onPreviewBrightness,
                ) { v -> set { it.copy(brightness = v) } }
                Hint("Un reloj de velador no necesita la pantalla a tope. Bajarlo evita que el teléfono se caliente.")
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                BrightnessSlider(
                    "Brillo en modo noche",
                    s.nightBrightness, NIGHT_BRIGHTNESS_MIN..NIGHT_BRIGHTNESS_MAX,
                    onPreview = onPreviewBrightness,
                ) { v -> set { it.copy(nightBrightness = v) } }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val percent = (s.dimLevel / DIM_MAX * 100).roundToInt()
                    Text("Oscurecer más: $percent %", style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = s.dimLevel,
                        onValueChange = { v -> set { it.copy(dimLevel = v) } },
                        valueRange = 0f..DIM_MAX,
                    )
                    Text(
                        "Capa oscura encima del reloj, por si el brillo mínimo del teléfono sigue siendo alto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("Modo ahorro") {
                SwitchRow(
                    "Bajar el brillo si no lo toco",
                    "Pasado un tiempo sin tocar la pantalla, el brillo baja al mínimo y el reloj se ve más apagado. Un toque lo devuelve a la normalidad",
                    s.saverEnabled,
                ) { v -> set { it.copy(saverEnabled = v) } }
                if (s.saverEnabled) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            "Entrar al modo ahorro después de: ${s.saverAfterMin} min",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Slider(
                            value = s.saverAfterMin.toFloat(),
                            onValueChange = { v -> set { it.copy(saverAfterMin = v.roundToInt()) } },
                            valueRange = SAVER_MIN_MINUTES.toFloat()..SAVER_MAX_MINUTES.toFloat(),
                            steps = SAVER_MAX_MINUTES - SAVER_MIN_MINUTES - 1,
                        )
                    }
                }
                Hint("La pantalla sigue encendida; solo se atenúa. Así gasta menos y calienta menos.")
            }

            Section("Modo noche") {
                Column(Modifier.selectableGroup()) {
                    NightMode.entries.forEach { mode ->
                        RadioRow(mode.label, mode.description, s.nightMode == mode) {
                            set { it.copy(nightMode = mode) }
                        }
                    }
                }
            }

            Section("Horario") {
                SwitchRow(
                    "Solo en un horario",
                    "El reloj aparece al cargar solo dentro de la franja y los días elegidos",
                    s.scheduleEnabled,
                ) { v -> set { it.copy(scheduleEnabled = v) } }
                if (s.scheduleEnabled) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TimeField(
                            "Desde", s.scheduleStart, s.use24h, Modifier.weight(1f),
                        ) { v -> set { it.copy(scheduleStart = v) } }
                        TimeField(
                            "Hasta", s.scheduleEnd, s.use24h, Modifier.weight(1f),
                        ) { v -> set { it.copy(scheduleEnd = v) } }
                    }
                    if (s.scheduleEnd <= s.scheduleStart) {
                        Hint("La franja cruza la medianoche (p. ej. 22:00 de un día a 07:00 del siguiente).")
                    }
                    DaysPicker(s.scheduleDays) { v -> set { it.copy(scheduleDays = v) } }
                    Hint(
                        "El horario solo filtra cuándo aparece el reloj al CARGAR (protector de " +
                            "pantalla o inicio automático). Si abres la app desde el ícono, se " +
                            "muestra siempre, sin importar la hora.",
                    )
                }
            }

            Section("Inicio automático") {
                val overlayGranted = canDrawOverlays(context)
                SwitchRow(
                    "Abrir solo al conectar la carga",
                    "Sin necesidad de una rutina. Requiere el permiso \"mostrar sobre otras apps\"",
                    s.autoStart,
                ) { v ->
                    set { it.copy(autoStart = v) }
                    if (v) {
                        if (!canDrawOverlays(context)) onRequestOverlay()
                        AutoStartService.startIfEnabled(context)
                    } else {
                        AutoStartService.stop(context)
                    }
                }
                if (s.autoStart && !overlayGranted) {
                    Text(
                        "Falta el permiso \"mostrar sobre otras apps\". Sin él, la app no puede " +
                            "abrirse sola al cargar.",
                        color = Color(0xFFFF9F0A),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    FilledTonalButton(
                        onClick = onRequestOverlay,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text("Conceder permiso") }
                }
                if (s.autoStart && overlayGranted) {
                    Text(
                        "✓ Activo. El reloj se abrirá al conectar la carga" +
                            (if (s.scheduleEnabled) ", dentro del horario elegido." else ".") +
                            " Verás una notificación fija mientras esté activo (Android lo exige).",
                        color = Color(0xFF30D158),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    FilledTonalButton(
                        onClick = onIgnoreBatteryOptimization,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text("Excluir de la optimización de batería") }
                    Hint("En Samsung, sin esta exclusión el sistema puede cerrar el servicio y dejar de abrir el reloj.")
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        val label = if (s.autoStartBatteryMin == 0) "Sin límite"
                                    else "${s.autoStartBatteryMin} %"
                        Text("Solo si la batería supera: $label", style = MaterialTheme.typography.bodyLarge)
                        Slider(
                            value = s.autoStartBatteryMin.toFloat(),
                            onValueChange = { v -> set { it.copy(autoStartBatteryMin = v.roundToInt()) } },
                            valueRange = 0f..95f,
                            steps = 18,
                        )
                    }
                }
                Hint(
                    "El inicio automático abre el reloj apenas conectas la carga. Es la " +
                        "alternativa a la rutina de Samsung. Al desconectar, el reloj se cierra " +
                        "solo (si tienes activado \"Cerrar al sacarlo del cargador\").",
                )
            }

            Section("Al cargar el teléfono") {
                DreamStatusLine(dreamStatus)
                Text(
                    "1. Toca el botón de abajo (Ajustes > Pantalla > Protector de pantalla).\n" +
                        "2. Activa el protector y elige \"Reloj de Noche\".\n" +
                        "3. Si aparece \"Cuándo iniciar\", elige \"Mientras se carga\".\n\n" +
                        "Listo: al poner el teléfono en el cargador inalámbrico y apagarse la " +
                        "pantalla, aparece el reloj sobre la pantalla de bloqueo.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                FilledTonalButton(
                    onClick = onOpenDreamSettings,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text("Abrir ajustes del protector de pantalla") }
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    "Solo con carga inalámbrica",
                    "Si lo cargas con cable, el protector no se muestra",
                    s.onlyWireless,
                ) { v -> set { it.copy(onlyWireless = v) } }
                SwitchRow(
                    "Cerrar el reloj al sacarlo del cargador",
                    "Para el reloj abierto desde el ícono de la app",
                    s.closeOnUnplug,
                ) { v -> set { it.copy(closeOnUnplug = v) } }
                Hint(
                    "¿El protector no aparece al cargar inalámbricamente con el teléfono " +
                        "bloqueado? En Samsung, con \"Proteger batería\" activado (carga hasta " +
                        "80-85 %), al llegar al tope la carga se pausa y el sistema deja de " +
                        "considerarlo \"cargando\", así que no lanza el protector. No es la app.\n\n" +
                        "Solución recomendada (más fiable): abre \"Modos y rutinas\", crea una " +
                        "rutina con\n" +
                        "  • Si: Carga > conectado / inalámbrico\n" +
                        "  • Entonces: Abrir aplicación > Reloj de Noche\n" +
                        "Así el reloj se abre apenas lo pones en el cargador, respeta el horario " +
                        "que elegiste aquí y mantiene la pantalla encendida mientras siga cargando " +
                        "(aunque la carga esté en pausa). Al retirarlo se cierra solo.",
                )
            }

            Section("Actualizaciones") {
                UpdateSection()
            }

            Text(
                "Versión ${BuildConfig.VERSION_NAME} · Letra Nunito (SIL Open Font License)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp).align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** Estados posibles del chequeo de actualización. */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateChecker.UpdateInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Sección de auto-actualización: busca en GitHub Releases y ofrece instalar. */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    // Comprobación automática al abrir Ajustes: si hay versión nueva, muestra el
    // aviso sin que el usuario toque nada. En silencio: si no hay novedad o
    // falla la red, no molesta (se queda en Idle con el botón normal).
    LaunchedEffect(Unit) {
        if (state != UpdateState.Idle) return@LaunchedEffect
        val info = withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME) }.getOrNull()
        }
        if (info != null && state == UpdateState.Idle) state = UpdateState.Available(info)
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        when (val st = state) {
            is UpdateState.Available -> {
                Text(
                    "Hay una versión nueva: ${st.info.versionName}",
                    color = Color(0xFF30D158),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (st.info.notes.isNotBlank()) {
                    Text(
                        st.info.notes.take(300),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        state = UpdateState.Downloading(0)
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    UpdateChecker.downloadAndInstall(context, st.info.apkUrl) { p ->
                                        state = UpdateState.Downloading(p)
                                    }
                                }
                                // El instalador del sistema toma el control aquí.
                                state = UpdateState.Idle
                            } catch (e: Exception) {
                                state = UpdateState.Error(e.message ?: "Error al descargar")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Descargar e instalar ${st.info.versionName}") }
            }

            is UpdateState.Downloading -> {
                Text("Descargando… ${st.percent}%", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { st.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                Button(
                    onClick = {
                        state = UpdateState.Checking
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME) }
                            }
                            state = result.fold(
                                onSuccess = { info ->
                                    if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
                                },
                                onFailure = { UpdateState.Error(it.message ?: "No se pudo comprobar") },
                            )
                        }
                    },
                    enabled = st !is UpdateState.Checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (st is UpdateState.Checking) "Buscando…" else "Buscar actualización")
                }
                val msg = when (st) {
                    is UpdateState.UpToDate -> "Ya tienes la última versión."
                    is UpdateState.Error -> "No se pudo comprobar: ${st.message}"
                    else -> null
                }
                if (msg != null) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** Slider de brillo con porcentaje; muestra el brillo en vivo mientras lo mueves. */
@Composable
private fun BrightnessSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onPreview: (Float?) -> Unit,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        val percent = max(1, (value / range.endInclusive * 100).roundToInt())
        Text("$title: $percent %", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = { v ->
                onChange(v)
                onPreview(v)
            },
            onValueChangeFinished = { onPreview(null) },
            valueRange = range,
        )
    }
}

/** Muestra una hora (p. ej. "22:00") que al tocarla abre un selector. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    minutesOfDay: Int,
    use24h: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val hour = minutesOfDay / 60
    val minute = minutesOfDay % 60
    OutlinedButton(
        onClick = { showPicker = true },
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatClock(hour, minute, use24h),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (showPicker) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = use24h)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour * 60 + state.minute)
                    showPicker = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancelar") } },
            title = { Text(label) },
            text = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = state)
                }
            },
        )
    }
}

/** "22:00" en 24 h, o "10:00 p. m." en 12 h. */
private fun formatClock(hour: Int, minute: Int, use24h: Boolean): String {
    if (use24h) return "${two(hour)}:${two(minute)}"
    val h12 = ((hour + 11) % 12) + 1
    val suffix = if (hour < 12) "a. m." else "p. m."
    return "$h12:${two(minute)} $suffix"
}

/** Siete chips (L M M J V S D) para elegir los días activos. */
@Composable
private fun DaysPicker(days: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DayLabels.forEachIndexed { i, label ->
            val selected = (days shr i) and 1 == 1
            val bg = if (selected) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.surfaceContainerHigh
            val fg = if (selected) MaterialTheme.colorScheme.onPrimary
                     else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(bg)
                    .toggleable(
                        value = selected,
                        role = Role.Checkbox,
                        onValueChange = { on ->
                            val next = if (on) days or (1 shl i) else days and (1 shl i).inv()
                            // No dejar los 7 días apagados: sin días, el horario nunca se cumple.
                            if (next and ALL_DAYS != 0) onChange(next)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DreamStatusLine(status: DreamStatus?) {
    if (status == null) return
    val (text, color) = when {
        status.ready -> "✓ Activado: el reloj aparecerá al cargar" to Color(0xFF30D158)
        !status.enabled -> "El protector de pantalla está desactivado" to Color(0xFFFF9F0A)
        !status.ours -> "Hay otro protector elegido; cámbialo a \"Reloj de Noche\"" to Color(0xFFFF9F0A)
        else -> "Revisa \"Cuándo iniciar\": debe ser \"Mientras se carga\"" to Color(0xFFFF9F0A)
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
