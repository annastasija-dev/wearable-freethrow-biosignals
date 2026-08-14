package lt.vilniustech.basketball.cloud

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge between Wear OS watch and phone cloud uploader (multi-stream).
 */
object WearableRawBridge : MessageClient.OnMessageReceivedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var appContextForReady: Context? = null
    private var sampleCallback: ((RawSample) -> Unit)? = null
    private var capabilitiesCallback: ((CapabilitiesReport) -> Unit)? = null
    private val streamCounts = mutableMapOf<String, Long>()
    private var lastWatchModel: String = "Galaxy Watch"
    @Volatile
    var watchAckSessionId: String? = null
        private set

    fun notifyWatchAck(sessionId: String) {
        watchAckSessionId = sessionId
        android.util.Log.i("WearableRawBridge", "watch ack (data) session=$sessionId")
    }

    fun start(
        context: Context,
        onSample: (RawSample) -> Unit,
        onCapabilities: (CapabilitiesReport) -> Unit,
    ) {
        sampleCallback = onSample
        capabilitiesCallback = onCapabilities
        appContextForReady = context.applicationContext
        Wearable.getMessageClient(context).addListener(this)
    }

    fun stop(context: Context) {
        Wearable.getMessageClient(context).removeListener(this)
        sampleCallback = null
        capabilitiesCallback = null
    }

    fun streamCounts(): Map<String, Long> = streamCounts.toMap()

    fun totalSamples(): Long = streamCounts.values.sum()

    fun trackSample(stream: String) {
        streamCounts[stream] = (streamCounts[stream] ?: 0L) + 1L
    }

    fun resetCounters() {
        streamCounts.clear()
        watchAckSessionId = null
    }

    fun sendStartRecording(context: Context, sessionId: String) {
        sendToWatch(context, "/basketball/start", sessionId.toByteArray(), retries = 12)
    }

    fun sendStopRecording(context: Context) {
        sendToWatch(context, "/basketball/stop", ByteArray(0), retries = 6)
    }

    fun watchModel(context: Context): String = lastWatchModel

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        handleMessage(event)
    }

    @Volatile
    var onWatchReady: ((Context) -> Unit)? = null

    fun handleMessage(event: com.google.android.gms.wearable.MessageEvent) {
        when {
            event.path == "/basketball/watch_ready" -> {
                android.util.Log.i("WearableRawBridge", "watch ready ping")
                onWatchReady?.let { cb ->
                    scope.launch { cb(appContextForReady ?: return@launch) }
                }
            }
            event.path == "/basketball/ack" -> {
                watchAckSessionId = event.data.decodeToString()
                android.util.Log.i("WearableRawBridge", "watch ack session=$watchAckSessionId")
            }
            event.path == "/basketball/capabilities" -> handleCapabilities(event)
            event.path == "/basketball/imu" -> handleLegacyImu(event)
            event.path.startsWith("/basketball/raw/") -> handleRawStream(event)
        }
    }

    suspend fun connectedWatchCount(context: Context): Int {
        return runCatching {
            WearNodes.reachableWatchNodes(context).size
        }.getOrDefault(0)
    }

    private fun handleCapabilities(event: com.google.android.gms.wearable.MessageEvent) {
        val payload = JSONObject(String(event.data))
        val report = CapabilitiesReport(
            watchModel = payload.optString("watch_model", "Galaxy Watch"),
            sdkVersion = payload.optString("sdk_version", "unknown"),
            trackerMode = payload.optString("tracker_mode", "unknown"),
            supportedStreams = payload.optJSONArray("supported_streams").toStringList(),
            activeStreams = payload.optJSONArray("active_streams").toStringList(),
        )
        if (report.watchModel.isNotBlank()) {
            lastWatchModel = report.watchModel
        }
        capabilitiesCallback?.invoke(report)
    }

    private fun handleLegacyImu(event: com.google.android.gms.wearable.MessageEvent) {
        val payload = JSONObject(String(event.data))
        dispatchSample(
            RawSample(
                stream = "accelerometer",
                payload = payload
                    .put("stream", "accelerometer")
                    .put("time_ms", payload.getLong("time_ms"))
                    .put("x", payload.getDouble("x"))
                    .put("y", payload.getDouble("y"))
                    .put("z", payload.getDouble("z"))
                    .put("source", payload.optString("source", "watch_relay")),
            ),
        )
    }

    private fun handleRawStream(event: com.google.android.gms.wearable.MessageEvent) {
        val stream = event.path.removePrefix("/basketball/raw/")
        if (stream.isBlank()) return
        val payload = JSONObject(String(event.data))
        dispatchSample(RawSample(stream = stream, payload = payload))
    }

    private fun dispatchSample(sample: RawSample) {
        streamCounts[sample.stream] = (streamCounts[sample.stream] ?: 0L) + 1L
        if (streamCounts[sample.stream]!! <= 3L || streamCounts[sample.stream]!! % 50L == 0L) {
            android.util.Log.i("WearableRawBridge", "sample ${sample.stream} total=${streamCounts[sample.stream]}")
        }
        sampleCallback?.invoke(sample)
    }

    private fun sendToWatch(context: Context, path: String, data: ByteArray, retries: Int = 5) {
        scope.launch {
            repeat(retries) { attempt ->
                val sent = runCatching {
                    val nodes = WearNodes.reachableWatchNodes(context)
                    if (nodes.isEmpty()) throw IllegalStateException("no watch node")
                    val client = Wearable.getMessageClient(context)
                    nodes.forEach { node ->
                        client.sendMessage(node.id, path, data).await()
                    }
                    android.util.Log.i("WearableRawBridge", "sent $path to ${nodes.size} watch node(s)")
                    true
                }.getOrElse { error ->
                    android.util.Log.w("WearableRawBridge", "send $path attempt ${attempt + 1}: ${error.message}")
                    false
                }
                if (sent) return@launch
                delay(1500)
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                add(optString(index))
            }
        }
    }
}

/** Backward-compatible alias used by older code paths. */
typealias WearableImuBridge = WearableRawBridge

fun WearableRawBridge.imuReceivedCount(): Long = totalSamples()
