package lt.vilniustech.basketball.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Discovers Wear Wireless debugging endpoints on every local interface. */
object AdbMdnsDiscovery {
    private const val TAG = "AdbMdns"

    data class Endpoint(val host: String, val port: Int, val name: String)

    data class Snapshot(
        val connect: List<Endpoint> = emptyList(),
        val pairing: List<Endpoint> = emptyList(),
    )

    suspend fun discover(context: Context, timeoutMs: Long = 8_000L): Snapshot = coroutineScope {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("ft-adb-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            kotlinx.coroutines.withTimeout(timeoutMs + 2_000L) {
                val nsdJob = async(Dispatchers.Main) { discoverNsd(app, timeoutMs) }
                val rawJob = async(Dispatchers.IO) { AdbMdnsRaw.query((timeoutMs * 0.65).toLong()) }
                merge(nsdJob.await(), rawJob.await(), AdbMdnsRaw.localIpv4s())
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "discover timeout ${timeoutMs}ms")
            Snapshot()
        } finally {
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    private fun merge(a: Snapshot, b: Snapshot, selfIps: Set<String>): Snapshot {
        fun List<Endpoint>.clean(): List<Endpoint> =
            distinctBy { "${it.host}:${it.port}" }
                .filter { it.host !in selfIps && !it.host.contains(':') }

        val connect = (a.connect + b.connect).clean()
        val pairing = (a.pairing + b.pairing).clean()
        Log.i(TAG, "merged connect=$connect pairing=$pairing self=$selfIps")
        return Snapshot(connect, pairing)
    }

    private suspend fun discoverNsd(context: Context, timeoutMs: Long): Snapshot {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val connect = mutableListOf<Endpoint>()
        val pairing = mutableListOf<Endpoint>()
        val lock = Any()
        val resolveQueue = ConcurrentLinkedQueue<Pair<NsdServiceInfo, (NsdServiceInfo) -> Unit>>()
        val resolving = AtomicBoolean(false)

        fun pumpResolve() {
            if (!resolving.compareAndSet(false, true)) return
            val next = resolveQueue.poll()
            if (next == null) {
                resolving.set(false)
                return
            }
            val (info, onResolved) = next
            runCatching {
                @Suppress("DEPRECATION")
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            Log.w(TAG, "resolve fail $errorCode name=${info.serviceName}")
                            resolving.set(false)
                            pumpResolve()
                        }
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            onResolved(resolved)
                            resolving.set(false)
                            pumpResolve()
                        }
                    },
                )
            }.onFailure {
                Log.w(TAG, "resolve throw ${it.message}")
                resolving.set(false)
                pumpResolve()
            }
        }

        fun add(list: MutableList<Endpoint>, info: NsdServiceInfo) {
            val hosts = hostsOf(info)
            if (hosts.isEmpty()) {
                Log.w(TAG, "resolved without host name=${info.serviceName} port=${info.port}")
                return
            }
            for (host in hosts) {
                val ep = Endpoint(host, info.port, info.serviceName ?: "")
                synchronized(lock) {
                    if (list.none { it.host == ep.host && it.port == ep.port }) {
                        list += ep
                        Log.i(TAG, "nsd ${info.serviceType} $ep")
                    }
                }
            }
        }

        fun listener(into: MutableList<Endpoint>) = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "discover start fail $serviceType $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i(TAG, "discover started $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "found ${serviceInfo.serviceType} ${serviceInfo.serviceName}")
                resolveQueue.add(serviceInfo to { add(into, it) })
                pumpResolve()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        val connectListener = listener(connect)
        val pairingListener = listener(pairing)
        val classicListener = listener(connect)
        runCatching { nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener) }
        runCatching { nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingListener) }
        runCatching { nsd.discoverServices("_adb._tcp", NsdManager.PROTOCOL_DNS_SD, classicListener) }

        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var extra = false
            while (System.currentTimeMillis() < deadline) {
                val snap = synchronized(lock) { Snapshot(connect.toList(), pairing.toList()) }
                if (snap.connect.isNotEmpty() || snap.pairing.isNotEmpty()) {
                    if (!extra) {
                        extra = true
                        delay(1800)
                    } else {
                        break
                    }
                } else {
                    delay(350)
                }
            }
            synchronized(lock) { Snapshot(connect.toList(), pairing.toList()) }
        } finally {
            runCatching { nsd.stopServiceDiscovery(connectListener) }
            runCatching { nsd.stopServiceDiscovery(pairingListener) }
            runCatching { nsd.stopServiceDiscovery(classicListener) }
        }
    }

    private fun hostsOf(info: NsdServiceInfo): List<String> {
        val out = linkedSetOf<String>()
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                info.hostAddresses.forEach { addr ->
                    addr.hostAddress?.let { out += it }
                }
            }
        }
        @Suppress("DEPRECATION")
        info.host?.hostAddress?.let { out += it }
        return out.filter { !it.contains(':') }
    }

    fun localIpv4(context: Context): String? = AdbMdnsRaw.localIpv4s().firstOrNull()

    fun bindWifiIfPossible(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val wifi = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return
        runCatching { cm.bindProcessToNetwork(wifi) }
    }

    fun unbind(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.bindProcessToNetwork(null) }
    }
}
