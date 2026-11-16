package dev.nanodash.core.ride

import dev.nanodash.core.fit.FitSport
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

    fun write(readings: List<HeartRateReading>): String =
        HEADER + "\n" + readings.sortedBy { it.utcMillis }.joinToString("") { "${it.utcMillis},${it.bpm}\n" }

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

/** Which sport the ride's .fit declares, kept as a one-word file (`sport.txt`) in the ride folder. Missing means motorcycling. */
object RideSport {
    const val FILE = "sport.txt"

    fun read(dir: File): FitSport =
        File(dir, FILE).takeIf { it.isFile && it.length() < 64 }?.readText()?.trim()?.uppercase()
            ?.let { name -> FitSport.entries.firstOrNull { it.name == name } } ?: FitSport.MOTORCYCLING

    fun write(dir: File, sport: FitSport) {
        if (sport == FitSport.MOTORCYCLING) File(dir, FILE).delete() else File(dir, FILE).writeText(sport.name.lowercase() + "\n")
    }
}
