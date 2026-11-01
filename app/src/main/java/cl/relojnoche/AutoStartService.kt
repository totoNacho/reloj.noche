package cl.relojnoche

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Servicio en primer plano que vigila la carga y abre el reloj solo, al estilo
 * NightDream. Es más fiable que un BroadcastReceiver declarado en el manifiesto
 * porque queda VIVO en segundo plano, así que el evento de carga no se pierde
 * aunque el sistema restrinja los broadcasts implícitos.
 *
 * Coste (inevitable en Android moderno): muestra una notificación permanente
 * discreta mientras está activo. Trae un botón para apagarlo.
 */
class AutoStartService : Service() {

    // Registrado en runtime: un receiver vivo dentro del servicio SÍ recibe el
    // evento de carga de forma fiable (a diferencia del declarado en manifest).
    private val chargeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> if (shouldAutoShow(context)) launchClockAuto(context)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(
            this, chargeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SettingsStore.update { it.copy(autoStart = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        // Si al arrancar ya está cargando y corresponde, mostrar de una.
        if (shouldAutoShow(this)) launchClockAuto(this)
        // START_STICKY: el sistema intenta recrearlo si lo mata.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(chargeReceiver) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Inicio automático",
                NotificationManager.IMPORTANCE_MIN,  // discreto: sin sonido ni cabecera
            ).apply { description = "Mantiene el reloj listo para abrirse al cargar" }
            nm.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutoStartService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reloj de Noche activo")
            .setContentText("Se abrirá solo al cargar. Toca para ajustes.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .addAction(0, "Desactivar", stop)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "auto_start"
        const val ACTION_STOP = "cl.relojnoche.STOP_AUTOSTART"

        /** Arranca el servicio si el inicio automático está activo. */
        fun startIfEnabled(context: Context) {
            if (!SettingsStore.current.autoStart) return
            val intent = Intent(context, AutoStartService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoStartService::class.java))
        }
    }
}
