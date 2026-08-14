package lt.vilniustech.basketball.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Keeps watch↔phone link alive: polls session + pings phone for active session. */
class WatchLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            scope.launch {
                SessionDataSync.readLatest(this@WatchLinkService)
                PhoneWifiRelay.pollSession(this@WatchLinkService)
                CloudSessionPoller.poll(this@WatchLinkService)
                WatchStatusSync.publishHeartbeat(this@WatchLinkService)
            }
            handler.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WatchCommands.init(applicationContext)
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.waiting_phone))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        PhoneWifiRelay.loadCachedIp(applicationContext)
        PhoneWifiRelay.resolvePhoneIpOnce(applicationContext)
        handler.post(pollRunnable)
        Log.i(TAG, "started")
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    private suspend fun sendWatchReady() {
        runCatching {
            val nodes = WearNodes.reachablePhoneNodes(this)
            if (nodes.isEmpty()) return
            val client = Wearable.getMessageClient(this)
            val data = BuildConfig.VERSION_NAME.toByteArray()
            nodes.forEach { node ->
                client.sendMessage(node.id, MessagePaths.WATCH_READY, data).await()
            }
            Log.i(TAG, "sent watch_ready to ${nodes.size} phone(s)")
        }.onFailure {
            Log.w(TAG, "watch_ready failed: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "WatchLinkService"
        private const val CHANNEL_ID = "ft_watch_link"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            ensureStarted(context)
        }

        fun ensureStarted(context: Context) {
            runCatching {
                val intent = Intent(context, WatchLinkService::class.java)
                context.startForegroundService(intent)
            }.onFailure {
                Log.w(TAG, "FGS start deferred: ${it.message}")
            }
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
        )
    }
}
