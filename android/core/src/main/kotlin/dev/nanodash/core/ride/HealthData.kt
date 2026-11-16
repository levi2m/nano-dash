package dev.nanodash.core.ride

import dev.nanodash.core.fit.RideSample
import java.io.File

/** One heart-rate reading, on UTC time as a watch or strap reports it (through Health Connect). */
data class HeartRateReading(val utcMillis: Long, val bpm: Int)

/**
 * Health data that belongs to a ride, kept next to its GPS log as `health.csv` so the .fit can be rebuilt from the folder at any
 * time, like every other ride file. The app writes it after the ride, from Health Connect; nothing here touches Android or the network.
 */
object HealthCsv {
    const val FILE = "health.csv"
    private const val HEADER = "utc_ms,heart_rate_bpm"

    /** The lowest and highest rates kept. A reading outside this range is a sensor glitch, not a heart rate. */
    const val MIN_BPM = 30
    const val MAX_BPM = 250

    /** Only the readings in range, so the file and the .fit agree on what a reading is (a watch can write 0 bpm for a stretch with no pulse). */
    fun clean(readings: List<HeartRateReading>): List<HeartRateReading> = readings.filter { it.bpm in MIN_BPM..MAX_BPM }

    fun write(readings: List<HeartRateReading>): String =
        HEADER + "\n" + clean(readings).sortedBy { it.utcMillis }.joinToString("") { "${it.utcMillis},${it.bpm}\n" }

    /** How many readings the file holds, counted by lines without parsing them (the ride list asks for every ride on every refresh). 0 when there is no file. */
    fun count(file: File): Int =
        if (!file.isFile || file.length() > RideFolder.MAX_TEXT_BYTES) 0 else file.useLines { lines -> (lines.count { it.isNotBlank() } - 1).coerceAtLeast(0) }

    /** Bad lines and out-of-range rates are skipped, not repaired. */
    fun parse(text: String?): List<HeartRateReading> =
        text?.lineSequence()?.drop(1)?.mapNotNull { line ->
            val p = line.split(',')
            val utc = p.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val bpm = p.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in MIN_BPM..MAX_BPM } ?: return@mapNotNull null
            HeartRateReading(utc, bpm)
        }?.sortedBy { it.utcMillis }?.toList().orEmpty()
}

/** Puts heart-rate readings onto the 1 Hz ride samples by time. */
object HealthMerge {
    /**
     * A reading further than this from a sample is not used for it: a gap in the watch's data stays a gap in the file, and
     * nothing is invented across it. Five seconds is a choice, not a measurement; the readings of a workout are expected to
     * come every second or so, but that is unchecked (see docs/HEALTH.md).
     */
    const val MAX_DISTANCE_MS = 5_000L

    /**
     * Each sample gets the nearest reading within [MAX_DISTANCE_MS], or none. [readingShiftMs] is added to every reading's time
     * first, so the readings follow the same shift as the GPS records (the camera's lag; see CameraTiming).
     */
    fun attach(samples: List<RideSample>, readings: List<HeartRateReading>, readingShiftMs: Long = 0): List<RideSample> {
        if (readings.isEmpty()) return samples
        val sorted = readings.map { it.copy(utcMillis = it.utcMillis + readingShiftMs) }.sortedBy { it.utcMillis }
        return samples.map { s ->
            val near = nearest(sorted, s.utcMillis)?.takeIf { Math.abs(it.utcMillis - s.utcMillis) <= MAX_DISTANCE_MS }
            if (near == null) s else s.copy(heartRateBpm = near.bpm)
        }
    }

    private fun nearest(sorted: List<HeartRateReading>, t: Long): HeartRateReading? {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (sorted[mid].utcMillis < t) lo = mid + 1 else hi = mid }
        val after = sorted.getOrNull(lo)
        val before = sorted.getOrNull(lo - 1)
        return when {
            after == null -> before
            before == null -> after
            t - before.utcMillis <= after.utcMillis - t -> before
            else -> after
        }
    }
}

/** Which rides the app looks up heart rate for by itself. */
object HealthImport {
    const val DAYS = 3L
    const val MAX_RIDES = 5

    /**
     * Rides that have a .fit and GPS fixes but no readings yet, and ended no more than [DAYS] days before [nowMs], newest first, at most [MAX_RIDES].
     * A ride cut off has no duration, so its start stands for its end. [rides] come newest first, as [RideFolder.list] returns them.
     */
    fun due(rides: List<RideSummary>, nowMs: Long): List<RideSummary> {
        val cutoff = nowMs - DAYS * 86_400_000L
        return rides.filter { it.hasFit && it.fixes > 0 && it.heartRateReadings == 0 && it.startUtcMillis + (it.durationSeconds ?: 0) * 1000 > cutoff }.take(MAX_RIDES)
    }
}
