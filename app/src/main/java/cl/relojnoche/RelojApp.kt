package cl.relojnoche

import android.app.Application

/** Carga los ajustes una sola vez para la app, el reloj y el protector de pantalla. */
class RelojApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        // Si el inicio automático quedó activado, asegurar que el servicio corra.
        runCatching { AutoStartService.startIfEnabled(this) }
    }
}
