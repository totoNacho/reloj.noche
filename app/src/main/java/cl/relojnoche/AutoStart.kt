package cl.relojnoche

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** true si la app puede dibujarse sobre otras apps (permiso de superposición). */
fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

/**
 * Decide si, en este momento, corresponde abrir el reloj automáticamente al
 * estar cargando, según los ajustes (permiso, horario, umbral de batería,
 * solo inalámbrica). Lo usan tanto el servicio como el receiver.
 */
fun shouldAutoShow(context: Context): Boolean {
    val settings = SettingsStore.current
    if (!settings.autoStart) return false
    if (!canDrawOverlays(context)) return false
    if (!settings.isWithinSchedule(System.currentTimeMillis())) return false

    val battery = readBattery(context) ?: return false
    if (!battery.isOnCharger) return false
    if (settings.autoStartBatteryMin > 0 && battery.percent < settings.autoStartBatteryMin) return false
    if (settings.onlyWireless && battery.isPlugged && !battery.isWireless) return false
    return true
}

/** Lanza el reloj como apertura automática (respeta la franja horaria). */
fun launchClockAuto(context: Context) {
    val launch = Intent(context, ClockActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(ClockActivity.EXTRA_AUTO, true)
    }
    runCatching { context.startActivity(launch) }
}
