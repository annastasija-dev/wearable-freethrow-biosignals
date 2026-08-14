package lt.vilniustech.basketball.watch

object MessagePaths {
    const val RAW_PREFIX = "/basketball/raw/"
    const val CAPABILITIES = "/basketball/capabilities"
    const val START = "/basketball/start"
    const val STOP = "/basketball/stop"
    const val ACK = "/basketball/ack"
    const val WATCH_READY = "/basketball/watch_ready"

    /** Legacy accelerometer path (still accepted on phone). */
    const val IMU = "/basketball/imu"

    fun raw(stream: String): String = "$RAW_PREFIX$stream"
}
