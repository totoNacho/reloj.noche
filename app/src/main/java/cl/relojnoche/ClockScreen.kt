package cl.relojnoche

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

/**
 * Pantalla del reloj, compartida por la app (ClockActivity) y el protector de
 * pantalla (ClockDreamService).
 *
 * - Deslizar a los lados: cambia de estilo (se recuerda).
 * - Un toque: muestra/oculta los controles.
 * - Doble toque: sale.
 *
 * @param onBrightness brillo pedido para la ventana (null = el del sistema).
 */
@Composable
fun ClockScreen(
    isDream: Boolean,
    onExit: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onBrightness: (Float?) -> Unit,
) {
    val settings by SettingsStore.state.collectAsState()
    val context = LocalContext.current
    val minute by rememberMinuteTick()

    // Modo noche: rojo tenue + brillo bajo.
    val roomDark by rememberRoomIsDark(enabled = settings.nightMode == NightMode.AUTO)
    val night = when (settings.nightMode) {
        NightMode.ALWAYS -> true
        NightMode.NEVER -> false
        NightMode.AUTO -> roomDark ?: isNightHour(minute)
    }
    // Brillo bajo SIEMPRE que el reloj esté activo (no solo en modo noche);
    // en noche baja aún más. Esto evita el calentamiento por pantalla a tope.
    val brightness = settings.effectiveBrightness(night)
    val brightnessCallback by rememberUpdatedState(onBrightness)
    LaunchedEffect(brightness) { brightnessCallback(brightness) }

    val colors = animatedFaceColors(faceColors(settings.colorIndex, night))
    val battery by rememberBatteryInfo()
    val nextAlarm = remember(minute, settings.showInfo) {
        if (settings.showInfo) nextAlarmMillis(context) else null
    }
    val info = ClockInfo(battery, nextAlarm)

    val styles = ClockStyle.entries
    val pagerState = rememberPagerState(initialPage = settings.style.ordinal) { styles.size }

    var overlayVisible by remember { mutableStateOf(!isDream) }
    var overlayPing by remember { mutableIntStateOf(0) }
    LaunchedEffect(overlayVisible, overlayPing) {
        if (overlayVisible) {
            delay(4500)
            overlayVisible = false
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            SettingsStore.update { it.copy(style = styles[page]) }
        }
    }
    LaunchedEffect(pagerState) {
        // Al deslizar, mostrar un momento el nombre del estilo.
        snapshotFlow { pagerState.currentPage }.drop(1).collect {
            overlayVisible = true
            overlayPing++
        }
    }

    val exit by rememberUpdatedState(onExit)
    val burnIn = burnInModifier(settings.burnInShift)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                overlayVisible = !overlayVisible
                                overlayPing++
                            },
                            onDoubleTap = { exit() },
                        )
                    },
            ) {
                ClockFace(styles[page], settings, colors, info, Modifier.fillMaxSize().then(burnIn))
            }
        }

        // Atenuación extra: capa negra encima del reloj para oscurecer más allá
        // del brillo mínimo del hardware. No tapa los controles (van después).
        if (settings.dimLevel > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = settings.dimLevel)),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(450)),
            modifier = Modifier.fillMaxSize(),
        ) {
            ClockOverlay(
                current = pagerState.currentPage,
                count = styles.size,
                label = styles[pagerState.currentPage].label,
                color = colors.secondary,
                onExit = onExit,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun animatedFaceColors(target: FaceColors): FaceColors {
    val spec = tween<Color>(1200)
    return FaceColors(
        primary = animateColorAsState(target.primary, spec, label = "primary").value,
        secondary = animateColorAsState(target.secondary, spec, label = "secondary").value,
        faint = animateColorAsState(target.faint, spec, label = "faint").value,
        seconds = animateColorAsState(target.seconds, spec, label = "seconds").value,
    )
}

@Composable
private fun ClockOverlay(
    current: Int,
    count: Int,
    label: String,
    color: Color,
    onExit: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(16.dp),
    ) {
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onOpenSettings != null) {
                OverlayButton(Icons.Filled.Settings, "Ajustes", color, onOpenSettings)
            }
            OverlayButton(Icons.Filled.Close, "Salir del reloj", color, onExit)
        }
        Column(
            Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(count) { i ->
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i == current) color else color.copy(alpha = 0.25f)),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                color = color,
                style = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
            )
            Text(
                "Desliza para cambiar de estilo · Toca dos veces para salir",
                color = color.copy(alpha = 0.45f),
                style = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, description: String, color: Color, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f)),
    ) {
        Icon(icon, contentDescription = description, tint = color)
    }
}
