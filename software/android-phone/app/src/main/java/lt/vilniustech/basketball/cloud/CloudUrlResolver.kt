package lt.vilniustech.basketball.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CloudBootstrapResult(
    val workingUrl: String,
    val repaired: Boolean,
    val message: String,
)

object CloudUrlResolver {
    suspend fun bootstrap(context: Context): CloudBootstrapResult = withContext(Dispatchers.IO) {
        val apiKey = AppConfig.apiKey(context)
        val current = AppConfig.cloudBaseUrl(context)

        if (current.isNotBlank() && !current.contains("ft-cloud.local") && healthOk(current, apiKey)) {
            return@withContext CloudBootstrapResult(
                workingUrl = current,
                repaired = false,
                message = "Cloud OK",
            )
        }

        val candidates = linkedSetOf<String>()

        CloudSubnetProbe.findCloudUrl(context)?.let { candidates += it.trimEnd('/') }

        if (current.isNotBlank()) candidates += current.trimEnd('/')
        candidates += BuildConfig.TAILSCALE_URL.trimEnd('/')

        CloudDiscovery.discoverHttpServices(context).forEach { url ->
            if (!url.contains("ft-cloud.local")) candidates += url.trimEnd('/')
        }

        val expanded = linkedSetOf<String>()
        expanded.addAll(candidates)
        for (seed in candidates.toList()) {
            runCatching {
                CloudApiClient(seed, apiKey).fetchSetupInfo().cloudUrls
            }.getOrNull()?.forEach { url ->
                if (url.isNotBlank() && !url.contains("ft-cloud.local")) {
                    expanded += url.trimEnd('/')
                }
            }
        }

        val sorted = expanded.sortedWith(
            compareBy<String> { url ->
                when {
                    url.contains("ft-cloud.local") -> 9
                    url.contains("100.") -> 1
                    url.contains("127.0.0.1") -> 8
                    url.contains("169.254.") -> 7
                    else -> 0
                }
            }.thenBy { it },
        )

        for (url in sorted) {
            if (url.isBlank() || url.contains("ft-cloud.local")) continue
            if (healthOk(url, apiKey)) {
                val repaired = url != current
                if (repaired) AppConfig.save(context, url, apiKey)
                return@withContext CloudBootstrapResult(
                    workingUrl = url,
                    repaired = repaired,
                    message = if (repaired) "Cloud auto-connected -> $url" else "Cloud OK",
                )
            }
        }

        CloudBootstrapResult(
            workingUrl = current,
            repaired = false,
            message = "Cloud unreachable — check internet connection",
        )
    }

    suspend fun ensureConnected(context: Context): CloudBootstrapResult = bootstrap(context)

    private fun healthOk(url: String, apiKey: String): Boolean {
        return runCatching {
            CloudApiClient(url, apiKey).healthCheckFast()
        }.isSuccess
    }
}
