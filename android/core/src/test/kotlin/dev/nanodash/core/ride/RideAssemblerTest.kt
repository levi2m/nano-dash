package dev.nanodash.core.ride

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RideAssemblerTest {
    private val lat0 = -20.2650
    private val lon0 = -40.2950
    private val start = 1793673900000L

    /** A circle of [radiusM] metres at [speed] m/s, clockwise (right turn) or anticlockwise, one fix per second. */
    private fun circle(seconds: Int, radiusM: Double = 50.0, speed: Double = 10.0, clockwise: Boolean = true): List<GnssFix> {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))
        val sign = if (clockwise) 1.0 else -1.0
        return (0..seconds).map { t ->
            val phi = sign * speed / radiusM * t                 // angle of the position around the centre, from north
            val north = radiusM * cos(phi)
            val east = radiusM * sin(phi)
            // velocity direction for a clockwise circle is (cos phi, -sin phi) in (east, north); mirrored for anticlockwise
            val bearing = (Math.toDegrees(atan2(sign * cos(phi), -sign * sin(phi))) + 360.0) % 360.0
            GnssFix(start + t * 1000L, lat0 + north / mPerDegLat, lon0 + east / mPerDegLon, 5.0, speed.toFloat(), bearing.toFloat(), 3f)
        }
    }

    @Test
    fun rightTurnGivesPositiveLeanMatchingPhysics() {
        val samples = RideAssembler().assemble(circle(120))
        val expectedLat = 10.0 * 10.0 / 50.0                      // v^2 / r = 2 m/s^2
        val expectedLean = Math.toDegrees(atan(expectedLat / RideAssembler.G))
        val settled = samples.drop(6)
        for (s in settled) {
            assertEquals(expectedLat, s.lateralAccelMps2!!.toDouble(), 0.25)
            assertEquals(expectedLean, s.leanAngleDeg!!.toDouble(), 1.5)
            assertTrue(abs(s.longitudinalAccelMps2!!) < 0.25)
        }
    }

    @Test
    fun leftTurnGivesNegativeLean() {
        val samples = RideAssembler().assemble(circle(60, clockwise = false))
        assertTrue(samples.drop(6).all { it.leanAngleDeg!! < -9f })
    }

    @Test
    fun distanceMatchesTheChordsOfTheCircle() {
        val samples = RideAssembler().assemble(circle(120))
        // 120 chords of 2 r sin(dphi/2), dphi = 0.2 rad
        val expected = 120 * 2 * 50.0 * sin(0.1)
        assertEquals(expected, samples.last().distanceM.toDouble(), 3.0)
    }

    @Test
    fun straightLineHasNoLean() {
        val m = 111_320.0
        val fixes = (0..30).map { t -> GnssFix(start + t * 1000L, lat0 + 15.0 * t / m, lon0, 5.0, 15f, 0f, 3f) }
        val s = RideAssembler().assemble(fixes)
        assertTrue(s.all { abs(it.leanAngleDeg!!) < 0.5f })
        assertEquals(15.0 * 30, s.last().distanceM.toDouble(), 5.0)
    }

    @Test
    fun aGapResetsTheDerivativesInsteadOfSpiking() {
        val a = circle(20)
        val b = circle(20).map { it.copy(utcMillis = it.utcMillis + 40_000) }        // 20 s hole in the middle
        val samples = RideAssembler().assemble(a + b)
        val firstAfterGap = samples.first { it.utcMillis >= start + 40_000 }
        assertEquals(0.0, firstAfterGap.lateralAccelMps2!!.toDouble(), 1e-6)
        assertTrue(samples.all { abs(it.leanAngleDeg!!) < 20f })
    }

    @Test
    fun missingSpeedAndBearingAreDerivedFromPositions() {
        val fixes = circle(60).map { it.copy(speedMps = null, bearingDeg = null) }
        val samples = RideAssembler().assemble(fixes)
        val expectedLean = Math.toDegrees(atan(2.0 / RideAssembler.G))
        assertEquals(expectedLean, samples.drop(8).map { it.leanAngleDeg!!.toDouble() }.average(), 2.0)
        assertEquals(10.0, samples.drop(8).map { it.speedMps.toDouble() }.average(), 0.3)
    }

    @Test
    fun duplicateSecondsAreDroppedAndOrderIsRestored() {
        val f = circle(10)
        val shuffled = (f + f[3].copy(latitudeDeg = f[3].latitudeDeg + 0.001)).reversed()
        val samples = RideAssembler().assemble(shuffled)
        assertEquals(11, samples.size)
        assertTrue(samples.zipWithNext().all { (x, y) -> x.utcMillis < y.utcMillis })
        assertNotNull(samples.first().leanAngleDeg)
    }

    @Test
    fun aMissingFirstAltitudeDoesNotMakeAGradeSpike() {
        val m = 111_320.0
        val fixes = (0..5).map { t ->
            GnssFix(start + t * 1000L, lat0 + 15.0 * t / m, lon0, if (t < 2) null else 300.0, 15f, 0f, 3f)
        }
        val s = RideAssembler().assemble(fixes)
        assertTrue(s.all { it.altitudeM == 300f }, "altitudes: ${s.map { it.altitudeM }}")
    }

    @Test
    fun coldStartAltitudesWithPoorVerticalAccuracyAreIgnored() {
        val m = 111_320.0
        val fixes = (0..5).map { t ->
            val cold = t < 3
            GnssFix(start + t * 1000L, lat0 + 15.0 * t / m, lon0, if (cold) -48.0 else 12.0, 15f, 0f, 3f, if (cold) 60f else 4f)
        }
        val s = RideAssembler().assemble(fixes)
        assertTrue(s.all { it.altitudeM == 12f }, "altitudes: ${s.map { it.altitudeM }}")
    }

    @Test
    fun geoMathBearingsAndAngles() {
        assertEquals(0.0, GeoMath.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, GeoMath.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(20.0, GeoMath.deltaDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, GeoMath.deltaDeg(10.0, 350.0), 1e-9)
        assertEquals(111_195.0, GeoMath.distanceM(0.0, 0.0, 1.0, 0.0), 50.0)
    }
}
