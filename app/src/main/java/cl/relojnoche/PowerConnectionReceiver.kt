package cl.relojnoche

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Inicio automático: cuando el teléfono se conecta a la carga, abre el reloj
 * sin necesidad de una rutina externa.
 *
 * Android no permite que una app en segundo plano lance una pantalla sola, SALVO
 * que tenga el permiso "mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW). Por eso
 * esta función solo actúa si el usuario activó el inicio automático Y concedió
 * ese permiso. Si no, no hace nada (y la app sigue funcionando por protector de
 * pantalla o por rutina de Samsung).
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return

        SettingsStore.init(context)
        val settings = SettingsStore.current
        if (!settings.autoStart) return
        if (!canDrawOverlays(context)) return

        // Respetar la franja horaria y los días elegidos.
        if (!settings.isWithinSchedule(System.currentTimeMillis())) return

        // Respetar el umbral de batería (si se configuró) y "solo inalámbrica".
        val battery = readBattery(context)
        if (settings.autoStartBatteryMin > 0 &&
            battery != null && battery.percent < settings.autoStartBatteryMin
        ) {
            return
        }
        if (settings.onlyWireless && battery != null && battery.isPlugged && !battery.isWireless) {
            return
        }

        val launch = Intent(context, ClockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ClockActivity.EXTRA_AUTO, true)
        }
        runCatching { context.startActivity(launch) }
    }
}

/** true si la app puede dibujarse sobre otras apps (permiso de superposición). */
fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
