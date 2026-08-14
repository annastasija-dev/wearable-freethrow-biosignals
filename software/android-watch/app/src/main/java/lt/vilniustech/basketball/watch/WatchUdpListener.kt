package lt.vilniustech.basketball.watch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.charset.StandardCharsets

/** Listens for phone UDP session beacons on the same Wi-Fi. */
object WatchUdpListener {
    private const val TAG = "WatchUdpListener"
    private const val PORT = 8788
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    fun ensureStarted(context: Context) {
        if (running) return
        running = true
        scope.launch {
            runCatching {
                DatagramSocket(PORT).use { socket ->
                    socket.soTimeout = 0
                    Log.i(TAG, "listening on :$PORT")
                    val buffer = ByteArray(2048)
                    while (isActive && running) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        handlePayload(context.applicationContext, text)
                    }
                }
            }.onFailure {
                Log.e(TAG, "listener failed: ${it.message}")
            }.also {
                running = false
            }
        }
    }

    private fun handlePayload(context: Context, text: String) {
        runCatching {
            val json = JSONObject(text)
            when (json.optString("action")) {
                "start" -> {
                    val sessionId = json.optString("session_id")
                    val cloudUrl = json.optString("cloud_url")
                    val apiKey = json.optString("api_key")
                    val phoneIp = json.optString("phone_ip")
                    if (phoneIp.isNotBlank()) PhoneWifiRelay.rememberPhoneIp(phoneIp, context)
                    CloudDirectUploader.configure(cloudUrl, apiKey)
                    CloudSessionPoller.configure(cloudUrl, apiKey)
                    if (sessionId.isNotBlank()) {
                        Log.i(TAG, "udp start session=$sessionId")
                        WatchCommands.startSession(sessionId, context)
                    }
                }
                "stop" -> {
                    Log.i(TAG, "udp stop")
                    WatchCommands.stopSession(context)
                }
            }
        }.onFailure {
            Log.w(TAG, "bad packet: ${it.message}")
        }
    }
}
