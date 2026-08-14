package lt.vilniustech.basketball.watch

import org.json.JSONObject

interface SensorTracker {
    fun start(
        onSample: (stream: String, payload: JSONObject) -> Unit,
        onCapabilities: (CapabilitiesReport) -> Unit,
    )

    fun stop()

    fun flush() = Unit

    val modeName: String
}

data class CapabilitiesReport(
    val watchModel: String,
    val sdkVersion: String,
    val supportedStreams: List<String>,
    val activeStreams: List<String>,
)
