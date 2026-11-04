package dev.nanodash.core.ride

import dev.nanodash.core.camera.CameraTiming
import dev.nanodash.core.fit.CameraSpan
import dev.nanodash.core.fit.FitActivityWriter

/**
 * Rebuilds a ride's .fit from the files the logger leaves behind. Useful when the app was killed before it could write
 * the .fit, or to regenerate a file after the writer improves. Pure functions: the caller reads and writes the files.
 */
object RideRecovery {
    /** Parses `gnss.csv` as written by the logger: `elapsed_ns,utc_ms,lat,lon,alt_m,speed_mps,bearing_deg,h_acc_m,...`. */
    fun parseGnssCsv(text: String): List<GnssFix> =
        text.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 8) return@mapNotNull null
            val utc = p[1].toLongOrNull() ?: return@mapNotNull null
            val lat = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val lon = p[3].toDoubleOrNull() ?: return@mapNotNull null
            GnssFix(utc, lat, lon, p[4].toDoubleOrNull(), p[5].toFloatOrNull(), p[6].toFloatOrNull(), p[7].toFloatOrNull(), p.getOrNull(10)?.trim()?.toFloatOrNull(), p[0].toLongOrNull())
        }.toList()

    private val SPAN = Regex("\"clip_span_estimate_utc_ms\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]")

    /** Every camera clip's phone-time window recorded in `events.jsonl` when a camera was stopped; one per camera. */
    fun clipSpansFromEvents(eventsJsonl: String?): List<CameraSpan> =
        eventsJsonl?.let { text -> SPAN.findAll(text).mapNotNull { m -> val a = m.groupValues[1].toLongOrNull(); val b = m.groupValues[2].toLongOrNull(); if (a != null && b != null) CameraSpan(a, b) else null }.toList() }
            ?: emptyList()

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

    private val CAMERA = Regex("\"camera\"\\s*:\\s*\"([^\"]*)\"")
    private val SHIFT = Regex("\"fit_shift_ms\"\\s*:\\s*(-?\\d+)")
    private val SKEW = Regex("\"phone_minus_gps_ms\"\\s*:\\s*(-?\\d+)")

    /** Like [clipSpansFromEvents] but with each camera's name and its calibrated shift (the default when the log has none). */
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
        val fileName: String get() = "ride$suffix.fit"
    }

    /**
     * The .fit files for a ride. Cameras whose clocks sit at the same distance from the phone share one file; cameras at
     * different distances each get their own, shifted to that camera's clock, because Mimo matches by the clip's own time.
     * Null when there are no GPS fixes.
     */
    fun buildFits(fixes: List<GnssFix>, clips: List<CameraClip>): List<NamedFit>? {
        val groups = clips.groupBy { it.shiftMs }.values.ifEmpty { listOf(emptyList()) }
        val built = groups.map { group ->
            val first = group.firstOrNull()
            val fit = buildFit(fixes, group.map { it.span }, first?.shiftMs ?: CameraTiming.FIT_SHIFT_MS, first?.phoneMinusGpsMs ?: 0) ?: return null
            (first?.camera?.filter { it.isLetterOrDigit() }?.takeLast(4).orEmpty()) to fit
        }
        return built.map { (tag, fit) -> NamedFit(if (built.size == 1 || tag.isEmpty()) "" else "-$tag", fit.bytes, fit.samples) }
    }

    /** Rebuilds every .fit of a ride from the files the logger left behind. */
    fun rebuildFits(gnssCsv: String, eventsJsonl: String?): List<NamedFit>? = buildFits(parseGnssCsv(gnssCsv), clipsFromEvents(eventsJsonl))

    /** An encoded .fit and the number of one-second samples in it. */
    class BuiltFit(val bytes: ByteArray, val samples: Int)

    /**
     * The .fit for a ride, or null if there are no GPS fixes. [shiftMs] is added to every time (records and the camera
     * window) so the file lines up with the clip's own clock: by default the camera's measured lag behind the phone. The
     * records are on GPS time and the camera [spans] on the phone's clock; [phoneMinusGpsMs] puts the spans on the records' time base first.
     */
    fun buildFit(fixes: List<GnssFix>, spans: List<CameraSpan>, shiftMs: Long = CameraTiming.FIT_SHIFT_MS, phoneMinusGpsMs: Long = 0): BuiltFit? {
        val samples = RideAssembler().assemble(fixes.map { it.copy(utcMillis = it.utcMillis + shiftMs) })
        if (samples.isEmpty()) return null
        val shifted = spans.map { CameraSpan(it.startUtcMillis + shiftMs - phoneMinusGpsMs, it.endUtcMillis + shiftMs - phoneMinusGpsMs) }
        return BuiltFit(FitActivityWriter().encode(samples, shifted), samples.size)
    }
}
