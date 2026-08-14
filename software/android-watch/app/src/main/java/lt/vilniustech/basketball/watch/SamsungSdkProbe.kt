package lt.vilniustech.basketball.watch

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Real Samsung SDK connection probe — not guessing from error text. */
object SamsungSdkProbe {
    private const val TAG = "SamsungSdkProbe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    fun probeAsync(context: Context) {
        if (!inFlight.compareAndSet(false, true)) return
        if (TrackerDiagnostics.sdkState == TrackerDiagnostics.SdkState.CONNECTED) {
            inFlight.set(false)
            return
        }
        TrackerDiagnostics.sdkState = TrackerDiagnostics.SdkState.PROBING
        scope.launch {
            try {
                runProbe(context.applicationContext)
            } finally {
                inFlight.set(false)
            }
        }
    }

    private suspend fun runProbe(context: Context) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var service: HealthTrackingService? = null
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                val types = service?.trackingCapability?.supportHealthTrackerTypes.orEmpty()
                val streams = listOf(
                    HealthTrackerType.ACCELEROMETER_CONTINUOUS,
                    HealthTrackerType.HEART_RATE_CONTINUOUS,
                    HealthTrackerType.PPG_CONTINUOUS,
                    HealthTrackerType.EDA_CONTINUOUS,
                    HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,
                ).filter { types.contains(it) }.map { type ->
                    when (type) {
                        HealthTrackerType.ACCELEROMETER_CONTINUOUS -> "accelerometer"
                        HealthTrackerType.HEART_RATE_CONTINUOUS -> "heart_rate"
                        HealthTrackerType.PPG_CONTINUOUS -> "ppg"
                        HealthTrackerType.EDA_CONTINUOUS -> "eda"
                        HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS -> "skin_temperature"
                        else -> type.name.lowercase()
                    }
                }
                TrackerDiagnostics.probedStreams = streams
                TrackerDiagnostics.sdkState = TrackerDiagnostics.SdkState.CONNECTED
                TrackerDiagnostics.sdkError = null
                Log.i(TAG, "probe OK streams=$streams")
                latch.countDown()
            }

            override fun onConnectionEnded() = Unit

            override fun onConnectionFailed(exception: HealthTrackerException) {
                val detail = SamsungPolicy.describe(exception)
                if (SamsungPolicy.isPolicyBlocked(exception)) {
                    TrackerDiagnostics.sdkState = TrackerDiagnostics.SdkState.POLICY_BLOCKED
                    TrackerDiagnostics.sdkError = detail
                } else {
                    TrackerDiagnostics.sdkState = TrackerDiagnostics.SdkState.FAILED
                    TrackerDiagnostics.sdkError = detail
                }
                Log.w(TAG, "probe failed: $detail")
                latch.countDown()
            }
        }
        runCatching {
            service = HealthTrackingService(listener, context)
            service?.connectService()
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            if (TrackerDiagnostics.sdkState == TrackerDiagnostics.SdkState.PROBING) {
                TrackerDiagnostics.sdkState = TrackerDiagnostics.SdkState.FAILED
                TrackerDiagnostics.sdkError = "Samsung SDK timeout"
            }
        }.onFailure {
            TrackerDiagnostics.sdkState = TrackerDiagnostics.SdkState.FAILED
            TrackerDiagnostics.sdkError = it.message
            Log.w(TAG, "probe error: ${it.message}")
        }.also {
            runCatching { service?.disconnectService() }
        }
    }
}
