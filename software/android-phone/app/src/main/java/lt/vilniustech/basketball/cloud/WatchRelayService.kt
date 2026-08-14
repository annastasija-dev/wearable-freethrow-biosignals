package lt.vilniustech.basketball.cloud

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives watch biosignal messages while the phone app is in background.
 */
class WatchRelayService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!messageEvent.path.startsWith("/basketball")) return
        WearableRawBridge.handleMessage(messageEvent)
    }
}
