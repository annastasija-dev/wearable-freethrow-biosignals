package lt.vilniustech.basketball.watch

import android.content.Intent
import android.os.PowerManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchCommandService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        WatchCommands.init(applicationContext)
        WatchLinkService.ensureStarted(applicationContext)
        WatchUdpListener.ensureStarted(applicationContext)
        WatchCommands.handleMessage(messageEvent)
        if (messageEvent.path == MessagePaths.START || messageEvent.path == "/basketball/start") {
            wakeScreenBriefly()
            bringAppToFront(messageEvent.data.decodeToString())
        }
    }

    private fun bringAppToFront(sessionId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(WatchCommands.EXTRA_SESSION_ID, sessionId)
            data = WatchCommands.sessionUri(sessionId)
        }
        startActivity(intent)
    }

    private fun wakeScreenBriefly() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ftwatch:session",
            )
            wakeLock.acquire(5000L)
        }
    }
}
