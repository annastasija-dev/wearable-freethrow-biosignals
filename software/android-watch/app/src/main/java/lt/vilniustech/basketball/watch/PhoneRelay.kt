package lt.vilniustech.basketball.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

object PhoneRelay {
    private const val TAG = "PhoneRelay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamCounts = mutableMapOf<String, Long>()

    @Volatile
    var lastRelayError: String? = null
        private set

    @Volatile
    var lastConnectedPhoneNodes: Int = -1
        private set

    fun resetCounter() {
        streamCounts.clear()
        lastRelayError = null
    }

    fun sendSample(context: Context, stream: String, payload: JSONObject) {
        scope.launch {
            val body = JSONObject(payload.toString())
                .put("stream", stream)
                .put("source", payload.optString("source", "samsung_health_sensor"))
                .toString()
                .toByteArray()

            repeat(8) { attempt ->
                val sent = runCatching {
                    val nodes = WearNodes.reachablePhoneNodes(context)
                    lastConnectedPhoneNodes = nodes.size
                    if (nodes.isEmpty()) {
                        throw IllegalStateException("no phone node")
                    }
                    val client = Wearable.getMessageClient(context)
                    nodes.forEach { node ->
                        WearNodes.rememberPhoneNode(node.id)
                        client.sendMessage(node.id, MessagePaths.raw(stream), body).await()
                    }
                    streamCounts[stream] = (streamCounts[stream] ?: 0L) + 1L
                    lastRelayError = null
                    true
                }.getOrElse { error ->
                    lastRelayError = error.message
                    Log.w(TAG, "relay $stream attempt ${attempt + 1} failed: ${error.message}")
                    false
                }
                if (sent) return@launch
                delay(1000)
            }
        }
    }

    fun sendCapabilities(context: Context, report: CapabilitiesReport) {
        scope.launch {
            val payload = JSONObject()
                .put("watch_model", report.watchModel)
                .put("sdk_version", report.sdkVersion)
                .put("tracker_mode", RecordingManager.mode().ifBlank { "UNKNOWN" })
                .put("supported_streams", JSONArray(report.supportedStreams))
                .put("active_streams", JSONArray(report.activeStreams))
                .toString()
                .toByteArray()

            repeat(8) { attempt ->
                val sent = runCatching {
                    val nodes = WearNodes.reachablePhoneNodes(context)
                    lastConnectedPhoneNodes = nodes.size
                    if (nodes.isEmpty()) throw IllegalStateException("no phone node")
                    val client = Wearable.getMessageClient(context)
                    nodes.forEach { node ->
                        client.sendMessage(node.id, MessagePaths.CAPABILITIES, payload).await()
                    }
                    lastRelayError = null
                    true
                }.getOrElse { error ->
                    lastRelayError = error.message
                    Log.w(TAG, "capabilities attempt ${attempt + 1} failed: ${error.message}")
                    false
                }
                if (sent) return@launch
                delay(1000)
            }
        }
    }

    fun samplesSent(): Long = streamCounts.values.sum()

    fun streamCounts(): Map<String, Long> = streamCounts.toMap()

    fun probePhoneNodes(context: Context) {
        scope.launch {
            runCatching {
                val nodes = WearNodes.reachablePhoneNodes(context)
                lastConnectedPhoneNodes = nodes.size
                lastRelayError = if (nodes.isEmpty()) "no phone node" else null
                Log.i(TAG, "reachable phone nodes: ${nodes.size}")
            }.onFailure { error ->
                lastConnectedPhoneNodes = 0
                lastRelayError = error.message
                Log.w(TAG, "node probe failed: ${error.message}")
            }
        }
    }
}
