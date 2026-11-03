package dev.nanodash.core.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockSkewTest {
    private val startUtc = 1_793_676_000_000L
    private val startElapsed = 5_000_000_000L

    /** A fix [i] seconds into the ride whose GPS time is the phone's clock minus [phoneAheadMs]. */
    private fun fix(i: Int, phoneAheadMs: Long, jitterMs: Long = 0) = GnssFix(
        utcMillis = startUtc + i * 1000L - phoneAheadMs + jitterMs, latitudeDeg = 0.0, longitudeDeg = 0.0, altitudeM = null, speedMps = null,
        bearingDeg = null, horizontalAccuracyM = null, elapsedNs = startElapsed + i * 1_000_000_000L,
    )

    @Test
    fun aPhoneThatRunsAheadShowsAsPositive() {
        assertEquals(800L, ClockSkew.phoneMinusGpsMs((0 until 20).map { fix(it, 800) }, startUtc, startElapsed))
        assertEquals(-250L, ClockSkew.phoneMinusGpsMs((0 until 20).map { fix(it, -250) }, startUtc, startElapsed))
    }

    @Test
    fun aFewOddFixesDoNotMoveTheMedian() {
        val fixes = (0 until 21).map { fix(it, 100, jitterMs = if (it % 7 == 0) 900 else 0) }
        assertEquals(100L, ClockSkew.phoneMinusGpsMs(fixes, startUtc, startElapsed))
    }

    @Test
    fun tooFewFixesOrOldLogsGiveNoAnswer() {
        assertNull(ClockSkew.phoneMinusGpsMs((0 until 3).map { fix(it, 500) }, startUtc, startElapsed))
        assertNull(ClockSkew.phoneMinusGpsMs((0 until 20).map { fix(it, 500).copy(elapsedNs = null) }, startUtc, startElapsed))
    }

    @Test
    fun aNonsenseClockIsIgnoredInsteadOfShiftingTheWholeFile() {
        assertNull(ClockSkew.phoneMinusGpsMs((0 until 20).map { fix(it, 600_000) }, startUtc, startElapsed))
    }
}
