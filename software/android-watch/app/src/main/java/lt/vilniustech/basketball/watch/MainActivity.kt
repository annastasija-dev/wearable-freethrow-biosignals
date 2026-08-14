package lt.vilniustech.basketball.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lt.vilniustech.basketball.watch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }
    private val sessionPollRunnable = object : Runnable {
        override fun run() {
            lifecycleScope.launch(Dispatchers.IO) {
                SessionDataSync.readLatest(applicationContext)
                PhoneWifiRelay.pollSession(applicationContext)
                CloudSessionPoller.poll(applicationContext)
            }
            handler.postDelayed(this, 1500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            toast(getString(R.string.permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestSensorPermissions()
        WatchCommands.init(applicationContext)
        WatchLinkService.start(applicationContext)
        WatchUdpListener.ensureStarted(applicationContext)
        WatchCommands.handleLaunchIntent(this, intent)
        updateStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WatchCommands.handleLaunchIntent(this, intent)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        PhoneRelay.probePhoneNodes(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            CloudDiscovery.bootstrap(applicationContext)
            SessionDataSync.readLatest(applicationContext)
            SamsungSdkProbe.probeAsync(applicationContext)
        }
        handler.post(refreshRunnable)
        handler.post(sessionPollRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(sessionPollRunnable)
        super.onPause()
    }

    private fun requestSensorPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.BODY_SENSORS
        }
        if (ContextCompat.checkSelfPermission(
                this,
                "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun updateStatus() {
        val recording = RecordingManager.isRecording()
        if (recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding.statusText.text = if (recording) {
            getString(R.string.status_recording)
        } else {
            getString(R.string.status_ready)
        }

        binding.sessionText.text = if (recording) {
            getString(R.string.session_label, RecordingManager.sessionId()) + "\n" +
                getString(R.string.tracker_label, RecordingManager.mode())
        } else {
            DevModeChecker.refresh(applicationContext)
            getString(R.string.waiting_phone) + " " + getString(R.string.version_label, BuildConfig.VERSION_NAME) +
                "\n" + DevModeChecker.statusMessage(applicationContext)
        }

        val sdkError = TrackerDiagnostics.sdkError
        val relayError = CloudDirectUploader.lastError ?: PhoneWifiRelay.lastError ?: PhoneRelay.lastRelayError
        binding.samplesText.text = when {
            TrackerDiagnostics.isPolicyBlocked() ->
                getString(R.string.sdk_error, TrackerDiagnostics.sdkError?.take(60) ?: "SDK policy")
            sdkError != null && TrackerDiagnostics.sdkState != TrackerDiagnostics.SdkState.POLICY_BLOCKED ->
                getString(R.string.sdk_error, sdkError.take(60))
            relayError != null && recording ->
                getString(R.string.relay_error, relayError.take(60))
            recording -> getString(
                R.string.samples_dual_label,
                RecordingManager.localSamples(),
                RecordingManager.samplesSent(),
            )
            RecordingManager.localSamples() > 0L -> getString(
                R.string.samples_dual_label,
                RecordingManager.localSamples(),
                RecordingManager.samplesSent(),
            )
            else -> getString(R.string.samples_label, RecordingManager.samplesSent())
        }

        val phoneNodes = PhoneRelay.lastConnectedPhoneNodes
        binding.streamsText.text = when {
            !recording -> "-"
            else -> {
                val counts = RecordingManager.localStreamCounts().toMutableMap()
                RecordingManager.activeStreams().forEach { stream ->
                    counts.putIfAbsent(stream, 0L)
                }
                if (counts.isEmpty()) {
                    getString(R.string.streams_waiting)
                } else {
                    val labels = mapOf(
                        "accelerometer" to "acc",
                        "heart_rate" to "hr",
                        "ppg" to "ppg",
                        "skin_temperature" to "temp",
                        "eda" to "eda",
                    )
                    counts.entries.sortedBy { it.key }.joinToString(" · ") { (stream, count) ->
                        "${labels[stream] ?: stream} $count"
                    }
                }
            }
        }
        if (!recording) {
            // no-op
        } else if (phoneNodes == 0 && CloudDirectUploader.samplesSent() == 0L) {
            // Keep UI honest: cloud upload needs watch internet.
            val cloudErr = CloudDirectUploader.lastError
            if (!cloudErr.isNullOrBlank()) {
                binding.streamsText.text = getString(R.string.relay_error, cloudErr.take(60))
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
