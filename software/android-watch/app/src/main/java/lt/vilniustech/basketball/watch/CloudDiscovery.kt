package lt.vilniustech.basketball.watch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Pins the always-on Fly cloud URL. Local LAN discovery is only a fallback. */
object CloudDiscovery {
    private const val TAG = "CloudDiscovery"
    private const val PREFS = "cloud_discovery"
    private const val KEY_URL = "cloud_url"
    private const val KEY_API_KEY = "api_key"
    private const val DEFAULT_API_KEY = "dev-change-me"
    private const val PUBLIC_CLOUD = "https://ft-cloud-vgtu.fly.dev"

    suspend fun bootstrap(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Prefer public cloud — colleagues should not depend on a local PC.
        val publicInfo = probeSetupInfo(PUBLIC_CLOUD)
        if (publicInfo != null) {
            val apiKey = publicInfo.optString("api_key").ifBlank { DEFAULT_API_KEY }
            configure(PUBLIC_CLOUD, apiKey)
            prefs.edit().putString(KEY_URL, PUBLIC_CLOUD).putString(KEY_API_KEY, apiKey).apply()
            Log.i(TAG, "cloud ready at $PUBLIC_CLOUD")
            return@withContext
        }

        val cachedUrl = prefs.getString(KEY_URL, null)
        val cachedKey = prefs.getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
        if (!cachedUrl.isNullOrBlank() && cachedUrl.startsWith("https://") && probeSetupInfo(cachedUrl) != null) {
            configure(cachedUrl, cachedKey)
            Log.i(TAG, "using cached cloud $cachedUrl")
            return@withContext
        }

        Log.w(TAG, "public cloud unreachable — watch needs internet (Wi-Fi or mobile)")
        configure(PUBLIC_CLOUD, DEFAULT_API_KEY)
    }

    private fun configure(url: String, apiKey: String) {
        CloudDirectUploader.configure(url, apiKey)
        CloudSessionPoller.configure(url, apiKey)
    }

    private fun probeSetupInfo(baseUrl: String): JSONObject? {
        return runCatching {
            val conn = URL("$baseUrl/api/v1/setup-info").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val text = BufferedReader(InputStreamReader(
                if (code in 200..299) conn.inputStream else conn.errorStream,
            )).use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) return null
            JSONObject(text)
        }.getOrNull()
    }
}
