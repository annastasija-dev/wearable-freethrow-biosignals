package lt.vilniustech.basketball.watch

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object WearNodes {
    private const val PHONE_CAPABILITY = "basketball_phone"

    @Volatile
    private var cachedPhoneId: String? = null

    suspend fun reachablePhoneNodes(context: Context): List<Node> {
        val merged = linkedMapOf<String, Node>()

        runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }.getOrDefault(emptyList()).forEach { node ->
            merged[node.id] = node
        }

        cachedPhoneId?.let { cachedId ->
            merged[cachedId]?.let { return listOf(it) }
        }

        for (filter in listOf(CapabilityClient.FILTER_REACHABLE, CapabilityClient.FILTER_ALL)) {
            runCatching {
                Wearable.getCapabilityClient(context)
                    .getCapability(PHONE_CAPABILITY, filter)
                    .await()
                    .nodes
            }.getOrDefault(emptySet()).forEach { node ->
                merged[node.id] = node
            }
        }

        val nodes = merged.values.toList()
        if (nodes.isNotEmpty()) {
            cachedPhoneId = nodes.first().id
        }
        return nodes
    }

    fun rememberPhoneNode(nodeId: String) {
        cachedPhoneId = nodeId
    }
}
