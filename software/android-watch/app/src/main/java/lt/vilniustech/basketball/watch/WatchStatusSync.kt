package lt.vilniustech.basketball.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object WatchStatusSync {
    private const val TAG = "WatchStatusSync"
    private const val PATH = "/basketball/watch_status"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun publishRecording(context: Context, sessionId: String) {
        scope.launch { publish(context, "recording", sessionId) }
    }

    fun publishStopped(context: Context) {
        scope.launch { publish(context, "stopped", "") }
    }

    fun publishHeartbeat(context: Context) {
        if (!RecordingManager.isRecording()) return
        scope.launch {
            publish(
                context,
                "recording",
                RecordingManager.sessionId(),
                RecordingManager.localSamples(),
            )
        }
    }

    private suspend fun publish(
        context: Context,
        state: String,
        sessionId: String,
        localSamples: Long = 0L,
    ) {
        runCatching {
            val request = PutDataMapRequest.create(PATH).apply {
                dataMap.putString("state", state)
                dataMap.putString("session_id", sessionId)
                dataMap.putLong("local_samples", localSamples)
                dataMap.putLong("updated_at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.i(TAG, "published $state session=$sessionId local=$localSamples")
        }.onFailure {
            Log.w(TAG, "publish failed: ${it.message}")
        }
    }
}
