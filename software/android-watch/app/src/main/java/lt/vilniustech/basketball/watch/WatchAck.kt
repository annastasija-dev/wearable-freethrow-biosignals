package lt.vilniustech.basketball.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object WatchAck {
    private const val TAG = "WatchAck"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sendRecordingStarted(context: Context, sessionId: String) {
        scope.launch {
            runCatching {
                val nodes = WearNodes.reachablePhoneNodes(context)
                if (nodes.isEmpty()) throw IllegalStateException("no phone node")
                val client = Wearable.getMessageClient(context)
                val data = sessionId.toByteArray()
                nodes.forEach { node ->
                    client.sendMessage(node.id, MessagePaths.ACK, data).await()
                }
                Log.i(TAG, "ack sent session=$sessionId")
            }.onFailure {
                Log.w(TAG, "ack failed: ${it.message}")
            }
        }
    }
}
