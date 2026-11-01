package cl.relojnoche

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reinicia el servicio de inicio automático tras encender o actualizar el teléfono. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                SettingsStore.init(context)
                AutoStartService.startIfEnabled(context)
            }
        }
    }
}
