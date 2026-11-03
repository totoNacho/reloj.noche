package cl.relojnoche

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Reloj a pantalla completa (lo que abre el icono de la app).
 *
 * - Se muestra sobre la pantalla de bloqueo, como el StandBy de iOS.
 * - Mientras el teléfono está en el cargador, la pantalla no se apaga.
 * - Al sacarlo de la base se cierra solo (si está activado), con un margen de
 *   gracia corto que reconfirma el estado para no cerrarse por un parpadeo.
 * - Pensado para abrirse también desde una rutina de "Modos y rutinas"
 *   (Si: carga inalámbrica -> Abrir Reloj de Noche), que en Samsung es más
 *   fiable que el protector de pantalla cuando "Proteger batería" está activo.
 */
class ClockActivity : ComponentActivity() {

    companion object {
        /** El reloj lo abrió la automatización (inicio automático / rutina). */
        const val EXTRA_AUTO = "cl.relojnoche.AUTO"
    }

    private val handler = Handler(Looper.getMainLooper())

    // Sondeo periódico del estado de carga. No dependemos solo del broadcast
    // ACTION_BATTERY_CHANGED porque en Samsung con carga inalámbrica NO siempre
    // llega al sacar el teléfono (la app quedaba abierta). Cada pocos segundos
    // revisamos directamente; si lleva varias lecturas seguidas fuera de la
    // base, cerramos. Así es fiable aunque el evento no se dispare.
    private val pollIntervalMs = 3_000L
    private val readingsToClose = 2       // ~2 lecturas fuera = ~3-6 s de gracia
    private var offChargerReadings = 0

    private val poll = object : Runnable {
        override fun run() {
            val plugged = readBattery(this@ClockActivity)?.isPlugged == true
            keepScreenOn(plugged)
            if (plugged) {
                offChargerReadings = 0
            } else {
                offChargerReadings++
                if (SettingsStore.current.closeOnUnplug && offChargerReadings >= readingsToClose) {
                    finish()
                    return
                }
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    // Refuerzo inmediato: si el evento SÍ llega, reaccionamos sin esperar al sondeo.
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val plugged = readBattery(context)?.isPlugged == true
            keepScreenOn(plugged)
            if (plugged) offChargerReadings = 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // La franja horaria SOLO se aplica cuando el reloj lo abre la
        // automatización (inicio automático o una rutina que pase EXTRA_AUTO).
        // Si lo abres tú desde el ícono, se muestra SIEMPRE, sin importar la
        // hora: quieres poder verlo cuando quieras. (Antes se cerraba también
        // al abrirlo a mano si estaba cargando; eso era un bug.)
        val settings = SettingsStore.current
        val launchedAuto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        if (launchedAuto && !settings.isWithinSchedule(System.currentTimeMillis())) {
            finish()
            return
        }

        showOverLockScreen()
        goImmersive()
        setContent {
            ClockScreen(
                isDream = false,
                onExit = { finish() },
                onOpenSettings = { openSettings() },
                onBrightness = { applyBrightness(it) },
            )
        }
        // La primera vez, mostrar los ajustes para explicar cómo activarlo al cargar.
        if (savedInstanceState == null && !SettingsStore.current.setupSeen) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, powerReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Arrancar el sondeo: fija la pantalla y vigila si sigue en la base.
        offChargerReadings = 0
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(powerReceiver) }
        handler.removeCallbacks(poll)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            goImmersive()
            // Reaplicar el brillo: al recuperar el foco el sistema puede haber
            // restaurado el brillo normal de la ventana.
            applyBrightness(lastBrightness)
        }
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private var lastBrightness: Float? = null

    private fun applyBrightness(value: Float?) {
        lastBrightness = value
        window.attributes = window.attributes.apply {
            screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Si el teléfono está bloqueado, pide desbloquear antes de abrir los ajustes. */
    private fun openSettings() {
        val open = { startActivity(Intent(this, SettingsActivity::class.java)) }
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open()
            })
        } else {
            open()
        }
    }
}
