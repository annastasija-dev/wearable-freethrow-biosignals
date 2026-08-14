package lt.vilniustech.basketball.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Finds cloud PC on the same Wi-Fi by scanning the local subnet for :8080/health.
 */
object CloudSubnetProbe {
    private const val TAG = "CloudSubnetProbe"
    private const val PORT = 8080
    private const val BATCH_SIZE = 24

    suspend fun findCloudUrl(context: Context): String? = withContext(Dispatchers.IO) {
        val apiKey = AppConfig.apiKey(context)
        val localIp = localIpv4(context) ?: return@withContext null
        Log.i(TAG, "phone IP: $localIp")

        for (prefix in subnetPrefixes(localIp)) {
            val hosts = prioritizedHosts(localIp, prefix)
            for (batch in hosts.chunked(BATCH_SIZE)) {
                val hit = coroutineScope {
                    batch.map { host ->
                        async(Dispatchers.IO) {
                            val url = "http://$prefix$host:$PORT"
                            val ok = runCatching {
                                CloudApiClient(url, apiKey).healthCheckFast()
                            }.isSuccess
                            if (ok) {
                                Log.i(TAG, "found cloud at $url")
                                url
                            } else {
                                null
                            }
                        }
                    }.awaitAll().firstOrNull { it != null }
                }
                if (hit != null) return@withContext hit
            }
        }
        Log.w(TAG, "subnet scan found nothing")
        null
    }

    private fun prioritizedHosts(localIp: String, prefix: String): List<Int> {
        val localHost = localIp.substringAfterLast('.').toIntOrNull() ?: 0
        val preferred = linkedSetOf(248, 244, 1, 100, 200, 254, localHost)
        for (offset in listOf(-2, -1, 1, 2, 10, 20, 50)) {
            val candidate = localHost + offset
            if (candidate in 1..254) preferred += candidate
        }
        for (host in 1..254) preferred += host
        return preferred.toList()
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

    private fun subnetPrefixes(ip: String): List<String> {
        val parts = ip.split(".")
        if (parts.size != 4) return emptyList()
        val a = parts[0].toIntOrNull() ?: return emptyList()
        val b = parts[1].toIntOrNull() ?: return emptyList()
        val c = parts[2].toIntOrNull() ?: return emptyList()
        val prefixes = linkedSetOf<String>()
        prefixes += "$a.$b.$c."
        if (a == 172 && b == 18) {
            prefixes += "172.18.0."
            prefixes += "172.18.1."
        }
        return prefixes.toList()
    }
}
