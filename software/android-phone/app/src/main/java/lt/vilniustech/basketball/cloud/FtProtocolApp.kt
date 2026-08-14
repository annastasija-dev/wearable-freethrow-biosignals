package lt.vilniustech.basketball.cloud

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FtProtocolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionCoordinator.init(this)
    }
}

object SessionCoordinator {
    private const val TAG = "SessionCoordinator"
    private lateinit var appContext: Context
    private lateinit var rawUploader: RawUploadManager
    private lateinit var localStore: LocalSessionStore

    @Volatile
    var sessionState: SessionState = SessionState()
        private set

    @Volatile
    var activeStreams: List<String> = emptyList()
        private set

    @Volatile
    private var finishingSessionId: String? = null

    private val ingestedSampleCount = java.util.concurrent.atomic.AtomicLong(0)
    private val streamCounts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    var onSessionChanged: (() -> Unit)? = null
    var onStreamsChanged: (() -> Unit)? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        PhoneWifiRelay.ensureStarted(appContext)
        localStore = LocalSessionStore(appContext)
        rawUploader = RawUploadManager(
            apiFactory = {
                CloudApiClient(
                    baseUrl = AppConfig.cloudBaseUrl(appContext),
                    apiKey = AppConfig.apiKey(appContext),
                )
            },
        )
        WearableRawBridge.start(
            context = appContext,
            onSample = { sample -> ingestWatchSample(sample) },
            onCapabilities = { report ->
                if (sessionState.active) {
                    activeStreams = report.activeStreams
                    onStreamsChanged?.invoke()
                    rawUploader.uploadCapabilities(sessionState.sessionId, report)
                }
            },
        )
        WearableRawBridge.onWatchReady = { ctx -> pushActiveSessionToWatch(ctx) }
        Wearable.getDataClient(appContext).addListener { events ->
            RawBatchSync.handleDataEvents(events) { sample -> ingestWatchSample(sample) }
            WatchStatusSync.handleDataEvents(events)
        }
        startSessionPushLoop()
        Log.i(TAG, "initialized wearable bridge")
        advertisePhoneCapability(appContext)
        wakeWearableLink(appContext)
    }

    private fun advertisePhoneCapability(context: Context) {
        // Capability is declared in res/values/wear.xml + wearable_capabilities.xml.
        // addLocalCapability duplicates it and causes 4006 DUPLICATE_CAPABILITY.
        Log.i(TAG, "phone capability declared via wear.xml (basketball_phone)")
    }

    private fun wakeWearableLink(context: Context) {
        Wearable.getCapabilityClient(context)
            .getCapability("basketball_watch", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                Log.i(TAG, "wearable watch capability nodes: ${info.nodes.size}")
            }
        Wearable.getNodeClient(context)
            .connectedNodes
            .addOnSuccessListener { nodes ->
                Log.i(TAG, "wearable nodes visible from phone: ${nodes.size}")
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "wearable node check failed: ${error.message}")
            }
    }

    fun bindSession(state: SessionState) {
        sessionState = state
        finishingSessionId = null
        ingestedSampleCount.set(0)
        streamCounts.clear()
        rawUploader.bindSession(state.sessionId)
        PhoneUdpBeacon.start(appContext, state.sessionId)
        onSessionChanged?.invoke()
        pushActiveSessionToWatch(appContext)
    }

    fun beginFinishing(sessionId: String) {
        finishingSessionId = sessionId
    }

    fun ingestedSampleCount(): Long = ingestedSampleCount.get()

    fun streamCounts(): Map<String, Long> = streamCounts.toMap()

    private var pushJob: kotlinx.coroutines.Job? = null

    fun ingestWatchSample(sample: RawSample) {
        val sessionId = when {
            sessionState.active -> sessionState.sessionId
            finishingSessionId != null -> finishingSessionId.orEmpty()
            else -> ""
        }
        if (sessionId.isBlank()) {
            Log.w(TAG, "sample dropped (no active session): ${sample.stream}")
            return
        }
        ingestedSampleCount.incrementAndGet()
        streamCounts.merge(sample.stream, 1L) { current, _ -> current + 1L }
        WearableRawBridge.trackSample(sample.stream)
        localStore.appendRawSample(sessionId, sample)
        rawUploader.enqueue(sessionId, sample)
        onStreamsChanged?.invoke()
    }

    private fun startSessionPushLoop() {
        pushJob?.cancel()
        pushJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(1000)
                if (sessionState.active) {
                    pushActiveSessionToWatch(appContext)
                }
            }
        }
    }

    private fun pushActiveSessionToWatch(context: Context) {
        if (!sessionState.active || finishingSessionId != null) return
        val sessionId = sessionState.sessionId
        CoroutineScope(Dispatchers.IO).launch {
            SessionDataSync.publishRecording(context, sessionId)
            Log.i(TAG, "pushed session via data layer: $sessionId")
        }
    }

    fun clearSession() {
        PhoneUdpBeacon.stop()
        sessionState = SessionState()
        finishingSessionId = null
        ingestedSampleCount.set(0)
        streamCounts.clear()
        activeStreams = emptyList()
        onSessionChanged?.invoke()
        onStreamsChanged?.invoke()
    }

    fun rawUploader(): RawUploadManager = rawUploader

    fun localStore(): LocalSessionStore = localStore

    fun apiClient(): CloudApiClient = CloudApiClient(
        baseUrl = AppConfig.cloudBaseUrl(appContext),
        apiKey = AppConfig.apiKey(appContext),
    )
}
