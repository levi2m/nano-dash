package dev.nanodash.core.camera

/** A short name that tells two Nanos apart: the end of the Bluetooth address, which the camera does not show anywhere else. */
fun cameraLabel(address: String) = "Osmo Nano " + address.takeLast(5)

/** One camera as the screen shows it. */
data class CameraRow(
    val address: String,
    val text: String,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val error: Boolean = false,
    val recording: Boolean? = null,
    /** True when this connection set the camera's clock to the phone's. */
    val clockSet: Boolean = false,
) {
    val label: String get() = cameraLabel(address)
}

/** All cameras in one line: a single camera reads as its own row, several read as counts. */
data class CameraSummary(val text: String, val ready: Boolean, val busy: Boolean, val error: Boolean, val recording: Boolean?)

/** The words the rider reads about the cameras and about a ride's start and stop. Pure functions, so they are tested in `core`. */
object CameraNotes {
    fun summarize(rows: List<CameraRow>): CameraSummary {
        if (rows.isEmpty()) return CameraSummary("Camera not connected", ready = false, busy = false, error = false, recording = null)
        val text = rows.firstOrNull { it.text.startsWith("Approve") }
            ?.let { if (rows.size == 1) it.text else "Approve this phone on the screen of ${it.label}" }
            ?: if (rows.size == 1) rows[0].text
            else rows.firstOrNull { it.error }?.let { "${it.label}: ${it.text}" } ?: "${rows.count { it.ready }} of ${rows.size} cameras ready"
        return CameraSummary(
            text = text,
            ready = rows.all { it.ready },
            busy = rows.any { it.busy },
            error = rows.any { it.error },
            recording = if (rows.any { it.recording == true }) true else if (rows.all { it.recording == false }) false else null,
        )
    }

    /** What to tell the rider after the start command went to [results] (the cameras that were ready) out of [connected] cameras. */
    fun startNote(connected: Int, results: Map<String, StartResult>): String {
        val problems = mutableListOf<String>()
        var recording = 0
        for ((address, r) in results) {
            val who = if (connected == 1) "Camera" else cameraLabel(address)
            when (r) {
                is StartResult.Started -> if (r.recordingFlagAt != null) recording++ else problems += "$who accepted the command but did not report recording"
                is StartResult.Rejected -> problems += "$who refused to record (code %02X). Is USB connected?".format(r.code ?: 0)
                StartResult.NoReply -> problems += "$who did not answer the start command"
                StartResult.NotReady -> problems += "$who is not ready"
            }
        }
        val missed = connected - results.size
        if (missed > 0) problems += if (missed == 1) "1 camera was not ready and has no video" else "$missed cameras were not ready and have no video"
        val detail = problems.joinToString(". ").let { if (it.endsWith("?")) it else "$it." }
        return when {
            problems.isEmpty() -> if (recording == 1) "Camera recording" else "$recording cameras recording"
            connected == 1 -> detail
            else -> "$recording of $connected cameras recording. $detail"
        }
    }

    /** True when every connected camera started and reported recording, which is when [startNote] has nothing to warn about. */
    fun startOk(connected: Int, results: Map<String, StartResult>): Boolean =
        results.size >= connected && results.values.all { it is StartResult.Started && it.recordingFlagAt != null }

    /** Null when every camera that was started confirmed the stop. */
    fun stopNote(started: Int, unconfirmed: List<String>): String? = when {
        unconfirmed.isEmpty() -> null
        started == 1 -> "The camera did not confirm the stop. Check that it stopped recording."
        else -> "${unconfirmed.joinToString(" and ")} did not confirm the stop. Check that ${if (unconfirmed.size == 1) "it" else "they"} stopped recording."
    }
}
