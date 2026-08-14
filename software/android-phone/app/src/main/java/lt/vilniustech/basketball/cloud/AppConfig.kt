package lt.vilniustech.basketball.cloud

import android.content.Context

object AppConfig {
    private const val PREFS = "ft_protocol_prefs"
    private const val KEY_BASE_URL = "cloud_base_url"
    private const val KEY_API_KEY = "cloud_api_key"

    fun cloudBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty().trimEnd('/')
        if (saved.isNotBlank() && !saved.contains("ft-cloud.local")) return saved
        val baked = BuildConfig.CLOUD_BASE_URL.trim().trimEnd('/')
        if (baked.isNotBlank()) return baked
        return BuildConfig.TAILSCALE_URL.trimEnd('/')
    }

    fun apiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, BuildConfig.CLOUD_API_KEY)?.trim().orEmpty()
    }

    fun save(context: Context, baseUrl: String, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }
}
