package lt.vilniustech.basketball.watch

import android.app.Application
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FtWatchApp : Application(), MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "FtWatchApp"

    override fun onCreate() {
        super.onCreate()
        WatchCommands.init(this)
        CloudDirectUploader.ensureConfigured()
        CloudSessionPoller.configure("https://ft-cloud-vgtu.fly.dev", "dev-change-me")
        Wearable.getMessageClient(this).addListener(this)
        Wearable.getDataClient(this).addListener(this)
        scope.launch {
            advertiseCapability()
            SessionDataSync.readLatest(this@FtWatchApp)
            CloudDiscovery.bootstrap(this@FtWatchApp)
        }
        WatchUdpListener.ensureStarted(this)
    }

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        Log.i(tag, "message path=${event.path}")
        WatchCommands.handleMessage(event)
    }

    override fun onDataChanged(dataEvents: com.google.android.gms.wearable.DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) {
                SessionDataSync.handleDataItem(event.dataItem)
            }
        }
    }

    private suspend fun advertiseCapability() {
        runCatching {
            Wearable.getCapabilityClient(this)
                .addLocalCapability("basketball_watch")
                .await()
            Log.i(tag, "advertised basketball_watch capability")
        }.onFailure {
            Log.w(tag, "capability advertise failed: ${it.message}")
        }
    }
}
