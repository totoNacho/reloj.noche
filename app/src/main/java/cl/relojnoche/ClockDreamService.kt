package cl.relojnoche

import android.service.dreams.DreamService
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Protector de pantalla ("Daydream"). Android lo inicia solo cuando el
 * teléfono está cargando y la pantalla se iba a apagar, y lo muestra sobre la
 * pantalla de bloqueo: es lo más parecido al StandBy de iOS.
 *
 * Es interactivo: deslizar cambia de estilo y doble toque (o la X) despierta
 * el teléfono. Compose necesita un Lifecycle y un SavedStateRegistry, que un
 * Service no trae, así que los proveemos aquí.
 */
class ClockDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true

        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ClockDreamService)
            setViewTreeSavedStateRegistryOwner(this@ClockDreamService)
            setContent {
                ClockScreen(
                    isDream = true,
                    onExit = { wakeUp() },
                    onOpenSettings = null,
                    onBrightness = { applyBrightness(it) },
                )
            }
        }
        setContentView(view)
        window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val settings = SettingsStore.current
        val battery = readBattery(this)
        // Si no está enchufado, esto es una vista previa desde Ajustes: mostrar
        // siempre, sin aplicar los filtros de carga ni de horario.
        val isPreview = battery == null || !battery.isPlugged

        if (!isPreview) {
            // "Solo con carga inalámbrica": descartar solo si está claramente
            // por cable (hay conector y NO es inalámbrico). Si la carga está
            // pausada (plugged=0) no lo tratamos como cable.
            if (settings.onlyWireless && battery!!.isPlugged && !battery.isWireless) {
                finish()
                return
            }
            // Franja horaria + días: fuera del horario elegido, no mostrarse.
            if (!settings.isWithinSchedule(System.currentTimeMillis())) {
                finish()
                return
            }
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun applyBrightness(value: Float?) {
        val w = window ?: return
        w.attributes = w.attributes.apply {
            screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}
