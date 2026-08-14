package lt.vilniustech.basketball.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class RecordingForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        acquireWakeLock()
        ensureChannel()
        val openUi = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (sessionId.isBlank()) getString(R.string.status_recording)
                else getString(R.string.session_label, sessionId),
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    openUi,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ftwatch:recording").apply {
                acquire(60 * 60 * 1000L)
            }
        }.onFailure {
            Log.w(TAG, "wake lock failed: ${it.message}")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "RecordingFGS"
        private const val CHANNEL_ID = "ft_watch_recording"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_SESSION_ID = "session_id"

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
