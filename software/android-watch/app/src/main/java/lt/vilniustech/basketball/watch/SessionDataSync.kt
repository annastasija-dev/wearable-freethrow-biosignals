package lt.vilniustech.basketball.watch

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object SessionDataSync {
    private const val TAG = "SessionDataSync"
    const val PATH = "/basketball/session"
    private var lastUpdatedAt = 0L
    private var lastSeq = 0L

    fun handleDataEvents(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            handleDataItem(event.dataItem)
        }
    }

    fun handleDataItem(item: DataItem) {
        val path = item.uri.path ?: return
        if (path != PATH) return
        val map = DataMapItem.fromDataItem(item).dataMap
        val updatedAt = map.getLong("updated_at")
        val seq = map.getLong("seq")
        if (updatedAt <= lastUpdatedAt && seq <= lastSeq) return
        lastUpdatedAt = updatedAt
        lastSeq = seq
        val phoneIp = map.getString("phone_ip").orEmpty()
        if (phoneIp.isNotBlank()) PhoneWifiRelay.rememberPhoneIp(phoneIp, WatchCommands.appContext())
        CloudDirectUploader.configure(
            map.getString("cloud_url"),
            map.getString("api_key"),
        )
        CloudSessionPoller.configure(
            map.getString("cloud_url"),
            map.getString("api_key"),
        )
        applyMap(map.getString("state").orEmpty(), map.getString("session_id").orEmpty())
    }

    private fun applyMap(state: String, sessionId: String) {
        Log.i(TAG, "apply state=$state session=$sessionId")
        when (state) {
            "recording" -> if (sessionId.isNotBlank()) WatchCommands.startSession(sessionId)
            "stopped" -> WatchCommands.stopSession()
        }
    }

    suspend fun readLatest(context: Context) {
        runCatching {
            val uri = Uri.parse("wear://*/basketball/session")
            val items = Wearable.getDataClient(context).getDataItems(uri).await()
            Log.i(TAG, "readLatest items=${items.count}")
            for (item in items) {
                handleDataItem(item)
            }
        }.onFailure {
            Log.w(TAG, "readLatest failed: ${it.message}")
        }
    }
}
