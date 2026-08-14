package lt.vilniustech.basketball.watch

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumSet

/**
 * Samsung Health Sensor SDK — all continuous biomedical streams supported on Galaxy Watch.
 */
class SamsungHealthMultiTracker(
    private val context: Context,
) : SensorTracker, ConnectionListener {

    override val modeName = "SAMSUNG_SDK"

    private var healthService: HealthTrackingService? = null
    private val activeTrackers = mutableMapOf<String, HealthTracker>()
    private var onSample: ((String, JSONObject) -> Unit)? = null
    private var onCapabilities: ((CapabilitiesReport) -> Unit)? = null
    private var supportedStreams: List<String> = emptyList()
    private var activeStreams: List<String> = emptyList()
    private val streamsWithData = mutableSetOf<String>()
    private var sampleCount = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val accelScale = 9.81 / (16383.75 / 4.0)

    private val streamTypes = linkedMapOf(
        "accelerometer" to HealthTrackerType.ACCELEROMETER_CONTINUOUS,
        "heart_rate" to HealthTrackerType.HEART_RATE_CONTINUOUS,
        "ppg" to HealthTrackerType.PPG_CONTINUOUS,
        "eda" to HealthTrackerType.EDA_CONTINUOUS,
        "skin_temperature" to HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,
    )

    override fun start(
        onSample: (stream: String, payload: JSONObject) -> Unit,
        onCapabilities: (CapabilitiesReport) -> Unit,
    ) {
        this.onSample = onSample
        this.onCapabilities = onCapabilities
        streamsWithData.clear()
        TrackerDiagnostics.sdkError = null
        if (healthService == null) {
            healthService = HealthTrackingService(this, context.applicationContext)
        }
        healthService?.connectService()
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        activeTrackers.values.forEach { tracker ->
            runCatching { tracker.unsetEventListener() }
        }
        activeTrackers.clear()
        onSample = null
        onCapabilities = null
        streamsWithData.clear()
        healthService?.disconnectService()
    }

    override fun flush() {
        activeTrackers.values.forEach { tracker ->
            runCatching { tracker.flush() }
        }
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "connected, starting trackers")
        val service = healthService ?: return
        val supportedTypes = service.trackingCapability.supportHealthTrackerTypes
        supportedStreams = streamTypes
            .filter { (_, trackerType) -> supportedTypes.contains(trackerType) }
            .keys
            .toList()
        Log.i(TAG, "supported by watch: $supportedStreams")

        val started = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        streamTypes.forEach { (stream, trackerType) ->
            if (!supportedTypes.contains(trackerType)) {
                Log.i(TAG, "skip unsupported stream=$stream")
                return@forEach
            }
            val delayMs = when (stream) {
                "accelerometer", "heart_rate" -> 0L
                "ppg" -> 300L
                "skin_temperature" -> 600L
                "eda" -> 900L
                else -> 0L
            }
            handler.postDelayed({
                runCatching {
                    startStream(service, stream, trackerType)
                    started.add(stream)
                    activeStreams = started.sorted()
                    publishCapabilities()
                    Log.i(TAG, "started stream=$stream active=$activeStreams")
                }.onFailure {
                    Log.w(TAG, "failed stream=$stream: ${it.message}")
                }
            }, delayMs)
        }
    }

    override fun onConnectionEnded() = Unit

    override fun onConnectionFailed(exception: HealthTrackerException) {
        val detail = SamsungPolicy.describe(exception)
        TrackerDiagnostics.sdkState = if (SamsungPolicy.isPolicyBlocked(exception)) {
            TrackerDiagnostics.SdkState.POLICY_BLOCKED
        } else {
            TrackerDiagnostics.SdkState.FAILED
        }
        TrackerDiagnostics.sdkError = detail
        Log.e(TAG, "connection failed: $detail")
    }

    companion object {
        private const val TAG = "SamsungHealth"
        private val PPG_CHANNELS: Set<PpgType> = EnumSet.of(PpgType.GREEN, PpgType.RED, PpgType.IR)
    }

    private fun startStream(
        service: HealthTrackingService,
        stream: String,
        trackerType: HealthTrackerType,
    ) {
        val tracker = if (stream == "ppg") {
            service.getHealthTracker(trackerType, PPG_CHANNELS)
        } else {
            service.getHealthTracker(trackerType)
        }
        tracker.setEventListener(listenerFor(stream))
        activeTrackers[stream] = tracker
        TrackerDiagnostics.sdkState = TrackerDiagnostics.SdkState.CONNECTED
        TrackerDiagnostics.probedStreams = activeTrackers.keys.sorted()
        TrackerDiagnostics.sdkError = null
    }

    private fun publishCapabilities() {
        onCapabilities?.invoke(
            CapabilitiesReport(
                watchModel = Build.MODEL.ifBlank { "Galaxy Watch" },
                sdkVersion = "1.4.1",
                supportedStreams = supportedStreams,
                activeStreams = activeStreams,
            ),
        )
    }

    private fun listenerFor(stream: String): HealthTracker.TrackerEventListener {
        return object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                val cb = onSample ?: return
                for (point in dataPoints) {
                    val payload = when (stream) {
                        "accelerometer" -> accelerometerPayload(point)
                        "heart_rate" -> heartRatePayload(point)
                        "ppg" -> ppgPayload(point)
                        "eda" -> edaPayload(point)
                        "skin_temperature" -> skinTemperaturePayload(point)
                        else -> null
                    } ?: continue
                    sampleCount++
                    val firstForStream = streamsWithData.add(stream)
                    if (sampleCount == 1L || firstForStream) {
                        Log.i(TAG, "first sample stream=$stream")
                        publishCapabilities()
                    }
                    cb(stream, payload)
                }
            }

            override fun onError(trackerError: HealthTracker.TrackerError) {
                if (SamsungPolicy.isPolicyBlocked(trackerError)) {
                    TrackerDiagnostics.sdkState = TrackerDiagnostics.SdkState.POLICY_BLOCKED
                    TrackerDiagnostics.sdkError = "SDK_POLICY_ERROR stream=$stream"
                } else {
                    TrackerDiagnostics.sdkError = "Samsung $stream error: $trackerError"
                }
                Log.e(TAG, "$stream tracker error: $trackerError")
            }

            override fun onFlushCompleted() {
                Log.d(TAG, "flush completed stream=$stream")
            }
        }
    }

    private fun accelerometerPayload(point: DataPoint): JSONObject? {
        val x = point.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) ?: return null
        val y = point.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) ?: return null
        val z = point.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) ?: return null
        return JSONObject()
            .put("time_ms", System.currentTimeMillis())
            .put("x", x * accelScale)
            .put("y", y * accelScale)
            .put("z", z * accelScale)
    }

    private fun heartRatePayload(point: DataPoint): JSONObject? {
        val bpm = point.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: return null
        val status = point.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
        val ibiList = point.getValue(ValueKey.HeartRateSet.IBI_LIST) as? List<*>
        val payload = JSONObject()
            .put("time_ms", System.currentTimeMillis())
            .put("bpm", bpm)
        if (status != null) payload.put("status", status)
        if (!ibiList.isNullOrEmpty()) {
            payload.put("ibi_ms", JSONArray().apply {
                ibiList.forEach { value ->
                    if (value != null) put(value)
                }
            })
        }
        return payload
    }

    private fun ppgPayload(point: DataPoint): JSONObject? {
        val green = point.getValue(ValueKey.PpgSet.PPG_GREEN)
        val ir = point.getValue(ValueKey.PpgSet.PPG_IR)
        val red = point.getValue(ValueKey.PpgSet.PPG_RED)
        if (green == null && ir == null && red == null) return null
        val payload = JSONObject().put("time_ms", System.currentTimeMillis())
        if (green != null) payload.put("green", green)
        if (ir != null) payload.put("ir", ir)
        if (red != null) payload.put("red", red)
        return payload
    }

    private fun edaPayload(point: DataPoint): JSONObject? {
        val conductance = point.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE) ?: return null
        val status = point.getValue(ValueKey.EdaSet.STATUS)
        val payload = JSONObject()
            .put("time_ms", System.currentTimeMillis())
            .put("skin_conductance_us", conductance)
        if (status != null) payload.put("status", status)
        return payload
    }

    private fun skinTemperaturePayload(point: DataPoint): JSONObject? {
        val objectTemp = point.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
        val ambientTemp = point.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
        val status = point.getValue(ValueKey.SkinTemperatureSet.STATUS)
        if (objectTemp == null && ambientTemp == null && status == null) return null
        val payload = JSONObject().put("time_ms", System.currentTimeMillis())
        if (objectTemp != null) payload.put("object_temp_c", objectTemp)
        if (ambientTemp != null) payload.put("ambient_temp_c", ambientTemp)
        if (status != null) payload.put("status", status)
        return payload
    }
}
