package lt.vilniustech.basketball.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/** Used only when Samsung SDK cannot be loaded — never generates fake samples. */
class ErrorTracker(
    override val modeName: String,
    private val message: String,
) : SensorTracker {
    override fun start(
        onSample: (stream: String, payload: JSONObject) -> Unit,
        onCapabilities: (CapabilitiesReport) -> Unit,
    ) {
        TrackerDiagnostics.sdkError = message
    }

    override fun stop() = Unit
}
