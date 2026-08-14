package lt.vilniustech.basketball.cloud

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds the cloud PC on the local Wi-Fi via mDNS (ft-cloud.local).
 */
object CloudDiscovery {
    private const val TAG = "CloudDiscovery"
    private const val SERVICE_TYPE = "_http._tcp."

    suspend fun discoverHttpServices(context: Context, timeoutMs: Long = 4000L): List<String> {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val found = linkedSetOf<String>()
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {
                        Log.d(TAG, "NSD started: $serviceType")
                    }

                    override fun onServiceFound(service: NsdServiceInfo) {
                        if (!service.serviceName.contains("FT-Cloud", ignoreCase = true)) return
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                val port = if (serviceInfo.port > 0) serviceInfo.port else 8080
                                found += "http://$host:$port"
                                found += "http://ft-cloud.local:$port"
                                Log.i(TAG, "resolved cloud: http://$host:$port")
                            }
                        })
                    }

                    override fun onServiceLost(service: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) {
                        if (cont.isActive) cont.resume(found.toList())
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "start discovery failed: $errorCode")
                        if (cont.isActive) cont.resume(emptyList())
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        if (cont.isActive) cont.resume(found.toList())
                    }
                }

                cont.invokeOnCancellation {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                }

                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                    if (cont.isActive) cont.resume(found.toList())
                }, timeoutMs - 200)
            }
        } ?: emptyList()
    }
}
