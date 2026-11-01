package cl.relojnoche

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Respaldo del inicio automático por evento: si el sistema entrega el broadcast
 * de "carga conectada", abre el reloj. En muchos Samsung este broadcast NO
 * llega de forma fiable en segundo plano (el sistema mata el receiver), por eso
 * la vía principal es el AutoStartService (servicio en primer plano). Este
 * receiver queda como refuerzo y no estorba si el servicio ya está activo.
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        SettingsStore.init(context)
        if (shouldAutoShow(context)) launchClockAuto(context)
    }
}
