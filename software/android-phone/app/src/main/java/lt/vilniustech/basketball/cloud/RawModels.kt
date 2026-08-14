package lt.vilniustech.basketball.cloud

import org.json.JSONObject

data class RawSample(
    val stream: String,
    val payload: JSONObject,
)

data class CapabilitiesReport(
    val watchModel: String,
    val sdkVersion: String,
    val trackerMode: String,
    val supportedStreams: List<String>,
    val activeStreams: List<String>,
)
