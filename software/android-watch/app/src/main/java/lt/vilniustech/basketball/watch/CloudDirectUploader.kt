package lt.vilniustech.basketball.watch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Upload samples directly to cloud — bypasses phone relay entirely. */
object CloudDirectUploader {
    private const val TAG = "CloudDirectUploader"
    private const val DEFAULT_CLOUD = "https://ft-cloud-vgtu.fly.dev"
    private const val DEFAULT_API_KEY = "dev-change-me"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = linkedMapOf<String, MutableList<JSONObject>>()
    private val inFlight = AtomicInteger(0)

    @Volatile
    private var cloudUrl: String? = DEFAULT_CLOUD

    @Volatile
    private var apiKey: String = DEFAULT_API_KEY

    @Volatile
    private var boundSessionId: String = ""

    @Volatile
    private var cloudSamplesSent = 0L

    @Volatile
    var lastError: String? = null
        private set

    fun ensureConfigured() {
        if (cloudUrl.isNullOrBlank()) configure(DEFAULT_CLOUD, DEFAULT_API_KEY)
    }

    fun bindSession(sessionId: String) {
        boundSessionId = sessionId
    }

    fun samplesSent(): Long = cloudSamplesSent

    fun resetCounter() {
        cloudSamplesSent = 0L
        lastError = null
        // Keep boundSessionId — cleared only when a new session binds or recording stops.
        synchronized(pending) { pending.clear() }
    }

    fun configure(url: String?, key: String?) {
        if (!url.isNullOrBlank()) {
            cloudUrl = url.trim().trimEnd('/')
            Log.i(TAG, "cloud=$cloudUrl")
        }
        if (!key.isNullOrBlank()) apiKey = key.trim()
    }

    fun enqueueSample(stream: String, payload: JSONObject) {
        synchronized(pending) {
            val list = pending.getOrPut(stream) { mutableListOf() }
            list += JSONObject(payload.toString()).put("stream", stream)
            if (list.size >= 1) flushStream(stream, boundSessionId)
        }
    }

    fun flushAll(sessionId: String = boundSessionId) {
        synchronized(pending) {
            pending.keys.toList().forEach { flushStream(it, sessionId) }
        }
    }

    fun awaitPendingUploads(timeoutMs: Long = 12_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        flushAll(boundSessionId)
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    fun uploadCapabilities(report: CapabilitiesReport, sessionId: String = boundSessionId) {
        val base = cloudUrl ?: return
        if (sessionId.isBlank()) return
        scope.launch {
            runCatching {
                val body = JSONObject()
                    .put("watch_model", report.watchModel)
                    .put("sdk_version", report.sdkVersion)
                    .put("tracker_mode", RecordingManager.mode().ifBlank { "UNKNOWN" })
                    .put("supported_streams", org.json.JSONArray(report.supportedStreams))
                    .put("active_streams", org.json.JSONArray(report.activeStreams))
                post("$base/api/v1/sessions/$sessionId/raw/capabilities", body)
                lastError = null
                Log.i(TAG, "capabilities uploaded mode=${RecordingManager.mode()}")
            }.onFailure {
                lastError = it.message
                Log.w(TAG, "capabilities failed: ${it.message}")
            }
        }
    }

    private fun flushStream(stream: String, sessionId: String) {
        val base = cloudUrl
        if (sessionId.isBlank() || base.isNullOrBlank()) return
        val batch: List<JSONObject>
        synchronized(pending) {
            val list = pending[stream] ?: return
            if (list.isEmpty()) return
            batch = list.toList()
            list.clear()
        }
        inFlight.incrementAndGet()
        scope.launch {
            try {
                runCatching {
                    val arr = org.json.JSONArray()
                    batch.forEach { arr.put(it) }
                    val body = JSONObject().put("stream", stream).put("samples", arr)
                    post("$base/api/v1/sessions/$sessionId/raw/batch", body)
                    cloudSamplesSent += batch.size
                    lastError = null
                    Log.i(TAG, "cloud sent ${batch.size} $stream (total=$cloudSamplesSent)")
                }.onFailure {
                    lastError = it.message
                    synchronized(pending) {
                        pending.getOrPut(stream) { mutableListOf() }.addAll(0, batch)
                    }
                    Log.w(TAG, "cloud flush $stream failed: ${it.message}")
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun post(url: String, body: JSONObject) {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                doPost(url, body)
                return
            } catch (error: Exception) {
                last = error
                Thread.sleep((attempt + 1) * 200L)
            }
        }
        throw last ?: IllegalStateException("upload failed")
    }

    private fun doPost(url: String, body: JSONObject) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-API-Key", apiKey)
        conn.setRequestProperty("Connection", "close")
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IllegalStateException("HTTP $code $err")
        }
        conn.disconnect()
    }
}
