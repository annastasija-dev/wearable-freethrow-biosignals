package lt.vilniustech.basketball.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Direct Wi-Fi relay — bypasses broken Wear OS message delivery on Samsung. */
object PhoneWifiRelay {
    private const val TAG = "PhoneWifiRelay"
    const val PORT = 8787
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handlerLimit = Semaphore(8)

    @Volatile
    private var running = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastWatchContactMs: Long = 0L

    @Volatile
    private var watchApkFile: File? = null

    fun setWatchApkForInstall(file: File?) {
        watchApkFile = file
        Log.i(TAG, "watch apk for install: ${file?.absolutePath} bytes=${file?.length()}")
    }

    fun allWatchInstallUrls(): List<String> {
        val ips = AdbMdnsRaw.localIpv4s().sortedWith(
            compareByDescending<String> { it.startsWith("172.16.") }
                .thenByDescending { it.startsWith("192.168.43.") || it.startsWith("192.168.49.") }
                .thenByDescending { it.startsWith("192.168.") }
                .thenByDescending { it.startsWith("10.") },
        )
        val urls = mutableListOf<String>()
        for (ip in ips) {
            urls += "http://$ip:$PORT/ft-watch.apk"
            urls += "http://$ip:$PORT/install"
        }
        return urls.distinct()
    }

    fun watchApkUrl(context: Context?): String? {
        val ip = localIp(context) ?: return null
        return "http://$ip:$PORT/ft-watch.apk"
    }

    fun lastWatchContactMs(): Long = lastWatchContactMs

    fun watchReachableRecently(withinMs: Long = 30_000L): Boolean {
        if (lastWatchContactMs <= 0L) return false
        return System.currentTimeMillis() - lastWatchContactMs <= withinMs
    }

    private fun markWatchContact() {
        lastWatchContactMs = System.currentTimeMillis()
    }

    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        if (running) return
        running = true
        scope.launch {
            runCatching {
                ServerSocket(PORT).apply { reuseAddress = true }.use { server ->
                    Log.i(TAG, "listening on :$PORT ip=${localIp(appContext)}")
                    while (isActive) {
                        val socket = server.accept()
                        socket.tcpNoDelay = true
                        scope.launch {
                            if (!handlerLimit.tryAcquire()) {
                                runCatching { writeJson(socket, 503, JSONObject().put("error", "busy")) }
                                runCatching { socket.close() }
                                return@launch
                            }
                            try {
                                handle(socket)
                            } finally {
                                handlerLimit.release()
                            }
                        }
                    }
                }
            }.onFailure {
                Log.e(TAG, "server failed: ${it.message}")
            }.also {
                running = false
            }
        }
    }

    @Volatile
    private var cachedLocalIp: String? = null

    fun localIp(context: Context?): String? {
        if (context == null) return cachedLocalIp
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return cachedLocalIp
        val network = cm.activeNetwork ?: return cachedLocalIp
        val props = cm.getLinkProperties(network) ?: return cachedLocalIp
        for (addr in props.linkAddresses) {
            val host = addr.address.hostAddress ?: continue
            if (host.contains(":") || host.startsWith("127.")) continue
            cachedLocalIp = host
            return host
        }
        return cachedLocalIp
    }

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = 1500
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val chunk = input.read(bytes, read, contentLength - read)
                    if (chunk <= 0) break
                    read += chunk
                }
                String(bytes, 0, read, StandardCharsets.UTF_8)
            } else {
                ""
            }
            when {
                method == "GET" && (path == "/install" || path == "/install/") -> {
                    markWatchContact()
                    val html = """
                        <!DOCTYPE html><html><head>
                        <meta charset="utf-8"/>
                        <meta name="viewport" content="width=device-width,initial-scale=1"/>
                        <title>FT Watch</title>
                        <style>
                          body{font-family:sans-serif;background:#0b1b2b;color:#fff;text-align:center;padding:18px}
                          a{display:block;margin:18px 0;padding:14px;background:#0EA5E9;color:#fff;text-decoration:none;border-radius:999px;font-weight:700}
                        </style></head><body>
                        <h1>FT Watch</h1>
                        <p>Tap the button and confirm Install.</p>
                        <a href="/ft-watch.apk">Install FT Watch</a>
                        </body></html>
                    """.trimIndent()
                    writeBytes(socket, 200, "text/html; charset=utf-8", html.toByteArray(StandardCharsets.UTF_8))
                }
                method == "GET" && (path == "/ft-watch.apk" || path == "/download/ft-watch.apk") -> {
                    markWatchContact()
                    val apk = watchApkFile
                    if (apk == null || !apk.exists() || apk.length() < 1_000_000L) {
                        writeJson(socket, 404, JSONObject().put("error", "apk_missing"))
                    } else {
                        writeFile(
                            socket,
                            200,
                            "application/vnd.android.package-archive",
                            apk,
                        )
                        Log.i(TAG, "served watch apk bytes=${apk.length()}")
                    }
                }
                method == "GET" && path == "/watch/session" -> {
                    markWatchContact()
                    val state = SessionCoordinator.sessionState
                    val json = JSONObject()
                        .put("active", state.active)
                        .put("session_id", state.sessionId)
                        .put("phone_ip", localIp(appContext).orEmpty())
                        .put("cloud_url", appContext?.let { AppConfig.cloudBaseUrl(it) }.orEmpty())
                        .put("api_key", appContext?.let { AppConfig.apiKey(it) }.orEmpty())
                    writeJson(socket, 200, json)
                }
                method == "POST" && path == "/watch/samples" -> {
                    markWatchContact()
                    val payload = JSONObject(body)
                    val arr = payload.optJSONArray("samples") ?: JSONArray()
                    var ingested = 0
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val stream = item.optString("stream")
                        if (stream.isBlank()) continue
                        SessionCoordinator.ingestWatchSample(RawSample(stream, item))
                        ingested++
                    }
                    writeJson(socket, 200, JSONObject().put("ok", true).put("count", ingested))
                    if (ingested > 0) Log.i(TAG, "wifi received $ingested samples")
                }
                else -> writeJson(socket, 404, JSONObject().put("error", "not found"))
            }
        }.onFailure {
            Log.w(TAG, "handle error: ${it.message}")
        }.also {
            runCatching { socket.close() }
        }
    }

    private fun writeJson(socket: Socket, code: Int, json: JSONObject) {
        writeBytes(socket, code, "application/json", json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeBytes(socket: Socket, code: Int, mime: String, bytes: ByteArray) {
        val reason = if (code == 200) "OK" else "ERR"
        val header =
            "HTTP/1.1 $code $reason\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(header.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun writeFile(socket: Socket, code: Int, mime: String, file: File) {
        socket.soTimeout = 0
        val out = socket.getOutputStream()
        val header =
            "HTTP/1.1 $code OK\r\nContent-Type: $mime\r\nContent-Length: ${file.length()}\r\nContent-Disposition: attachment; filename=\"FT-Watch.apk\"\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        FileInputStream(file).use { input -> input.copyTo(out) }
        out.flush()
    }
}
