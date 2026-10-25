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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.max
import kotlin.math.roundToInt

/** Ajustes del reloj y guía para activarlo como protector de pantalla. */
class SettingsActivity : ComponentActivity() {

    private var dreamStatus by mutableStateOf<DreamStatus?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        SettingsStore.update { it.copy(setupSeen = true) }
        setContent {
            RelojTheme {
                SettingsScreen(
                    dreamStatus = dreamStatus,
                    onOpenClock = ::openClock,
                    onOpenDreamSettings = ::openDreamSettings,
                    onPreviewBrightness = ::previewBrightness,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dreamStatus = DreamStatus.read(this)
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
                        faceColors(s.colorIndex, night = s.nightMode == NightMode.ALWAYS),
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
                if (s.nightMode != NightMode.NEVER) {
                    Hint("En modo noche el reloj se vuelve rojo tenue, sin importar el color.")
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

            Section("Modo noche") {
                Column(Modifier.selectableGroup()) {
                    NightMode.entries.forEach { mode ->
                        RadioRow(mode.label, mode.description, s.nightMode == mode) {
                            set { it.copy(nightMode = mode) }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val percent = max(1, (s.nightBrightness * 100).roundToInt())
                    Text("Brillo de noche: $percent %", style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = s.nightBrightness,
                        onValueChange = { v ->
                            set { it.copy(nightBrightness = v) }
                            onPreviewBrightness(v)
                        },
                        onValueChangeFinished = { onPreviewBrightness(null) },
                        valueRange = NIGHT_BRIGHTNESS_MIN..NIGHT_BRIGHTNESS_MAX,
                    )
                    Text(
                        "Mientras mueves la barra, la pantalla toma ese brillo para que lo pruebes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    "Si usas \"Proteger batería\" (carga hasta 80-85 %), Samsung puede no iniciar el " +
                        "protector cuando la carga está en pausa. Alternativa: en Modos y rutinas crea " +
                        "una rutina \"Si: Estado de carga (inalámbrica)\" → \"Entonces: Abrir app " +
                        "Reloj de Noche\". Abierto así, el reloj mantiene la pantalla encendida " +
                        "mientras siga en el cargador.",
                )
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
