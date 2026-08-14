package lt.vilniustech.basketball.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/** Broadcasts active session over UDP — works when Wear OS and Wi-Fi relay fail. */
object PhoneUdpBeacon {
    private const val TAG = "PhoneUdpBeacon"
    private const val PORT = 8788
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context, sessionId: String) {
        stop()
        val app = context.applicationContext
        job = scope.launch {
            val payload = JSONObject()
                .put("action", "start")
                .put("session_id", sessionId)
                .put("cloud_url", AppConfig.cloudBaseUrl(app))
                .put("api_key", AppConfig.apiKey(app))
                .put("phone_ip", PhoneWifiRelay.localIp(app).orEmpty())
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            while (isActive) {
                broadcast(bytes)
                delay(1000)
            }
        }
        Log.i(TAG, "beacon started session=$sessionId")
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.launch {
            runCatching {
                val bytes = JSONObject().put("action", "stop").toString()
                    .toByteArray(StandardCharsets.UTF_8)
                broadcast(bytes)
            }
        }
        Log.i(TAG, "beacon stopped")
    }

    private fun broadcast(bytes: ByteArray) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val targets = listOf(
                "255.255.255.255",
                "172.18.255.255",
                "172.18.1.255",
            )
            for (host in targets) {
                runCatching {
                    val addr = InetAddress.getByName(host)
                    val packet = DatagramPacket(bytes, bytes.size, addr, PORT)
                    socket.send(packet)
                }
            }
        }
    }
}
