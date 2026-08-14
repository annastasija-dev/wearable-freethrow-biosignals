package lt.vilniustech.basketball.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.MessageEvent

object WatchCommands {
    private const val TAG = "WatchCommands"
    const val EXTRA_SESSION_ID = "session_id"
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun appContext(): Context = appContext

    fun handleMessage(event: MessageEvent) {
        Log.i(TAG, "rx path=${event.path}")
        when (event.path) {
            MessagePaths.START, "/basketball/start" -> startSession(event.data.decodeToString())
            MessagePaths.STOP, "/basketball/stop" -> stopSession()
        }
    }

    fun handleLaunchIntent(context: Context, intent: Intent?) {
        if (intent == null) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?: intent.data?.getQueryParameter("session")
        if (!sessionId.isNullOrBlank()) {
            Log.i(TAG, "launch intent session=$sessionId")
            startSession(sessionId, context)
        }
    }

    fun startSession(sessionId: String, context: Context? = null) {
        if (sessionId.isBlank()) return
        if (RecordingManager.isRecording() && RecordingManager.sessionId() == sessionId) return
        Log.i(TAG, "start session=$sessionId")
        val app = (context ?: appContext).applicationContext
        WatchLinkService.ensureStarted(app)
        RecordingManager.start(app, sessionId)
        WatchStatusSync.publishRecording(app, sessionId)
        bringMainActivityToFront(app)
        startRecordingService(app, sessionId)
        WatchAck.sendRecordingStarted(app, sessionId)
        toast(app, app.getString(R.string.status_recording))
    }

    fun stopSession(context: Context? = null) {
        Log.i(TAG, "stop session scheduled")
        val app = (context ?: appContext).applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            RecordingManager.stop(app)
            WatchStatusSync.publishStopped(app)
            runCatching { RecordingForegroundService.stop(app) }
        }, 8000)
    }

    fun sessionUri(sessionId: String): Uri =
        Uri.parse("ftwatch://start?session=${Uri.encode(sessionId)}")

    private fun startRecordingService(context: Context, sessionId: String) {
        runCatching { RecordingForegroundService.start(context, sessionId) }
            .onFailure { error ->
                Log.w(TAG, "foreground service failed: ${error.message}")
                WatchLinkService.ensureStarted(context)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { RecordingForegroundService.start(context, sessionId) }
                        .onFailure { retry ->
                            Log.w(TAG, "foreground service retry failed: ${retry.message}")
                        }
                }, 500)
            }
    }

    private fun bringMainActivityToFront(context: Context) {
        runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "bring ui failed: ${it.message}")
        }
    }

    private fun toast(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
