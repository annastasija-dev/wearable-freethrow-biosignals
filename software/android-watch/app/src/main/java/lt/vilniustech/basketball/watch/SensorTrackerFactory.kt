package lt.vilniustech.basketball.watch

import android.content.Context

object SensorTrackerFactory {
    fun create(context: Context): SensorTracker {
        if (!BuildConfig.HAS_SAMSUNG_SDK) {
            return ErrorTracker(
                modeName = "NO_SDK",
                message = "Samsung Health Sensor SDK AAR missing",
            )
        }
        return runCatching {
            val cls = Class.forName("lt.vilniustech.basketball.watch.SamsungHealthMultiTracker")
            cls.getConstructor(Context::class.java)
                .newInstance(context.applicationContext) as SensorTracker
        }.getOrElse { error ->
            ErrorTracker(
                modeName = "SDK_ERROR",
                message = error.message ?: "Samsung SDK init failed",
            )
        }
    }
}
