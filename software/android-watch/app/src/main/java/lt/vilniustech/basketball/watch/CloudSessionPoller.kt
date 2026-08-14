package lt.vilniustech.basketball.watch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Polls cloud for active recording session — works without phone Wi-Fi relay. */
object CloudSessionPoller {
    private const val TAG = "CloudSessionPoller"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollInFlight = AtomicBoolean(false)

    @Volatile
    private var cloudUrl: String? = "https://ft-cloud-vgtu.fly.dev"

    @Volatile
    private var apiKey: String = "dev-change-me"

    @Volatile
    var lastError: String? = null
        private set

    fun configure(url: String?, key: String?) {
        if (!url.isNullOrBlank()) cloudUrl = url.trim().trimEnd('/')
        if (!key.isNullOrBlank()) apiKey = key.trim()
    }

    fun poll(context: Context) {
        if (!pollInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val base = cloudUrl ?: return@launch
                val json = get("$base/api/v1/sessions/active") ?: return@launch
                lastError = null
                val active = json.optJSONObject("active")
                if (active != null) {
                    val sessionId = active.optString("session_id")
                    val status = active.optString("status")
                    val startedAt = active.optString("started_at")
                    if (status == "recording" && sessionId.isNotBlank() && isRecent(startedAt)) {
                        if (!RecordingManager.isRecording() || RecordingManager.sessionId() != sessionId) {
                            Log.i(TAG, "cloud session start $sessionId")
                            WatchCommands.startSession(sessionId, context)
                        }
                        return@launch
                    }
                }
                if (RecordingManager.isRecording()) {
                    val currentId = RecordingManager.sessionId()
                    if (currentId.isNotBlank()) {
                        val detail = get("$base/api/v1/sessions/$currentId")
                        if (detail != null && detail.optString("ended_at").isNotBlank()) {
                            Log.i(TAG, "cloud session finished $currentId")
                            WatchCommands.stopSession(context)
                        }
                    }
                }
            } catch (error: Exception) {
                lastError = error.message
                Log.w(TAG, "poll failed: ${error.message}")
            } finally {
                pollInFlight.set(false)
            }
        }
    }

    private fun isRecent(startedAt: String): Boolean {
        if (startedAt.isBlank()) return true
        return runCatching {
            val instant = java.time.Instant.parse(startedAt)
            java.time.Duration.between(instant, java.time.Instant.now()).toMinutes() <= 10
        }.getOrDefault(true)
    }

    private fun get(url: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-API-Key", apiKey)
        conn.setRequestProperty("Connection", "close")
        val code = conn.responseCode
        val text = BufferedReader(InputStreamReader(
            if (code in 200..299) conn.inputStream else conn.errorStream,
        )).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) return null
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
