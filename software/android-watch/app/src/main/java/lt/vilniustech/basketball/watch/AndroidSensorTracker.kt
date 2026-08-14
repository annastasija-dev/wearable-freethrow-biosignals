package lt.vilniustech.basketball.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import org.json.JSONObject

/** Real hardware sensors when Samsung SDK policy blocks access. */
class AndroidSensorTracker(
    private val context: Context,
) : SensorTracker, SensorEventListener {
    override val modeName = "ANDROID_FALLBACK"

    private var sensorManager: SensorManager? = null
    private var onSample: ((String, JSONObject) -> Unit)? = null
    private var onCapabilities: ((CapabilitiesReport) -> Unit)? = null

    override fun start(
        onSample: (stream: String, payload: JSONObject) -> Unit,
        onCapabilities: (CapabilitiesReport) -> Unit,
    ) {
        this.onSample = onSample
        this.onCapabilities = onCapabilities
        TrackerDiagnostics.sdkError = null
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val started = mutableListOf<String>()
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            started += "accelerometer"
        }
        sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sensor ->
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            started += "heart_rate"
        }
        if (started.isEmpty()) {
            TrackerDiagnostics.sdkError = "No Android sensors available"
            return
        }
        Log.i(TAG, "started streams=$started")
        onCapabilities(
            CapabilitiesReport(
                watchModel = Build.MODEL.ifBlank { "Galaxy Watch" },
                sdkVersion = "android",
                supportedStreams = started,
                activeStreams = started,
            ),
        )
    }

    override fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        onSample = null
        onCapabilities = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val cb = onSample ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                cb(
                    "accelerometer",
                    JSONObject()
                        .put("time_ms", System.currentTimeMillis())
                        .put("x", event.values[0].toDouble())
                        .put("y", event.values[1].toDouble())
                        .put("z", event.values[2].toDouble())
                        .put("source", "android_sensor"),
                )
            }
            Sensor.TYPE_HEART_RATE -> {
                if (event.values.isNotEmpty() && event.values[0] > 0f) {
                    cb(
                        "heart_rate",
                        JSONObject()
                            .put("time_ms", System.currentTimeMillis())
                            .put("bpm", event.values[0].toDouble())
                            .put("source", "android_sensor"),
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "AndroidSensorTracker"
    }
}
