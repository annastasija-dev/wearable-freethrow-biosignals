package lt.vilniustech.basketball.cloud

data class SessionState(
    val active: Boolean = false,
    val sessionId: String = "",
    val participantCode: String = "",
    val shotsDone: Int = 0,
    val shotsTarget: Int = 10,
)

data class ParticipantProfile(
    val weightKg: Double,
    val heightCm: Int,
    val ageYears: Int,
    val sex: String,
    val skillLevel: String,
    val throwTechnique: String,
)

data class SessionStartResponse(
    val sessionId: String,
    val startedAt: String,
    val shotsTarget: Int,
)

data class ShotLabelResponse(
    val sessionId: String,
    val shotNo: Int,
    val result: String,
    val remaining: Int,
    val done: Boolean,
)
