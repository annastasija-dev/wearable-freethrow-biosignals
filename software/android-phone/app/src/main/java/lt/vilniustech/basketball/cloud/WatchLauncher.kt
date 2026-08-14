package lt.vilniustech.basketball.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.concurrent.futures.await
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.tasks.await

object WatchLauncher {
    private const val TAG = "WatchLauncher"
    private const val WATCH_PACKAGE = "lt.vilniustech.basketball.watch"
    const val EXTRA_SESSION_ID = "session_id"

    suspend fun launchRecordingSession(context: Context, sessionId: String): Boolean {
        val nodes = WearNodes.reachableWatchNodes(context)
        if (nodes.isEmpty()) {
            Log.w(TAG, "no watch nodes for launch")
            return false
        }
        val uri = Uri.parse("ftwatch://start?session=${Uri.encode(sessionId)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(WATCH_PACKAGE)
            putExtra(EXTRA_SESSION_ID, sessionId)
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return runCatching {
            RemoteActivityHelper(context)
                .startRemoteActivity(intent, nodes.first().id)
                .await()
            Log.i(TAG, "launched watch session=$sessionId")
            true
        }.getOrElse {
            Log.w(TAG, "launch failed: ${it.message}")
            false
        }
    }

    suspend fun sendStartMessage(context: Context, sessionId: String): Boolean {
        val nodes = WearNodes.reachableWatchNodes(context)
        if (nodes.isEmpty()) return false
        val client = com.google.android.gms.wearable.Wearable.getMessageClient(context)
        val data = sessionId.toByteArray()
        nodes.forEach { node ->
            client.sendMessage(node.id, "/basketball/start", data).await()
        }
        Log.i(TAG, "sent /basketball/start to ${nodes.size} node(s)")
        return true
    }
}
