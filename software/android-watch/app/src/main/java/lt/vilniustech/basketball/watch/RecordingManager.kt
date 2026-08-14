package lt.vilniustech.basketball.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

object RecordingManager {
    private const val TAG = "RecordingManager"
    private val trackers = mutableListOf<SensorTracker>()
    private var active = false
    private var sessionId: String = ""
    private var mode: String = ""
    private var supportedStreams = mutableSetOf<String>()
    private var activeStreams = mutableSetOf<String>()
    private val localStreamCounts = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())
    private var sampleWatchdog: Runnable? = null
    private var flushRunnable: Runnable? = null
    private var capabilitiesUploaded = false

    fun isRecording(): Boolean = active

    fun sessionId(): String = sessionId

    fun mode(): String = mode

    fun activeStreams(): List<String> = activeStreams.toList()

    fun samplesSent(): Long =
        RawBatchSync.samplesSent() + PhoneWifiRelay.samplesSent() + CloudDirectUploader.samplesSent()

    fun localSamples(): Long = localStreamCounts.values.sum()

    fun streamCounts(): Map<String, Long> = PhoneRelay.streamCounts()

    fun localStreamCounts(): Map<String, Long> = localStreamCounts.toMap()

    fun start(context: Context, newSessionId: String) {
        Log.i(TAG, "start session=$newSessionId")
        CloudDirectUploader.ensureConfigured()
        stopInternal(context, flush = false)
        sessionId = newSessionId
        active = true
        CloudDirectUploader.bindSession(newSessionId)
        PhoneRelay.resetCounter()
        PhoneWifiRelay.resetCounter()
        CloudDirectUploader.resetCounter()
        RawBatchSync.resetCounter()
        CloudDirectUploader.bindSession(newSessionId)
        localStreamCounts.clear()
        supportedStreams.clear()
        activeStreams.clear()
        capabilitiesUploaded = false
        TrackerDiagnostics.sdkError = null

        val app = context.applicationContext
        val modes = mutableListOf<String>()

        // Samsung SDK first — full biomedical streams when Dev mode is on.
        runCatching {
            val samsung = SensorTrackerFactory.create(app)
            if (samsung.modeName == "SAMSUNG_SDK") {
                samsung.start(
                    onSample = sampleCallback(app),
                    onCapabilities = capabilitiesCallback(app),
                )
                trackers += samsung
                modes += samsung.modeName
                Log.i(TAG, "samsung tracker started")
            } else {
                TrackerDiagnostics.sdkError = "Samsung SDK: ${samsung.modeName}"
            }
        }.onFailure {
            Log.w(TAG, "samsung tracker failed: ${it.message}")
            TrackerDiagnostics.sdkError = it.message
        }

        runCatching {
            val android = AndroidSensorTracker(app)
            android.start(
                onSample = sampleCallback(app),
                onCapabilities = capabilitiesCallback(app),
            )
            trackers += android
            modes += android.modeName
            Log.i(TAG, "android tracker started")
        }.onFailure {
            Log.w(TAG, "android tracker failed: ${it.message}")
        }

        mode = modes.joinToString("+").ifBlank { "NONE" }
        scheduleSampleWatchdog()
        schedulePeriodicFlush(app)
        scheduleCapabilitiesRefresh(app)
        PhoneRelay.probePhoneNodes(app)
        Log.i(TAG, "recording active mode=$mode trackers=${trackers.size}")
    }

    private fun scheduleCapabilitiesRefresh(context: Context) {
        handler.postDelayed({
            if (!active) return@postDelayed
            publishCapabilities(context)
        }, 3000)
    }

    private fun schedulePeriodicFlush(context: Context) {
        flushRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (!active) return
                trackers.forEach { runCatching { it.flush() } }
                PhoneWifiRelay.flushAsync()
                CloudDirectUploader.flushAll(sessionId)
                RawBatchSync.flushAll(context.applicationContext)
                handler.postDelayed(this, 500)
            }
        }
        flushRunnable = runnable
        handler.post(runnable)
    }

    private fun scheduleSampleWatchdog() {
        sampleWatchdog?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!active) return@Runnable
            if (localSamples() == 0L) {
                TrackerDiagnostics.sdkError = TrackerDiagnostics.sdkError
                    ?: "Sensors not working — check permissions"
                Log.w(TAG, "no samples after watchdog")
            }
        }
        sampleWatchdog = runnable
        handler.postDelayed(runnable, 5_000)
    }

    private fun sampleCallback(context: Context): (String, org.json.JSONObject) -> Unit = { stream, payload ->
        localStreamCounts[stream] = (localStreamCounts[stream] ?: 0L) + 1L
        val firstForStream = localStreamCounts[stream] == 1L
        if (localStreamCounts.values.sum() == 1L || firstForStream) {
            Log.i(TAG, "first sample stream=$stream")
            TrackerDiagnostics.sdkError = null
            activeStreams.add(stream)
            publishCapabilities(context)
        }
        RawBatchSync.enqueue(context.applicationContext, stream, payload)
        // Bluetooth/Wear Message-per-sample floods Samsung and drops delivery.
        // Data Layer batches + optional cloud backup are the reliable paths.
        CloudDirectUploader.enqueueSample(stream, payload)
    }

    private fun capabilitiesCallback(context: Context): (CapabilitiesReport) -> Unit = { report ->
        supportedStreams.addAll(report.supportedStreams)
        activeStreams.addAll(report.activeStreams)
        publishCapabilities(context)
    }

    private fun publishCapabilities(context: Context) {
        if (sessionId.isBlank() || activeStreams.isEmpty()) return
        val report = CapabilitiesReport(
            watchModel = android.os.Build.MODEL.ifBlank { "Galaxy Watch" },
            sdkVersion = if (mode.contains("SAMSUNG_SDK")) "1.4.1" else "android",
            supportedStreams = supportedStreams.sorted(),
            activeStreams = activeStreams.sorted(),
        )
        CloudDirectUploader.uploadCapabilities(report, sessionId)
        PhoneRelay.sendCapabilities(context.applicationContext, report)
        capabilitiesUploaded = true
    }

    fun stop(context: Context) {
        stopInternal(context, flush = true)
    }

    private fun stopInternal(context: Context, flush: Boolean) {
        val endingSession = sessionId
        if (flush && endingSession.isNotBlank()) {
            RawBatchSync.flushAll(context.applicationContext)
            PhoneWifiRelay.flushAsync()
            CloudDirectUploader.flushAll(endingSession)
            CloudDirectUploader.awaitPendingUploads()
        }
        sampleWatchdog?.let { handler.removeCallbacks(it) }
        sampleWatchdog = null
        flushRunnable?.let { handler.removeCallbacks(it) }
        flushRunnable = null
        trackers.forEach { runCatching { it.stop() } }
        trackers.clear()
        active = false
        sessionId = ""
        activeStreams.clear()
        supportedStreams.clear()
        localStreamCounts.clear()
        capabilitiesUploaded = false
        Log.i(TAG, "recording stopped session=$endingSession")
    }
}
