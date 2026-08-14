package lt.vilniustech.basketball.cloud

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object WearNodes {
    private const val TAG = "WearNodes"
    private const val WATCH_CAPABILITY = "basketball_watch"

    @Volatile
    private var cachedWatchId: String? = null

    @Volatile
    var lastDiagnostic: String = ""
        private set

    /** True when Galaxy Watch advertises FT Watch capability (app installed + Wear link OK). */
    @Volatile
    var watchAppPresent: Boolean = false
        private set

    suspend fun ftWatchCapabilityNodes(context: Context): List<Node> {
        val merged = linkedMapOf<String, Node>()
        for (filter in listOf(CapabilityClient.FILTER_REACHABLE, CapabilityClient.FILTER_ALL)) {
            runCatching {
                Wearable.getCapabilityClient(context)
                    .getCapability(WATCH_CAPABILITY, filter)
                    .await()
                    .nodes
            }.getOrDefault(emptySet()).forEach { merged[it.id] = it }
        }
        watchAppPresent = merged.isNotEmpty()
        if (watchAppPresent) {
            WatchWifiInstaller.markInstalled(context)
        }
        return merged.values.toList()
    }

    suspend fun reachableWatchNodes(context: Context): List<Node> {
        val merged = linkedMapOf<String, Node>()
        val notes = mutableListOf<String>()

        val local = runCatching {
            Wearable.getNodeClient(context).localNode.await()
        }
        notes += if (local.isSuccess) {
            "local=${local.getOrNull()?.displayName}"
        } else {
            "localError=${local.exceptionOrNull()?.message}"
        }

        val connected = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }
        if (connected.isSuccess) {
            val nodes = connected.getOrDefault(emptyList())
            notes += "connectedNodes=${nodes.size}"
            nodes.forEach { merged[it.id] = it }
            nodes.forEach { n ->
                notes += "node=${n.displayName};nearby=${n.isNearby}"
            }
        } else {
            notes += "connectedError=${connected.exceptionOrNull()?.message}"
        }

        for (filter in listOf(CapabilityClient.FILTER_ALL, CapabilityClient.FILTER_REACHABLE)) {
            runCatching {
                Wearable.getCapabilityClient(context).getAllCapabilities(filter).await()
            }.onSuccess { caps ->
                notes += "caps($filter)=${caps.size}"
                caps.values.forEach { info ->
                    info.nodes.forEach { merged[it.id] = it }
                }
            }.onFailure {
                notes += "capsError($filter)=${it.message}"
            }
            runCatching {
                Wearable.getCapabilityClient(context)
                    .getCapability(WATCH_CAPABILITY, filter)
                    .await()
                    .nodes
            }.getOrDefault(emptySet()).forEach { merged[it.id] = it }
        }

        val appNodes = ftWatchCapabilityNodes(context)
        notes += "ftWatch=${appNodes.size}"

        cachedWatchId?.let { id -> merged[id]?.let { return listOf(it) } }

        val nodes = merged.values.toList()
        if (nodes.isNotEmpty()) cachedWatchId = nodes.first().id
        lastDiagnostic = notes.joinToString(" | ")
        Log.i(TAG, lastDiagnostic)
        return nodes
    }
}
