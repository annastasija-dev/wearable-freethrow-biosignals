package lt.vilniustech.basketball.cloud

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Local backup when cloud upload fails or for post-session recovery.
 */
class LocalSessionStore(context: Context) {
    private val root = File(context.filesDir, "sessions").apply { mkdirs() }

    fun onSessionStarted(sessionId: String, participant: String, profile: ParticipantProfile? = null) {
        val dir = sessionDir(sessionId)
        dir.mkdirs()
        val json = JSONObject()
            .put("session_id", sessionId)
            .put("participant", participant)
        if (profile != null) {
            json.put("weight_kg", profile.weightKg)
                .put("height_cm", profile.heightCm)
                .put("age_years", profile.ageYears)
                .put("sex", profile.sex)
                .put("skill_level", profile.skillLevel)
                .put("throw_technique", profile.throwTechnique)
        }
        writeText(File(dir, "session.json"), json.toString())
    }
    fun appendShot(sessionId: String, shotNo: Int, result: String, timestamp: String) {
        val dir = sessionDir(sessionId)
        dir.mkdirs()
        appendLine(
            File(dir, "shots.jsonl"),
            JSONObject()
                .put("shot_no", shotNo)
                .put("result", result)
                .put("client_timestamp", timestamp)
                .toString(),
        )
    }

    fun appendRawSample(sessionId: String, sample: RawSample) {
        val dir = sessionDir(sessionId)
        dir.mkdirs()
        appendLine(
            File(dir, "${sample.stream}.jsonl"),
            sample.payload.toString(),
        )
    }

    fun pendingSessionIds(): List<String> = root.listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        ?.sortedDescending()
        .orEmpty()

    private fun sessionDir(sessionId: String): File = File(root, sessionId)

    private fun writeText(file: File, text: String) {
        file.writeText(text)
    }

    private fun appendLine(file: File, line: String) {
        file.appendText(line + "\n")
    }
}
