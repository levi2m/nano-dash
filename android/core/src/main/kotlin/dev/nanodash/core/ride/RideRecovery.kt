package dev.nanodash.core.ride

import dev.nanodash.core.camera.CameraTiming
import dev.nanodash.core.fit.CameraSpan
import dev.nanodash.core.fit.FitActivityWriter

/**
 * Rebuilds a ride's .fit from the files the logger leaves behind. Useful when the app was killed before it could write
 * the .fit, or to regenerate a file after the writer improves. Pure functions: the caller reads and writes the files.
 */
object RideRecovery {
    private val SPAN = Regex("\"${LogKey.CLIP_SPAN}\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]")

    /** Every camera clip's phone-time window recorded in `events.jsonl` when a camera was stopped; one per camera. */
    fun clipSpansFromEvents(eventsJsonl: String?): List<CameraSpan> = clipsFromEvents(eventsJsonl).map { it.span }

    /** One camera's clip window and how far its clock sits from the phone's, as logged when it was stopped. */
    data class CameraClip(
        val camera: String,
        /** In the phone's clock, as logged. */
        val span: CameraSpan,
        /** What to add to the GPS-time records: the camera's lag behind the phone plus how far the phone ran from the GPS. */
        val shiftMs: Long,
        /** Phone clock minus GPS time, which moves [span] onto the same time base as the records. */
        val phoneMinusGpsMs: Long = 0,
    )

    private val CAMERA = Regex("\"${LogKey.CAMERA}\"\\s*:\\s*\"([^\"]*)\"")
    private val SHIFT = Regex("\"${LogKey.FIT_SHIFT_MS}\"\\s*:\\s*(-?\\d+)")
    private val SKEW = Regex("\"${LogKey.PHONE_MINUS_GPS_MS}\"\\s*:\\s*(-?\\d+)")

    /** Each camera clip in `events.jsonl` with its name and calibrated shift (the legacy shift when the log has none). */
    fun clipsFromEvents(eventsJsonl: String?): List<CameraClip> =
        eventsJsonl?.lineSequence().orEmpty().mapNotNull { line ->
            val span = SPAN.find(line) ?: return@mapNotNull null
            val start = span.groupValues[1].toLongOrNull() ?: return@mapNotNull null    // digits too long for a Long: skip the line
            val end = span.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            CameraClip(
                CAMERA.find(line)?.groupValues?.get(1) ?: "",
                CameraSpan(start, end),
                SHIFT.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: CameraTiming.LEGACY_FIT_SHIFT_MS,
                SKEW.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0,
            )
        }.toList()

    /** A finished .fit: [suffix] is "" for the usual `ride.fit`, or a tag such as "-1A2B" when each camera needs its own file. */
    class NamedFit(val suffix: String, val bytes: ByteArray, val samples: Int) {
        val fileName: String get() = RideLog.fitName(suffix)
    }

    /**
     * The .fit files for a ride. Cameras whose clocks sit at the same distance from the phone share one file; cameras at
     * different distances each get their own, shifted to that camera's clock, because Mimo matches by the clip's own time.
     * Null when there are no GPS fixes.
     */
    fun buildFits(
        fixes: List<GnssFix>,
        clips: List<CameraClip>,
        heartRate: List<HeartRateReading> = emptyList(),
    ): List<NamedFit>? {
        val groups = clips.groupBy { it.shiftMs }.values.ifEmpty { listOf(emptyList()) }
        val built = groups.map { group ->
            val first = group.firstOrNull()
            val fit = buildFit(fixes, group.map { it.span }, first?.shiftMs ?: CameraTiming.FIT_SHIFT_MS, first?.phoneMinusGpsMs ?: 0, heartRate) ?: return null
            (first?.camera?.filter { it.isLetterOrDigit() }?.takeLast(4).orEmpty()) to fit
        }
        return built.map { (tag, fit) -> NamedFit(if (built.size == 1 || tag.isEmpty()) "" else "-$tag", fit.bytes, fit.samples) }
    }

    /** Rebuilds every .fit of a ride from the files the logger left behind. */
    fun rebuildFits(gnssCsv: String, eventsJsonl: String?, healthCsv: String? = null): List<NamedFit>? =
        buildFits(GnssCsv.parse(gnssCsv), clipsFromEvents(eventsJsonl), HealthCsv.parse(healthCsv))

    /** An encoded .fit and the number of one-second samples in it. */
    class BuiltFit(val bytes: ByteArray, val samples: Int)

    /**
     * The .fit for a ride, or null if there are no GPS fixes. [shiftMs] is added to every time (records and the camera
     * window) so the file lines up with the clip's own clock: by default the camera's measured lag behind the phone. The
     * records are on GPS time and the camera [spans] on the phone's clock; [phoneMinusGpsMs] puts the spans on the records' time base first.
     */
    fun buildFit(
        fixes: List<GnssFix>,
        spans: List<CameraSpan>,
        shiftMs: Long = CameraTiming.FIT_SHIFT_MS,
        phoneMinusGpsMs: Long = 0,
        heartRate: List<HeartRateReading> = emptyList(),
    ): BuiltFit? {
        val assembled = RideAssembler().assemble(fixes.map { it.copy(utcMillis = it.utcMillis + shiftMs) })
        if (assembled.isEmpty()) return null
        // The readings come from the watch, on the phone's clock like the camera spans, while the records are on GPS time: they get the same
        // move as the spans (the camera's lag, and the phone-versus-GPS difference), so heart rate stays level with the position.
        val samples = HealthMerge.attach(assembled, heartRate, shiftMs - phoneMinusGpsMs)
        val shifted = spans.map { CameraSpan(it.startUtcMillis + shiftMs - phoneMinusGpsMs, it.endUtcMillis + shiftMs - phoneMinusGpsMs) }
        return BuiltFit(FitActivityWriter().encode(samples, shifted), samples.size)
    }
}
