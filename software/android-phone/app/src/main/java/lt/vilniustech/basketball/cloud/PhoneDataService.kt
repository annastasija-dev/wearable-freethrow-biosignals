package lt.vilniustech.basketball.cloud

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

class PhoneDataService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        RawBatchSync.handleDataEvents(dataEvents) { sample ->
            SessionCoordinator.ingestWatchSample(sample)
        }
        WatchStatusSync.handleDataEvents(dataEvents)
    }
}
