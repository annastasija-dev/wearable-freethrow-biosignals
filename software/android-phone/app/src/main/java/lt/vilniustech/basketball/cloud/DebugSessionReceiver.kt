package lt.vilniustech.basketball.cloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** ADB test: adb shell am broadcast -a lt.vilniustech.basketball.cloud.DEBUG_START */
class DebugSessionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        val sessionId = intent?.getStringExtra("session_id")
            ?: "DBG_${java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())}"
        SessionCoordinator.init(context)
        SessionCoordinator.bindSession(
            SessionState(
                active = true,
                sessionId = sessionId,
                participantCode = "DBG",
                shotsDone = 0,
                shotsTarget = 10,
            ),
        )
        scope.launch {
            SessionDataSync.publishRecording(context, sessionId)
            Log.i(TAG, "debug session active=$sessionId")
        }
    }

    companion object {
        private const val TAG = "DebugSessionReceiver"
    }
}
