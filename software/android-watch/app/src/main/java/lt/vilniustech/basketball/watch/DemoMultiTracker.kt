package lt.vilniustech.basketball.watch

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.sin
import kotlin.random.Random
import org.json.JSONObject

/**
 * Synthetic multi-stream generator when samsung-health-sensor-api.aar is missing.
 */
class DemoMultiTracker : SensorTracker {
    override val modeName = "DEMO"

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var tick = 0
    private var onSample: ((String, JSONObject) -> Unit)? = null

    private val streams = listOf("accelerometer", "heart_rate", "ppg", "eda", "skin_temperature")

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val t = System.currentTimeMillis()
            val phase = tick / 25.0
            val sampleCb = onSample ?: return

            sampleCb(
                "accelerometer",
                JSONObject()
                    .put("time_ms", t)
                    .put("x", sin(phase) * 2.0 + Random.nextDouble(-0.2, 0.2))
                    .put("y", sin(phase * 1.3) * 1.5)
                    .put("z", 9.8 + sin(phase * 0.7) * 3.0),
            )

            if (tick % 25 == 0) {
                sampleCb(
                    "heart_rate",
                    JSONObject()
                        .put("time_ms", t)
                        .put("bpm", 70 + (sin(phase * 0.2) * 8).toInt())
                        .put("ibi_ms", JSONArrayHelper.list(820, 790))
                        .put("status", 1),
                )
                sampleCb(
                    "eda",
                    JSONObject()
                        .put("time_ms", t)
                        .put("skin_conductance_us", 0.35 + sin(phase * 0.1) * 0.05)
                        .put("status", 0),
                )
            }

            if (tick % 5 == 0) {
                sampleCb(
                    "ppg",
                    JSONObject()
                        .put("time_ms", t)
                        .put("green", 1200 + Random.nextInt(-40, 40))
                        .put("ir", 980 + Random.nextInt(-30, 30))
                        .put("red", 450 + Random.nextInt(-20, 20)),
                )
            }

            if (tick % 125 == 0) {
                sampleCb(
                    "skin_temperature",
                    JSONObject()
                        .put("time_ms", t)
                        .put("object_temp_c", 33.2 + sin(phase * 0.05) * 0.3)
                        .put("ambient_temp_c", 24.1 + sin(phase * 0.03) * 0.2)
                        .put("status", 0),
                )
            }

            tick++
            handler.postDelayed(this, 40)
        }
    }

    override fun start(
        onSample: (stream: String, payload: JSONObject) -> Unit,
        onCapabilities: (CapabilitiesReport) -> Unit,
    ) {
        this.onSample = onSample
        running = true
        tick = 0
        onCapabilities(
            CapabilitiesReport(
                watchModel = Build.MODEL.ifBlank { "Demo Watch" },
                sdkVersion = "demo",
                supportedStreams = streams,
                activeStreams = streams,
            ),
        )
        handler.post(runnable)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(runnable)
        onSample = null
    }
}

private object JSONArrayHelper {
    fun list(vararg values: Int): org.json.JSONArray {
        val array = org.json.JSONArray()
        values.forEach { array.put(it) }
        return array
    }
}
