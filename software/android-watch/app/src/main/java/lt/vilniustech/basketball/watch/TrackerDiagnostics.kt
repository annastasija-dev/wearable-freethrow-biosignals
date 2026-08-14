package lt.vilniustech.basketball.watch

object TrackerDiagnostics {
    enum class SdkState {
        UNKNOWN,
        PROBING,
        CONNECTED,
        POLICY_BLOCKED,
        FAILED,
    }

    @Volatile
    var sdkState: SdkState = SdkState.UNKNOWN

    @Volatile
    var sdkError: String? = null

    @Volatile
    var probedStreams: List<String> = emptyList()

    fun isPolicyBlocked(): Boolean = sdkState == SdkState.POLICY_BLOCKED

    fun resetProbe() {
        sdkState = SdkState.UNKNOWN
        sdkError = null
        probedStreams = emptyList()
    }
}
