package app.aitranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Hält die App während einer Übersetzung als Vordergrunddienst am Leben: Android beendet sonst bei gesperrtem Bildschirm
 * oder nach dem Wechsel in eine andere App den Prozess bzw. das WLAN. Die Übersetzung selbst läuft weiter im ViewModel
 * (App-weit, siehe [TranslatorApplication]); der Dienst sorgt nur für Priorität, CPU- und WLAN-Sperre und die Benachrichtigung.
 */
class TranslationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(this, "Übersetzung läuft…", null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        acquireLocks()
        return START_NOT_STICKY // wird die App beendet, beginnt nichts von selbst: "Fortsetzen" bleibt Sache des Benutzers
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF hält das WLAN bei ausgeschaltetem Bildschirm; die Alternative gilt nur bei Bildschirm an
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:translation").apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS) // Sicherheitsnetz: nie länger als 8 Stunden
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:translation")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "translation"
        private const val NOTIFICATION_ID = 1
        private const val MAX_HOLD_MS = 8 * 60 * 60 * 1000L
        private const val TAG = "TranslationService"

        /** Startet den Dienst. Schlägt das fehl (z. B. System verbietet es), läuft die Übersetzung trotzdem, nur ohne Schutz. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TranslationService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Vordergrunddienst konnte nicht gestartet werden", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TranslationService::class.java))
        }

        /** Aktualisiert Text und Fortschrittsbalken der Benachrichtigung; [percent] null = unbestimmt. */
        fun update(context: Context, text: String, percent: Int?) {
            val manager = context.getSystemService(NotificationManager::class.java)
            try {
                manager.notify(NOTIFICATION_ID, buildNotification(context, text, percent))
            } catch (e: SecurityException) {
                // Benachrichtigungen verweigert: der Dienst läuft trotzdem
            }
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Übersetzung", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Zeigt, dass eine Übersetzung läuft"
                    },
                )
            }
        }

        private fun buildNotification(context: Context, text: String, percent: Int?): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AI-Translator")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setProgress(100, percent ?: 0, percent == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        }
    }
}
