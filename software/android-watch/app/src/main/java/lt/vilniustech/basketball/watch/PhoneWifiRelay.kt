package lt.vilniustech.basketball.watch

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Direct Wi-Fi to phone — works when Wear OS messages fail. */
object PhoneWifiRelay {
    private const val TAG = "PhoneWifiRelay"
    private const val PREFS = "phone_wifi_relay"
    private const val KEY_PHONE_IP = "phone_ip"
    private const val PORT = 8787
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = mutableListOf<JSONObject>()
    private val pollInFlight = AtomicBoolean(false)
    private val flushInFlight = AtomicBoolean(false)
    private val resolveInFlight = AtomicBoolean(false)

    @Volatile
    private var phoneIp: String? = null

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var wifiSamplesSent = 0L

    fun samplesSent(): Long = wifiSamplesSent

    fun resetCounter() {
        wifiSamplesSent = 0L
        lastError = null
        synchronized(pending) { pending.clear() }
    }

    fun rememberPhoneIp(ip: String?, context: Context? = null) {
        if (ip.isNullOrBlank()) return
        phoneIp = ip
        context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_PHONE_IP, ip)?.apply()
        Log.i(TAG, "phone ip=$ip")
    }

    fun loadCachedIp(context: Context): String? {
        phoneIp?.let { return it }
        val cached = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHONE_IP, null)
        if (!cached.isNullOrBlank()) phoneIp = cached
        return cached
    }

    fun enqueueSample(stream: String, payload: JSONObject) {
        synchronized(pending) {
            pending += JSONObject(payload.toString()).put("stream", stream)
            if (pending.size >= 3) flushAsync()
        }
    }

    fun flushAsync() {
        if (!flushInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                flushSamples(WatchCommands.appContext())
            } finally {
                flushInFlight.set(false)
            }
        }
    }

    fun pollSession(context: Context) {
        if (!pollInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                var ip = phoneIp ?: loadCachedIp(context)
                if (ip == null) {
                    resolvePhoneIpOnce(context)
                    return@launch
                }
                runCatching {
                    val conn = openGet("http://$ip:$PORT/watch/session")
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val json = JSONObject(body)
                    val active = json.optBoolean("active")
                    val sessionId = json.optString("session_id")
                    val reportedIp = json.optString("phone_ip")
                    rememberPhoneIp(if (reportedIp.isNotBlank()) reportedIp else ip, context)
                    CloudDirectUploader.configure(
                        json.optString("cloud_url"),
                        json.optString("api_key"),
                    )
                    lastError = null
                    if (active && sessionId.isNotBlank()) {
                        Log.i(TAG, "wifi session start $sessionId")
                        WatchCommands.startSession(sessionId, context)
                    }
                }.onFailure {
                    lastError = it.message
                    Log.w(TAG, "poll failed: ${it.message}")
                }
            } finally {
                pollInFlight.set(false)
            }
        }
    }

    fun resolvePhoneIpOnce(context: Context) {
        if (!resolveInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                resolvePhoneIp(context)?.let { rememberPhoneIp(it, context) }
            } finally {
                resolveInFlight.set(false)
            }
        }
    }

    private suspend fun flushSamples(context: Context) = withContext(Dispatchers.IO) {
        val batch: List<JSONObject>
        synchronized(pending) {
            if (pending.isEmpty()) return@withContext
            batch = pending.toList()
            pending.clear()
        }
        val ip = phoneIp ?: loadCachedIp(context) ?: resolvePhoneIp(context) ?: run {
            synchronized(pending) { pending.addAll(0, batch) }
            return@withContext
        }
        runCatching {
            val arr = JSONArray()
            batch.forEach { arr.put(it) }
            val payload = JSONObject().put("samples", arr).toString()
            val conn = openPost("http://$ip:$PORT/watch/samples", payload)
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            wifiSamplesSent += batch.size
            lastError = null
            Log.i(TAG, "sent ${batch.size} samples to $ip (total=$wifiSamplesSent)")
        }.onFailure {
            lastError = it.message
            synchronized(pending) { pending.addAll(0, batch) }
            Log.w(TAG, "flush failed: ${it.message}")
        }
    }

    private suspend fun resolvePhoneIp(context: Context): String? = withContext(Dispatchers.IO) {
        val local = localIpv4(context) ?: return@withContext null
        val prefix = local.substringBeforeLast('.') + "."
        val localHost = local.substringAfterLast('.').toIntOrNull() ?: 0
        val priority = linkedSetOf<Int>()
        for (h in listOf(localHost - 1, localHost + 1, localHost - 2, localHost + 2, 168, 248, 244, 1)) {
            if (h in 1..254) priority += h
        }
        for (h in priority) {
            val ip = "$prefix$h"
            if (probeSession(ip)) return@withContext ip
        }
        null
    }

    private fun probeSession(ip: String): Boolean {
        return runCatching {
            val conn = openGet("http://$ip:$PORT/watch/session")
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    private fun openGet(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        conn.requestMethod = "GET"
        conn.setRequestProperty("Connection", "close")
        return conn
    }

    private fun openPost(url: String, body: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Connection", "close")
        conn.outputStream.write(body.toByteArray(StandardCharsets.UTF_8))
        conn.outputStream.flush()
        return conn
    }

    private fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        for (addr in props.linkAddresses) {
            val host = addr.address.hostAddress ?: continue
            if (host.contains(":") || host.startsWith("127.")) continue
            return host
        }
        return null
    }
}
