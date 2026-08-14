package lt.vilniustech.basketball.cloud

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.Kadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Phone → Watch Wi‑Fi APK install via Wireless debugging (auto-discovered). */
object WatchWifiInstaller {
    private const val TAG = "WatchWifiInstaller"
    private const val PREFS = "watch_wifi_install"
    private const val KEY_HOST = "last_host"
    private const val KEY_PORT = "last_port"
    private const val KEY_INSTALLED = "ft_watch_installed"

    fun markInstalled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INSTALLED, true)
            .apply()
    }

    fun isInstalled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INSTALLED, false)

    data class Result(val ok: Boolean, val message: String, val needsPairCode: Boolean = false)

    suspend fun installAuto(
        context: Context,
        apk: File,
        pairingCode: String? = null,
        onStatus: (String) -> Unit = {},
    ): Result {
        val code = pairingCode?.trim().orEmpty()
        if (code.length < 5) {
            return Result(false, "Enter the 6-digit Pair new device code.", needsPairCode = true)
        }

        onStatus("Looking for Pair new device…")
        var snap = AdbMdnsDiscovery.discover(context, timeoutMs = 8_000L)
        Log.i(TAG, "mdns connect=${snap.connect} pairing=${snap.pairing}")
        if (snap.pairing.isEmpty()) {
            onStatus("Still looking for the pairing code on the watch…")
            snap = AdbMdnsDiscovery.discover(context, timeoutMs = 6_000L)
        }
        if (snap.pairing.isEmpty()) {
            return Result(
                false,
                "Pair new device not found. On the watch open Wireless debugging → Pair new device, leave the code visible, and try again.",
                needsPairCode = true,
            )
        }

        val p = preferWatch(snap.pairing)
        val pairPorts = snap.pairing.map { it.port }.toSet()
        val connectBefore = snap.connect.filter { it.port !in pairPorts && it.host !in AdbMdnsRaw.localIpv4s() }

        onStatus("Pairing with the watch (${p.host})…")
        runCatching {
            Log.i(TAG, "pairing ${p.host}:${p.port}")
            kotlinx.coroutines.withTimeout(20_000L) {
                withContext(Dispatchers.IO) {
                    Kadb.pair(p.host, p.port, code)
                }
            }
            Log.i(TAG, "pair OK")
            rememberHost(context, p.host)
        }.onFailure {
            Log.w(TAG, "pair failed: ${it.message}")
            return Result(false, "Pairing failed: ${it.message}", needsPairCode = true)
        }

        onStatus("Paired. Sending FT Watch…")
        kotlinx.coroutines.delay(2000)
        var after = AdbMdnsDiscovery.discover(context, timeoutMs = 7_000L)
        if (after.connect.none { it.port !in pairPorts }) {
            onStatus("Waiting for the install port…")
            kotlinx.coroutines.delay(1500)
            after = AdbMdnsDiscovery.discover(context, timeoutMs = 6_000L)
        }
        val connect = (after.connect + connectBefore)
            .filter { it.port !in pairPorts && it.port !in after.pairing.map { e -> e.port } }
            .distinctBy { "${it.host}:${it.port}" }

        if (connect.isEmpty()) {
            return Result(
                false,
                "Paired. Close Pair new device, stay on Wireless debugging, and tap install again with a new code.",
                needsPairCode = true,
            )
        }

        var last = Result(false, "Could not install the APK.")
        for (target in orderedTargets(connect)) {
            onStatus("Installing on ${target.host}:${target.port}…")
            last = withContext(Dispatchers.IO) { installAt(target.host, target.port, apk) }
            if (last.ok) {
                remember(context, target.host, target.port)
                markInstalled(context)
                return last
            }
            Log.w(TAG, "install ${target.host}:${target.port} failed: ${last.message}")
        }
        val ssl = last.message.contains("ssl", ignoreCase = true) || last.message.contains("SSL")
        return if (ssl) {
            Result(
                false,
                "Paired, but install is not done yet. Use a new Pair new device code and try again.",
                needsPairCode = true,
            )
        } else {
            last
        }
    }

    private fun preferWatch(list: List<AdbMdnsDiscovery.Endpoint>): AdbMdnsDiscovery.Endpoint {
        return orderedTargets(list).first()
    }

    private fun orderedTargets(list: List<AdbMdnsDiscovery.Endpoint>): List<AdbMdnsDiscovery.Endpoint> {
        val self = AdbMdnsRaw.localIpv4s()
        val usable = list.filter { it.host !in self }.ifEmpty { list }
        val watchy = usable.filter {
            val n = it.name.lowercase()
            n.contains("watch") || n.contains("wear") || n.contains("galaxy") || n.contains("sm-l")
        }
        return (watchy + usable).distinctBy { "${it.host}:${it.port}" }
    }

    private fun rememberedHost(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, null)

    private fun rememberHost(context: Context, host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .apply()
    }

    private suspend fun scanHostAdbPorts(host: String): List<AdbMdnsDiscovery.Endpoint> {
        Log.i(TAG, "scanning adb ports on $host")
        val ports = (30000..47000).toList()
        return withContext(Dispatchers.IO) {
            coroutineScope {
                ports.chunked(80).flatMap { batch ->
                    batch.map { port ->
                        async(Dispatchers.IO) {
                            if (tcpOpen(host, port, 120)) AdbMdnsDiscovery.Endpoint(host, port, "scan-host") else null
                        }
                    }.awaitAll().filterNotNull()
                }.also { Log.i(TAG, "host $host open=${it.map { e -> e.port }}") }
            }
        }
    }

    private fun remembered(context: Context): List<AdbMdnsDiscovery.Endpoint> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString(KEY_HOST, null) ?: return emptyList()
        val port = p.getInt(KEY_PORT, 0)
        if (port <= 0) return emptyList()
        if (!tcpOpen(host, port, 400)) return emptyList()
        Log.i(TAG, "using remembered $host:$port")
        return listOf(AdbMdnsDiscovery.Endpoint(host, port, "remembered"))
    }

    private suspend fun scanLastPort(context: Context): List<AdbMdnsDiscovery.Endpoint> {
        val port = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, 0)
        val ports = linkedSetOf<Int>()
        if (port in 1..65535) ports += port
        ports += 5555
        val prefixes = AdbMdnsRaw.localIpv4s().mapNotNull { ip ->
            val parts = ip.split('.')
            if (parts.size != 4) return@mapNotNull null
            if (ip.startsWith("100.")) return@mapNotNull null
            parts.take(3).joinToString(".") + "."
        }.distinct()
        if (prefixes.isEmpty()) return emptyList()
        Log.i(TAG, "subnet scan prefixes=$prefixes ports=$ports")
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val found = mutableListOf<AdbMdnsDiscovery.Endpoint>()
                val self = AdbMdnsRaw.localIpv4s()
                for (prefix in prefixes) {
                    val hits = (1..254).chunked(48).flatMap { batch ->
                        batch.map { host ->
                            async(Dispatchers.IO) {
                                val ip = "$prefix$host"
                                if (ip in self) return@async null
                                for (p in ports) {
                                    if (tcpOpen(ip, p, 180)) {
                                        return@async AdbMdnsDiscovery.Endpoint(ip, p, "scan")
                                    }
                                }
                                null
                            }
                        }.awaitAll().filterNotNull()
                    }
                    found += hits
                    if (found.isNotEmpty()) break
                }
                Log.i(TAG, "subnet hits=$found")
                found.distinctBy { "${it.host}:${it.port}" }
            }
        }
    }

    private fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    private fun remember(context: Context, host: String, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .apply()
    }

    private fun clearPort(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PORT)
            .apply()
    }

    private suspend fun installAt(host: String, port: Int, apk: File): Result {
        return runCatching {
            Log.i(TAG, "installing $host:$port bytes=${apk.length()}")
            Kadb.create(host, port).use { kadb ->
                val remote = "/data/local/tmp/FT-Watch.apk"
                kadb.push(apk, remote)
                kadb.shell("pm uninstall lt.vilniustech.basketball.watch")
                val commands = listOf(
                    "pm install -r -d -t -g \"$remote\"",
                    "pm install -r -t -g \"$remote\"",
                    "pm install -r -t \"$remote\"",
                )
                var lastOut = ""
                for (cmd in commands) {
                    val shell = kadb.shell(cmd)
                    lastOut = "${shell.output} (exit=${shell.exitCode})"
                    Log.i(TAG, "$cmd -> $lastOut")
                    if (installSucceeded(shell.exitCode, shell.output)) {
                        kadb.shell("rm -f \"$remote\"")
                        return Result(true, "FT Watch installed.")
                    }
                    if (shell.output.contains("UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                        shell.output.contains("SIGNATURE", ignoreCase = true)
                    ) {
                        kadb.shell("pm uninstall lt.vilniustech.basketball.watch")
                    }
                }
                Result(false, "Could not install APK: ${lastOut.take(220)}")
            }
        }.getOrElse { err ->
            val msg = err.message.orEmpty()
            Log.e(TAG, "connect/install failed", err)
            val needsPair =
                msg.contains("unauthorized", ignoreCase = true) ||
                    msg.contains("not allowed", ignoreCase = true) ||
                    (msg.contains("pair", ignoreCase = true) && !msg.contains("tls handshake", ignoreCase = true))
            Result(
                false,
                if (needsPair) {
                    "Need a new code: Pair new device, then try again."
                } else {
                    msg.ifBlank { "Wi-Fi install error" }
                },
                needsPairCode = needsPair,
            )
        }
    }

    private fun installSucceeded(exitCode: Int, output: String): Boolean {
        val text = output.lowercase()
        if (text.contains("failure") || text.contains("failed") || text.contains("error:")) return false
        return exitCode == 0 || text.contains("success")
    }

    private fun setStatusLog(msg: String) = Log.i(TAG, msg)

    private suspend fun delayShort() {
        kotlinx.coroutines.delay(1500)
    }
}
