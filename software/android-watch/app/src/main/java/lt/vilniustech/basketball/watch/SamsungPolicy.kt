package lt.vilniustech.basketball.watch

import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException

object SamsungPolicy {
    fun isPolicyBlocked(exception: HealthTrackerException): Boolean {
        val msg = exception.message.orEmpty()
        return msg.contains("SDK_POLICY", ignoreCase = true) ||
            msg.contains("POLICY_ERROR", ignoreCase = true)
    }

    fun isPolicyBlocked(error: HealthTracker.TrackerError): Boolean =
        error == HealthTracker.TrackerError.SDK_POLICY_ERROR

    fun describe(exception: HealthTrackerException): String {
        val msg = exception.message.orEmpty().ifBlank { exception.toString() }
        return "code=${exception.errorCode} $msg"
    }
}
