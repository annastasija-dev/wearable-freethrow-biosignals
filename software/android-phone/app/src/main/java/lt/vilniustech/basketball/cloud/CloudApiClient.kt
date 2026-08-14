package lt.vilniustech.basketball.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class CloudApiClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    fun healthCheck() {
        get("/health")
    }

    fun healthCheckFast() {
        get("/health", connectTimeoutMs = 2500, readTimeoutMs = 2500)
    }

    fun fetchSetupInfo(): SetupInfo {
        val json = get("/api/v1/setup-info")
        val urls = mutableListOf<String>()
        val array = json.optJSONArray("cloud_urls")
        if (array != null) {
            for (index in 0 until array.length()) {
                urls.add(array.optString(index))
            }
        }
        return SetupInfo(
            apiKey = json.optString("api_key"),
            cloudUrls = urls,
            installUrl = json.optString("install_url"),
        )
    }

    fun startSession(
        participantCode: String,
        watchModel: String,
        wrist: String,
        phoneModel: String?,
        profile: ParticipantProfile,
    ): SessionStartResponse {
        val body = JSONObject()
            .put("participant_code", participantCode)
            .put("watch_model", watchModel)
            .put("wrist", wrist)
            .put("phone_model", phoneModel)
            .put("weight_kg", profile.weightKg)
            .put("height_cm", profile.heightCm)
            .put("age_years", profile.ageYears)
            .put("sex", profile.sex)
            .put("throw_technique", profile.throwTechnique)
        val json = post("/api/v1/sessions/start", body)
        return SessionStartResponse(
            sessionId = json.getString("session_id"),
            startedAt = json.getString("started_at"),
            shotsTarget = json.getInt("shots_target"),
        )
    }
    fun labelShot(
        sessionId: String,
        result: String,
        clientTimestamp: Instant,
        shotNo: Int,
    ): ShotLabelResponse {
        val body = JSONObject()
            .put("result", result)
            .put("client_timestamp", clientTimestamp.toString())
            .put("shot_no", shotNo)
        val json = post("/api/v1/sessions/$sessionId/shots", body)
        return ShotLabelResponse(
            sessionId = json.getString("session_id"),
            shotNo = json.getInt("shot_no"),
            result = json.getString("result"),
            remaining = json.getInt("remaining"),
            done = json.getBoolean("done"),
        )
    }

    fun uploadRawBatch(sessionId: String, stream: String, samples: List<JSONObject>) {
        if (samples.isEmpty()) return
        val arr = JSONArray()
        samples.forEach { sample ->
            arr.put(sample)
        }
        post(
            "/api/v1/sessions/$sessionId/raw/batch",
            JSONObject()
                .put("stream", stream)
                .put("samples", arr),
        )
    }

    fun uploadCapabilities(sessionId: String, report: CapabilitiesReport) {
        val body = JSONObject()
            .put("watch_model", report.watchModel)
            .put("sdk_version", report.sdkVersion)
            .put("tracker_mode", report.trackerMode)
            .put("supported_streams", JSONArray(report.supportedStreams))
            .put("active_streams", JSONArray(report.activeStreams))
        post("/api/v1/sessions/$sessionId/raw/capabilities", body)
    }

    fun finishSession(sessionId: String) {
        post("/api/v1/sessions/$sessionId/finish", JSONObject())
    }

    fun fetchSessionSummary(sessionId: String): SessionSummary {
        val json = get("/api/v1/sessions/$sessionId")
        val streams = mutableMapOf<String, Int>()
        val streamJson = json.optJSONObject("raw_streams")
        if (streamJson != null) {
            streamJson.keys().forEach { key ->
                streams[key] = streamJson.optInt(key)
            }
        }
        return SessionSummary(
            sessionId = json.optString("session_id"),
            rawSamples = json.optInt("raw_samples"),
            rawStreams = streams,
            endedAt = json.optString("ended_at").takeIf { it.isNotBlank() },
        )
    }

    private fun get(path: String, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 15_000): JSONObject {
        val url = URL(baseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("X-API-Key", apiKey)
        }
        return readResponse(connection)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val url = URL(baseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", apiKey)
        }
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $text")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

data class SetupInfo(
    val apiKey: String,
    val cloudUrls: List<String>,
    val installUrl: String,
)

data class SessionSummary(
    val sessionId: String,
    val rawSamples: Int,
    val rawStreams: Map<String, Int> = emptyMap(),
    val endedAt: String? = null,
)
