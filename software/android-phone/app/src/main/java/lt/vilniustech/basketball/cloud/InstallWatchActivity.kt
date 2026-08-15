package lt.vilniustech.basketball.cloud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * Simple install: Wi‑Fi + Wireless debugging ON → one button.
 * IP/ports discovered automatically. Pairing code field only if required once.
 */
class InstallWatchActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var inputPairCode: EditText
    private lateinit var installButton: MaterialButton

    private var installJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_install_watch)
        val toolbar = findViewById<MaterialToolbar>(R.id.installToolbar)
        setSupportActionBar(toolbar)

        val root = findViewById<View>(R.id.installRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            toolbar.setPadding(0, bars.top, 0, 0)
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        statusText = findViewById(R.id.installStatusText)
        inputPairCode = findViewById(R.id.inputPairCode)
        installButton = findViewById(R.id.installWatchButton)

        statusText.text = ""
        installButton.setOnClickListener { startWifiInstall() }
        findViewById<MaterialButton>(R.id.closeInstallButton).setOnClickListener { finish() }

        ensureNotificationChannel()
        val needed = mutableListOf<String>()
        fun need(p: String) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed += p
        }
        if (Build.VERSION.SDK_INT >= 33) {
            need(Manifest.permission.POST_NOTIFICATIONS)
            need(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        need(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startWifiInstall() {
        installJob?.cancel()
        installJob = lifecycleScope.launch {
            installButton.isEnabled = false
            hideKeyboard()
            val code = inputPairCode.text?.toString()?.trim().orEmpty()
            if (code.length < 5) {
                setStatus(getString(R.string.install_watch_need_code_once))
                cancelProgressNotification()
                installButton.isEnabled = true
                return@launch
            }

            setStatus(getString(R.string.install_watch_preparing))
            showProgressNotification(getString(R.string.install_notif_progress_body))

            val already = withContext(Dispatchers.IO) {
                WearNodes.ftWatchCapabilityNodes(this@InstallWatchActivity)
            }
            if (already.isNotEmpty()) {
                WatchWifiInstaller.markInstalled(this@InstallWatchActivity)
                setStatus(getString(R.string.install_watch_already_ok))
                cancelProgressNotification()
                showDoneNotification()
                installButton.isEnabled = true
                return@launch
            }

            val apk = withContext(Dispatchers.IO) { extractEmbeddedWatchApk() }
            if (apk == null) {
                setStatus(getString(R.string.install_watch_apk_missing))
                cancelProgressNotification()
                installButton.isEnabled = true
                return@launch
            }

            setStatus(getString(R.string.install_watch_searching))
            showProgressNotification(getString(R.string.install_notif_sending_wifi))

            val result = withTimeoutOrNull(40_000L) {
                withContext(Dispatchers.IO) {
                    WatchWifiInstaller.installAuto(
                        this@InstallWatchActivity,
                        apk,
                        code,
                    ) { msg -> runOnUiThread { setStatus(msg) } }
                }
            } ?: WatchWifiInstaller.Result(
                false,
                getString(R.string.install_watch_timeout),
                needsPairCode = true,
            )

            if (!result.ok && result.needsPairCode) {
                setStatus(result.message.ifBlank { getString(R.string.install_watch_need_code_once) })
                cancelProgressNotification()
                installButton.isEnabled = true
                return@launch
            }

            if (result.ok) {
                WatchWifiInstaller.markInstalled(this@InstallWatchActivity)
            }

            if (!result.ok) {
                val shown = if (result.message.contains("ssl", ignoreCase = true) ||
                    result.message.contains("SSL")
                ) {
                    getString(R.string.install_watch_ssl_hint)
                } else {
                    getString(R.string.install_watch_wifi_failed, result.message)
                }
                setStatus(shown)
                cancelProgressNotification()
                installButton.isEnabled = true
                return@launch
            }

            setStatus(getString(R.string.install_watch_waiting_after_send))
            showProgressNotification(getString(R.string.install_notif_look_watch))

            repeat(12) { tick ->
                delay(1500)
                val present = withContext(Dispatchers.IO) {
                    WearNodes.ftWatchCapabilityNodes(this@InstallWatchActivity)
                }
                if (present.isNotEmpty()) {
                    setStatus(getString(R.string.install_watch_success))
                    cancelProgressNotification()
                    showDoneNotification()
                    Toast.makeText(this@InstallWatchActivity, R.string.install_watch_success, Toast.LENGTH_LONG).show()
                    installButton.isEnabled = true
                    return@launch
                }
                setStatus(
                    getString(R.string.install_watch_waiting_after_send) + "\n" +
                        getString(R.string.install_watch_waiting_confirm, (tick + 1) * 2),
                )
            }

            setStatus(getString(R.string.install_watch_sent_open_app, result.message))
            cancelProgressNotification()
            showDoneNotification()
            installButton.isEnabled = true
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(inputPairCode.windowToken, 0)
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
        android.util.Log.i(TAG, msg.replace('\n', ' '))
    }

    private fun extractEmbeddedWatchApk(): File? {
        return runCatching {
            val out = File(cacheDir, "FT-Watch.apk")
            resources.openRawResource(R.raw.wearable_app).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            if (out.length() < 1_000_000L) null else out
        }.getOrNull()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.install_notif_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun showProgressNotification(body: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.install_notif_progress_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_PROGRESS, notification)
    }

    private fun cancelProgressNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_PROGRESS)
    }

    private fun showDoneNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.install_notif_title))
            .setContentText(getString(R.string.install_notif_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_DONE, notification)
    }

    companion object {
        private const val TAG = "InstallWatch"
        private const val CHANNEL_ID = "ft_watch_install"
        private const val NOTIF_PROGRESS = 5102
        private const val NOTIF_DONE = 5103
    }
}
