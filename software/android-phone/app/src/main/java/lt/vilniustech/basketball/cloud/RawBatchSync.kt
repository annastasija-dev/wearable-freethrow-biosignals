package lt.vilniustech.basketball.cloud

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import org.json.JSONArray
import org.json.JSONObject

object RawBatchSync {
    private const val TAG = "RawBatchSync"
    private const val PATH_PREFIX = "/basketball/batch/"

    fun handleDataEvents(events: DataEventBuffer, onSample: (RawSample) -> Unit) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (!path.startsWith(PATH_PREFIX)) continue
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val stream = map.getString("stream") ?: path.removePrefix(PATH_PREFIX)
            val samplesJson = map.getString("samples_json") ?: continue
            val arr = JSONArray(samplesJson)
            for (i in 0 until arr.length()) {
                val payload = arr.getJSONObject(i)
                onSample(RawSample(stream = stream, payload = payload))
            }
            Log.i(TAG, "received ${arr.length()} $stream samples via data layer")
        }
    }
}
