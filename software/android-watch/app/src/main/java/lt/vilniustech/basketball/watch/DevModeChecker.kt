package lt.vilniustech.basketball.watch

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/** Shows Samsung Health Platform Dev mode status on the watch UI. */
object DevModeChecker {
    private const val TAG = "DevModeChecker"
    private const val HEALTH_PLATFORM = "com.samsung.android.health.platform"

    @Volatile
    private var lastStatus: String = "Checking Samsung SDK…"

    fun statusMessage(context: Context): String = lastStatus

    fun refresh(context: Context) {
        lastStatus = probe(context)
        Log.i(TAG, lastStatus)
    }

    private fun probe(context: Context): String {
        val installed = runCatching {
            context.packageManager.getPackageInfo(HEALTH_PLATFORM, 0)
            true
        }.getOrDefault(false)
        if (!installed) {
            return "Health Platform not found — Settings→Apps"
        }
        val bodySensors = context.checkSelfPermission(android.Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED
        val samsungPerm = context.checkSelfPermission(
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
        ) == PackageManager.PERMISSION_GRANTED
        if (!bodySensors || !samsungPerm) {
            return "Permissions: allow sensors for FT Watch"
        }

        if (RecordingManager.isRecording()) {
            val streams = RecordingManager.localStreamCounts().keys.sorted().joinToString(", ")
            return if (streams.isNotBlank()) {
                "Recording: $streams"
            } else {
                "Recording — waiting for data…"
            }
        }

        return when (TrackerDiagnostics.sdkState) {
            TrackerDiagnostics.SdkState.CONNECTED -> {
                val streams = TrackerDiagnostics.probedStreams.joinToString(", ")
                "✅ Samsung SDK OK ($streams)"
            }
            TrackerDiagnostics.SdkState.POLICY_BLOCKED ->
                "⚠️ Samsung SDK policy — reboot watch, then open FT Watch"
            TrackerDiagnostics.SdkState.PROBING ->
                "Checking Samsung SDK…"
            TrackerDiagnostics.SdkState.FAILED -> {
                val err = TrackerDiagnostics.sdkError.orEmpty()
                if (err.isBlank()) "Samsung SDK error — try again"
                else "Samsung SDK: ${err.take(40)}"
            }
            TrackerDiagnostics.SdkState.UNKNOWN ->
                "Ready — start on phone"
        }
    }
}
