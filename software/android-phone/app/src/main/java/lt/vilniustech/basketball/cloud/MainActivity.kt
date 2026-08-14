package lt.vilniustech.basketball.cloud



import android.content.Intent

import android.os.Bundle

import android.os.Handler

import android.os.Looper

import android.view.Menu

import android.view.MenuItem

import android.widget.ArrayAdapter
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

import androidx.core.view.WindowCompat

import androidx.core.view.WindowInsetsCompat

import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import lt.vilniustech.basketball.cloud.databinding.ActivityMainBinding

import java.time.Instant



class MainActivity : AppCompatActivity() {



    private lateinit var binding: ActivityMainBinding

    private var lastParticipantCode: String = ""

    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {

        override fun run() {

            if (SessionCoordinator.sessionState.active) {
                binding.streamCounterText.text = formatStreamCounters()
                val ack = WearableRawBridge.watchAckSessionId
                val samples = maxOf(
                    SessionCoordinator.ingestedSampleCount(),
                    WearableRawBridge.totalSamples(),
                )
                val (label, ok) = when {
                    samples > 0L -> getString(R.string.watch_wifi_active) to true
                    ack != null -> getString(R.string.watch_recording_ack) to true
                    !WearNodes.watchAppPresent -> getString(R.string.watch_app_closed) to false
                    PhoneWifiRelay.watchReachableRecently() -> getString(R.string.watch_wifi_active) to true
                    else -> getString(R.string.watch_waiting_ack) to false
                }
                setWatchStatus(label, ok)
            }

            handler.postDelayed(this, 1000)

        }

    }



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.overflowIcon = ContextCompat.getDrawable(this, R.drawable.ic_more_vert_white)

        applySystemBarInsets()



        SessionCoordinator.onSessionChanged = { runOnUiThread { updateUi() } }

        SessionCoordinator.onStreamsChanged = { runOnUiThread { updateUi() } }



        updateConnectionLabel()

        updateUi()

        setupParticipantDropdowns()

        binding.startButton.setOnClickListener { startSession() }
        binding.hitButton.setOnClickListener { labelShot("hit") }

        binding.missButton.setOnClickListener { labelShot("miss") }

        binding.finishButton.setOnClickListener { finishSession() }

        binding.testCloudButton.setOnClickListener { testCloudConnection() }

    }



    override fun onDestroy() {

        if (isFinishing) {

            SessionCoordinator.onSessionChanged = null

            SessionCoordinator.onStreamsChanged = null

        }

        super.onDestroy()

    }



    override fun onCreateOptionsMenu(menu: Menu): Boolean {

        menuInflater.inflate(R.menu.main_menu, menu)

        return true

    }



    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        return when (item.itemId) {

            R.id.action_install_watch -> {
                startActivity(Intent(this, InstallWatchActivity::class.java))
                true
            }

            R.id.action_settings -> {

                startActivity(Intent(this, SettingsActivity::class.java))

                true

            }

            else -> super.onOptionsItemSelected(item)

        }

    }



    override fun onResume() {

        super.onResume()

        lifecycleScope.launch {

            val bootstrap = withContext(Dispatchers.IO) {

                CloudUrlResolver.bootstrap(this@MainActivity)

            }

            updateConnectionLabel()

            val watches = withContext(Dispatchers.IO) {

                WearableRawBridge.connectedWatchCount(this@MainActivity)

            }

            setWatchStatus(
                watchStatusLabel(watches),
                watches > 0 || PhoneWifiRelay.watchReachableRecently(),
            )

            if (bootstrap.repaired) {

                toast(bootstrap.message)

            }

        }

        handler.post(refreshRunnable)

    }



    override fun onPause() {

        handler.removeCallbacks(refreshRunnable)

        super.onPause()

    }



    private fun updateConnectionLabel() {
        binding.cloudUrlText.visibility = android.view.View.GONE
    }



    private fun setupParticipantDropdowns() {
        val sexAdapter = ArrayAdapter(
            this,
            R.layout.item_dropdown,
            resources.getStringArray(R.array.sex_options),
        )
        binding.sexInput.threshold = 0
        binding.sexInput.setAdapter(sexAdapter)
        binding.sexInput.setDropDownBackgroundResource(R.drawable.popup_dropdown_bg)
        binding.sexInput.dropDownVerticalOffset = 8
        binding.sexInput.setOnClickListener { binding.sexInput.showDropDown() }
        binding.sexInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.sexInput.showDropDown()
        }

        val techniqueAdapter = ArrayAdapter(
            this,
            R.layout.item_dropdown,
            resources.getStringArray(R.array.throw_technique_options),
        )
        binding.throwTechniqueInput.threshold = 0
        binding.throwTechniqueInput.setAdapter(techniqueAdapter)
        binding.throwTechniqueInput.setDropDownBackgroundResource(R.drawable.popup_dropdown_bg)
        binding.throwTechniqueInput.dropDownVerticalOffset = 8
        binding.throwTechniqueInput.setOnClickListener { binding.throwTechniqueInput.showDropDown() }
        binding.throwTechniqueInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.throwTechniqueInput.showDropDown()
        }
    }

    private fun startSession() {

        val participant = binding.participantInput.text.toString().trim().uppercase()
        val weightKg = binding.weightInput.text.toString().trim().replace(',', '.').toDoubleOrNull()
        val heightCm = binding.heightInput.text.toString().trim().toIntOrNull()
        val ageYears = binding.ageInput.text.toString().trim().toIntOrNull()
        val sex = binding.sexInput.text.toString().trim()
        val throwTechnique = binding.throwTechniqueInput.text.toString().trim()

        if (participant.isEmpty()) {
            toast(getString(R.string.error_participant))
            return
        }
        if (weightKg == null || weightKg <= 0.0 || weightKg > 300.0) {
            toast(getString(R.string.error_weight))
            return
        }
        if (heightCm == null || heightCm < 100 || heightCm > 250) {
            toast(getString(R.string.error_height))
            return
        }
        if (ageYears == null || ageYears < 10 || ageYears > 100) {
            toast(getString(R.string.error_age))
            return
        }
        if (sex.isEmpty()) {
            toast(getString(R.string.error_sex))
            return
        }
        if (throwTechnique.isEmpty()) {
            toast(getString(R.string.error_throw_technique))
            return
        }

        val profile = ParticipantProfile(
            weightKg = weightKg,
            heightCm = heightCm,
            ageYears = ageYears,
            sex = sex,
            throwTechnique = throwTechnique,
        )

        lifecycleScope.launch {

            runCatching {

                val watchNodes = withContext(Dispatchers.IO) {
                    WearableRawBridge.connectedWatchCount(this@MainActivity)
                }
                val ftWatchNodes = withContext(Dispatchers.IO) {
                    WearNodes.ftWatchCapabilityNodes(this@MainActivity)
                }

                if (watchNodes == 0 && !PhoneWifiRelay.watchReachableRecently()) {
                    toast(getString(R.string.watch_not_connected))
                } else if (ftWatchNodes.isEmpty()) {
                    toast(getString(R.string.watch_app_closed_toast))
                    val neverInstalled = !WatchWifiInstaller.isInstalled(this@MainActivity) &&
                        watchNodes == 0
                    if (neverInstalled) {
                        startActivity(Intent(this@MainActivity, InstallWatchActivity::class.java))
                    }
                }

                withContext(Dispatchers.IO) {

                    CloudUrlResolver.bootstrap(this@MainActivity)

                    SessionCoordinator.apiClient().startSession(

                        participantCode = participant,

                        watchModel = WearableRawBridge.watchModel(this@MainActivity),

                        wrist = "right",

                        phoneModel = android.os.Build.MODEL,

                        profile = profile,

                    )

                }

            }.onSuccess { response ->

                lastParticipantCode = participant

                WearableRawBridge.resetCounters()

                SessionCoordinator.localStore().onSessionStarted(
                    response.sessionId,
                    participant,
                    profile,
                )

                SessionCoordinator.bindSession(

                    SessionState(

                        active = true,

                        sessionId = response.sessionId,

                        participantCode = participant,

                        shotsDone = 0,

                        shotsTarget = response.shotsTarget,

                    ),

                )

                lifecycleScope.launch {
                    val sessionId = response.sessionId
                    withContext(Dispatchers.IO) {
                        SessionDataSync.publishRecording(this@MainActivity, sessionId)
                    }
                    val launched = WatchLauncher.launchRecordingSession(this@MainActivity, sessionId)
                    val messaged = WatchLauncher.sendStartMessage(this@MainActivity, sessionId)
                    WearableRawBridge.sendStartRecording(this@MainActivity, sessionId)
                    if (!launched && !messaged) {
                        toast(getString(R.string.watch_start_failed))
                    }
                    repeat(8) { attempt ->
                        delay(2000)
                        if (WearableRawBridge.totalSamples() > 0L) return@launch
                        withContext(Dispatchers.IO) {
                            SessionDataSync.publishRecording(this@MainActivity, sessionId)
                        }
                        WatchLauncher.launchRecordingSession(this@MainActivity, sessionId)
                        WatchLauncher.sendStartMessage(this@MainActivity, sessionId)
                        WearableRawBridge.sendStartRecording(this@MainActivity, sessionId)
                        android.util.Log.i("MainActivity", "re-sent watch start attempt ${attempt + 2}")
                    }
                }

                setParticipantFieldsEnabled(false)

                updateUi()

                toast(getString(R.string.session_started, formatSessionLabel(response.sessionId)))

            }.onFailure {

                toast(getString(R.string.start_failed, it.message ?: "unknown"))

            }

        }

    }


    private fun labelShot(result: String) {

        val sessionState = SessionCoordinator.sessionState

        if (!sessionState.active) {

            toast(getString(R.string.start_first))

            return

        }

        if (sessionState.shotsDone >= sessionState.shotsTarget) {

            toast(getString(R.string.all_shots_done))

            return

        }



        lifecycleScope.launch {

            runCatching {

                val timestamp = Instant.now()

                val shotNo = sessionState.shotsDone + 1

                SessionCoordinator.localStore().appendShot(

                    sessionState.sessionId,

                    shotNo,

                    result,

                    timestamp.toString(),

                )

                withContext(Dispatchers.IO) {

                    SessionCoordinator.apiClient().labelShot(

                        sessionId = sessionState.sessionId,

                        result = result,

                        clientTimestamp = timestamp,

                        shotNo = shotNo,

                    )

                }

            }.onSuccess { response ->

                SessionCoordinator.bindSession(sessionState.copy(shotsDone = response.shotNo))

                if (response.done) {

                    toast(getString(R.string.shots_complete))

                }

            }.onFailure {

                toast(getString(R.string.label_failed, it.message ?: "unknown"))

            }

        }

    }



    private fun finishSession() {

        val sessionState = SessionCoordinator.sessionState

        if (!sessionState.active) return



        lifecycleScope.launch {

            runCatching {

                SessionCoordinator.beginFinishing(sessionState.sessionId)

                delay(15000)

                withContext(Dispatchers.IO) {
                    SessionDataSync.publishStopped(this@MainActivity)
                    WearableRawBridge.sendStopRecording(this@MainActivity)
                }

                val pending = SessionCoordinator.rawUploader().bufferedSampleCount()

                if (pending > 0) {

                    toast(getString(R.string.session_upload_pending, pending))

                }

                withContext(Dispatchers.IO) {
                    SessionCoordinator.rawUploader().awaitPendingUploads()
                    runCatching {
                        SessionCoordinator.apiClient().finishSession(sessionState.sessionId)
                    }.onFailure { finishError ->
                        val summary = runCatching {
                            SessionCoordinator.apiClient().fetchSessionSummary(sessionState.sessionId)
                        }.getOrNull()
                        if (summary?.endedAt.isNullOrBlank()) {
                            throw finishError
                        }
                    }
                }

                val localSamples = SessionCoordinator.ingestedSampleCount()
                val wearableSamples = WearableRawBridge.totalSamples()
                val cloudSamples = withContext(Dispatchers.IO) {
                    var samples = 0L
                    repeat(5) { attempt ->
                        samples = runCatching {
                            SessionCoordinator.apiClient()
                                .fetchSessionSummary(sessionState.sessionId)
                                .rawSamples
                                .toLong()
                        }.getOrDefault(0L)
                        if (samples > 0L) return@withContext samples
                        if (attempt < 4) delay(2000)
                    }
                    samples
                }
                FinishStats(
                    watchSamples = maxOf(localSamples, wearableSamples, cloudSamples),
                    uploadError = SessionCoordinator.rawUploader().lastUploadError,
                )

            }.onSuccess { stats ->

                val uploadError = stats.uploadError
                val watchSamples = stats.watchSamples

                SessionCoordinator.clearSession()

                setParticipantFieldsEnabled(true)

                updateUi()
                when {

                    uploadError != null -> toast(getString(R.string.upload_error, uploadError))

                    watchSamples == 0L -> toast(getString(R.string.session_uploaded_no_watch))

                    else -> toast(getString(R.string.session_uploaded))

                }

            }.onFailure { error ->
                lifecycleScope.launch {
                    val recovered = withContext(Dispatchers.IO) {
                        runCatching {
                            SessionCoordinator.apiClient()
                                .fetchSessionSummary(sessionState.sessionId)
                                .endedAt
                                ?.isNotBlank() == true
                        }.getOrDefault(false)
                    }
                    if (recovered) {
                        SessionCoordinator.clearSession()
                        setParticipantFieldsEnabled(true)
                        updateUi()
                        toast(getString(R.string.session_uploaded))                    } else {
                        toast(getString(R.string.finish_failed, error.message ?: "unknown"))
                    }
                }
            }

        }

    }



    private fun testCloudConnection() {

        lifecycleScope.launch {

            runCatching {

                withContext(Dispatchers.IO) {

                    CloudUrlResolver.bootstrap(this@MainActivity)

                    SessionCoordinator.apiClient().healthCheck()

                }

            }.onSuccess {

                updateConnectionLabel()

                toast(getString(R.string.cloud_ok))

            }.onFailure {

                toast(getString(R.string.cloud_fail, it.message ?: "unknown"))

            }

        }

    }



    private fun setWatchStatus(label: String, connected: Boolean) {
        binding.watchStatusText.text = label
        val bg = if (connected) R.color.watch_ok_bg else R.color.watch_off_bg
        val fg = if (connected) R.color.watch_ok_text else R.color.watch_off_text
        binding.watchStatusText.chipBackgroundColor =
            android.content.res.ColorStateList.valueOf(getColor(bg))
        binding.watchStatusText.setTextColor(getColor(fg))
        binding.watchStatusText.chipIconTint =
            android.content.res.ColorStateList.valueOf(getColor(fg))
    }

    private fun watchStatusLabel(wearNodes: Int): String {
        return when {
            wearNodes > 0 -> getString(R.string.watch_connected)
            PhoneWifiRelay.watchReachableRecently() -> getString(R.string.watch_wifi_active)
            else -> getString(R.string.watch_wifi_ready)
        }
    }

    private fun updateUi() {

        val sessionState = SessionCoordinator.sessionState

        val activeStreams = SessionCoordinator.activeStreams

        val active = sessionState.active

        binding.startButton.isEnabled = !active

        binding.hitButton.isEnabled = active && sessionState.shotsDone < sessionState.shotsTarget

        binding.missButton.isEnabled = active && sessionState.shotsDone < sessionState.shotsTarget

        binding.finishButton.isEnabled = active

        if (!active) {
            setParticipantFieldsEnabled(true)
        }


        binding.participantBanner.visibility = if (active) {

            android.view.View.VISIBLE

        } else {

            android.view.View.GONE

        }



        if (active) {

            binding.activeParticipantText.text = getString(

                R.string.active_participant,

                sessionState.participantCode,

            )

            binding.activeSessionText.text = formatSessionLabel(sessionState.sessionId)

            binding.activeStreamsText.text = if (activeStreams.isEmpty()) {

                getString(R.string.streams_waiting)

            } else {

                getString(R.string.streams_active, activeStreams.joinToString(", "))

            }

        }



        if (!active && lastParticipantCode.isNotEmpty()) {

            binding.lastParticipantText.visibility = android.view.View.VISIBLE

            binding.lastParticipantText.text = getString(

                R.string.last_participant,

                lastParticipantCode,

            )

        } else {

            binding.lastParticipantText.visibility = android.view.View.GONE

        }



        binding.statusChip.text = if (active) {

            getString(R.string.status_recording)

        } else {

            getString(R.string.status_idle)

        }

        binding.statusChip.setChipBackgroundColorResource(

            if (active) R.color.chip_recording_bg else R.color.chip_idle_bg,

        )

        binding.statusChip.setTextColor(

            getColor(if (active) R.color.chip_recording_text else R.color.chip_idle_text),

        )



        binding.statusText.text = if (active) {

            getString(R.string.recording_status, formatSessionLabel(sessionState.sessionId))

        } else {

            getString(R.string.no_session)

        }

        binding.shotCounterText.text = getString(

            R.string.shots_counter,

            sessionState.shotsDone,

            sessionState.shotsTarget,

        )

        binding.streamCounterText.text = formatStreamCounters()

        val uploadError = SessionCoordinator.rawUploader().lastUploadError

        if (uploadError != null && sessionState.active) {

            binding.streamCounterText.text = getString(R.string.upload_error, uploadError)

        }

    }



    private fun formatStreamCounters(): String {

        val counts = linkedMapOf<String, Long>()
        SessionCoordinator.streamCounts().forEach { (stream, count) ->
            counts[stream] = count
        }
        WearableRawBridge.streamCounts().forEach { (stream, count) ->
            counts[stream] = maxOf(counts[stream] ?: 0L, count)
        }

        if (counts.isEmpty()) {

            return getString(R.string.stream_counter_empty)

        }

        val labels = mapOf(
            "accelerometer" to "acc",
            "heart_rate" to "hr",
            "ppg" to "ppg",
            "skin_temperature" to "temp",
            "eda" to "eda",
        )
        return counts.entries
            .sortedBy { it.key }
            .joinToString(" · ") { (stream, count) ->
                getString(R.string.stream_counter_item, labels[stream] ?: stream, count)
            }

    }



    private fun applySystemBarInsets() {

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { view, insets ->

            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.setPadding(0, systemBars.top, 0, 0)

            insets

        }

        ViewCompat.requestApplyInsets(binding.appBar)

    }



    private fun setParticipantFieldsEnabled(enabled: Boolean) {
        binding.participantInput.isEnabled = enabled
        binding.weightInput.isEnabled = enabled
        binding.heightInput.isEnabled = enabled
        binding.ageInput.isEnabled = enabled
        binding.sexInput.isEnabled = enabled
        binding.throwTechniqueInput.isEnabled = enabled
    }

    private fun formatSessionLabel(sessionId: String): String {

        val match = SESSION_ID_PATTERN.matchEntire(sessionId.trim())

        if (match != null) {

            val participant = match.groupValues[1]

            val date = match.groupValues[2]

            val time = match.groupValues[3]

            val suffix = match.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }

            val formattedDate = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}"

            val formattedTime = "${time.substring(0, 2)}:${time.substring(2, 4)}"

            return if (suffix != null) {

                "$participant · $formattedDate $formattedTime ($suffix)"

            } else {

                "$participant · $formattedDate $formattedTime"

            }

        }

        return sessionId

    }



    private fun toast(message: String) {

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    }



    private data class FinishStats(
        val watchSamples: Long,
        val uploadError: String?,
    )

    companion object {

        private val SESSION_ID_PATTERN = Regex("""^([A-Z0-9_-]+)_(\d{8})_(\d{4})(?:_(\d{2}))?$""")

    }

}


