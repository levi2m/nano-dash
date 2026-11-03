package dev.nanodash.core.ride

/**
 * The .fit times come from the GPS; the camera's clock was set from the phone's. If the phone's clock is off, the overlay is
 * off by the same amount. Every fix carries its GPS time and the phone's monotonic clock, and the ride's start recorded both
 * the phone's UTC clock and the monotonic clock, so the two UTC clocks can be compared.
 */
object ClockSkew {
    private const val MIN_FIXES = 5
    private const val MAX_PLAUSIBLE_MS = 120_000L

    /** Phone UTC clock minus GPS time, in ms (positive: the phone runs ahead), or null if it cannot be told or is implausible. */
    fun phoneMinusGpsMs(fixes: List<GnssFix>, startUtcMs: Long, startElapsedNs: Long): Long? {
        val diffs = fixes.mapNotNull { f ->
            f.elapsedNs?.let { startUtcMs + (it - startElapsedNs) / 1_000_000 - f.utcMillis }
        }.sorted()
        if (diffs.size < MIN_FIXES) return null
        val median = diffs[diffs.size / 2]
        return median.takeIf { kotlin.math.abs(it) <= MAX_PLAUSIBLE_MS }
    }
}
