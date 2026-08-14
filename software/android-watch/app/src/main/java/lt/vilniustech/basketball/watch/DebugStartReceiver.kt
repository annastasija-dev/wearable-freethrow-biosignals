package lt.vilniustech.basketball.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-only: adb shell am broadcast -a lt.vilniustech.basketball.watch.START_TEST */
class DebugStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val sessionId = "TEST_${System.currentTimeMillis()}"
        WatchCommands.init(context.applicationContext)
        WatchCommands.startSession(sessionId, context)
    }

    companion object {
        const val ACTION = "lt.vilniustech.basketball.watch.START_TEST"
    }
}
