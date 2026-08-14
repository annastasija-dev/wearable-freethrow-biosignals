package lt.vilniustech.basketball.cloud

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object SessionDataSync {
    private const val TAG = "SessionDataSync"
    private const val PATH = "/basketball/session"
    private var seq = 0L

    suspend fun publishRecording(context: Context, sessionId: String) {
        seq++
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString("state", "recording")
            dataMap.putString("session_id", sessionId)
            dataMap.putString("phone_ip", PhoneWifiRelay.localIp(context).orEmpty())
            dataMap.putString("cloud_url", AppConfig.cloudBaseUrl(context))
            dataMap.putString("api_key", AppConfig.apiKey(context))
            dataMap.putInt("relay_port", 8787)
            dataMap.putLong("updated_at", System.currentTimeMillis())
            dataMap.putLong("seq", seq)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
        Log.i(TAG, "published recording session=$sessionId seq=$seq")
    }

    suspend fun publishStopped(context: Context) {
        seq++
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString("state", "stopped")
            dataMap.putString("session_id", "")
            dataMap.putLong("updated_at", System.currentTimeMillis())
            dataMap.putLong("seq", seq)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
        Log.i(TAG, "published stopped seq=$seq")
    }
}
