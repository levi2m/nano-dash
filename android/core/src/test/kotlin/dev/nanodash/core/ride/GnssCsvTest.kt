package dev.nanodash.core.ride

import kotlin.test.Test
import kotlin.test.assertEquals

class GnssCsvTest {
    @Test
    fun whatTheServiceWritesIsWhatRecoveryReadsBack() {
        val full = GnssFix(
            utcMillis = 1_793_676_577_000, latitudeDeg = -20.265123, longitudeDeg = -40.295987, altitudeM = 5.5, speedMps = 12.5f,
            bearingDeg = 90.25f, horizontalAccuracyM = 3.5f, verticalAccuracyM = 4.25f, elapsedNs = 123_456_789_012,
            speedAccuracyMps = 0.4f, bearingAccuracyDeg = 2.5f,
        )
        val sparse = GnssFix(1_793_676_578_000, -20.0, -40.0, altitudeM = null, speedMps = null, bearingDeg = null, horizontalAccuracyM = null)
        val text = GnssCsv.HEADER + "\n" + GnssCsv.line(full) + GnssCsv.line(sparse)
        assertEquals(listOf(full, sparse), GnssCsv.parse(text))
    }
}
