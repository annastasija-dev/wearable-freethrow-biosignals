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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Watch → phone over Bluetooth via Wear Data Layer batches.
 * Prefer this over Message API (one message per sample floods the link).
 */
object RawBatchSync {
    private const val TAG = "RawBatchSync"
    private const val PATH_PREFIX = "/basketball/batch/"
    private const val BATCH_SIZE = 20
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = linkedMapOf<String, MutableList<JSONObject>>()
    private val sent = AtomicLong(0)

    fun samplesSent(): Long = sent.get()

    fun resetCounter() {
        sent.set(0)
        synchronized(pending) { pending.clear() }
    }

    fun enqueue(context: Context, stream: String, payload: JSONObject) {
        val sessionId = RecordingManager.sessionId()
        if (sessionId.isBlank()) return
        synchronized(pending) {
            val list = pending.getOrPut(stream) { mutableListOf() }
            list += JSONObject(payload.toString()).put("stream", stream)
            if (list.size >= BATCH_SIZE) {
                val batch = list.toList()
                list.clear()
                flush(context, sessionId, stream, batch)
            }
        }
    }

    fun flushAll(context: Context) {
        val sessionId = RecordingManager.sessionId()
        if (sessionId.isBlank()) return
        synchronized(pending) {
            pending.forEach { (stream, list) ->
                if (list.isNotEmpty()) {
                    val batch = list.toList()
                    list.clear()
                    flush(context, sessionId, stream, batch)
                }
            }
        }
    }

    private fun flush(context: Context, sessionId: String, stream: String, batch: List<JSONObject>) {
        scope.launch {
            runCatching {
                val arr = JSONArray()
                batch.forEach { arr.put(it) }
                // Unique path so Wear does not coalesce/drop rapid updates.
                val path = "$PATH_PREFIX$stream/${System.currentTimeMillis()}"
                val request = PutDataMapRequest.create(path).apply {
                    dataMap.putString("session_id", sessionId)
                    dataMap.putString("stream", stream)
                    dataMap.putString("samples_json", arr.toString())
                    dataMap.putLong("updated_at", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(context).putDataItem(request).await()
                sent.addAndGet(batch.size.toLong())
                Log.i(TAG, "flushed ${batch.size} $stream over BT data layer (total=${sent.get()})")
            }.onFailure {
                Log.w(TAG, "flush $stream failed: ${it.message}")
            }
        }
    }
}
