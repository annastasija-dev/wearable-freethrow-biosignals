package lt.vilniustech.basketball.cloud

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem

object WatchStatusSync {
    private const val TAG = "WatchStatusSync"
    private const val PATH = "/basketball/watch_status"

    fun handleDataEvents(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (path != PATH) continue
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val state = map.getString("state").orEmpty()
            val sessionId = map.getString("session_id").orEmpty()
            val localSamples = map.getLong("local_samples")
            Log.i(TAG, "watch status state=$state session=$sessionId local=$localSamples")
            if (state == "recording" && sessionId.isNotBlank()) {
                WearableRawBridge.notifyWatchAck(sessionId)
            }
        }
    }
}
