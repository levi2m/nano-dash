package dev.nanodash.core.ride

import dev.nanodash.core.fit.RideSample
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One GNSS fix as the phone reports it. Optional fields are null when the receiver did not supply them. */
data class GnssFix(
    val utcMillis: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val horizontalAccuracyM: Float?,
    val verticalAccuracyM: Float? = null,
    /** The phone's monotonic clock when the fix was made, to compare the phone's UTC clock with the GPS's. */
    val elapsedNs: Long? = null,
)

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north, 0 to 360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest signed angle from [from] to [to], in degrees, range -180 to 180 (positive = clockwise). */
    fun deltaDeg(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }
}

/**
 * Turns the phone's raw GNSS fixes into the 1 Hz samples written to the .fit, and derives the extra channels from the
 * track alone (no phone mounting assumptions):
 *  - lateral acceleration = speed x heading rate, and lean angle = atan(lateral acceleration / g). This is the steady-state
 *    cornering estimate; it ignores suspension, tyre profile and rider position, so treat it as an estimate. Positive is a right turn.
 *  - longitudinal acceleration = change in speed per second.
 * Derivatives are reset across gaps longer than [maxGapS] and heading is only trusted above [minSpeedForHeadingMps].
 */
class RideAssembler {
    fun assemble(fixes: List<GnssFix>): List<RideSample> {
        val sorted = fixes.sortedBy { it.utcMillis }.distinctBy { it.utcMillis / 1000 }
        // A fix without altitude carries the previous one forward; before the first known altitude use that one, not 0 m,
        // so the grade does not spike when the first real altitude arrives.
        // A cold GPS start gives altitudes that are tens of metres off, flagged by a large vertical accuracy: ignore those.
        fun altitudeOf(f: GnssFix) = f.altitudeM?.takeIf { (f.verticalAccuracyM ?: 0f) <= MAX_VERTICAL_ACCURACY_M }
        val firstAltitude = sorted.firstNotNullOfOrNull { altitudeOf(it) } ?: 0.0
        val out = ArrayList<RideSample>(sorted.size)
        var distance = 0.0
        var prev: GnssFix? = null
        var prevSpeed = 0.0
        var prevHeading: Double? = null
        var latAcc = 0.0
        var longAcc = 0.0

        for (f in sorted) {
            val p = prev
            val dt = if (p == null) 0.0 else (f.utcMillis - p.utcMillis) / 1000.0
            val gap = p == null || dt <= 0 || dt > maxGapS
            val step = if (p == null) 0.0 else GeoMath.distanceM(p.latitudeDeg, p.longitudeDeg, f.latitudeDeg, f.longitudeDeg)

            val speed = (f.speedMps?.toDouble() ?: if (!gap) step / dt else 0.0).coerceAtLeast(0.0)
            if (!gap && speed > 0.3) distance += step

            val heading: Double? = when {
                speed < minSpeedForHeadingMps -> null
                f.bearingDeg != null -> f.bearingDeg.toDouble()
                !gap && step >= 3.0 -> GeoMath.bearingDeg(p!!.latitudeDeg, p.longitudeDeg, f.latitudeDeg, f.longitudeDeg)
                else -> null
            }

            if (gap) {
                latAcc = 0.0
                longAcc = 0.0
            } else {
                val rawLong = (speed - prevSpeed) / dt
                longAcc = smoothing * longAcc + (1 - smoothing) * rawLong
                val h0 = prevHeading
                val rawLat = if (heading != null && h0 != null) speed * Math.toRadians(GeoMath.deltaDeg(h0, heading)) / dt else 0.0
                latAcc = smoothing * latAcc + (1 - smoothing) * rawLat
            }
            val lean = Math.toDegrees(atan(latAcc / G))

            out += RideSample(
                utcMillis = f.utcMillis,
                latitudeDeg = f.latitudeDeg,
                longitudeDeg = f.longitudeDeg,
                altitudeM = (altitudeOf(f) ?: out.lastOrNull()?.altitudeM?.toDouble() ?: firstAltitude).toFloat(),
                speedMps = speed.toFloat(),
                distanceM = distance.toFloat(),
                leanAngleDeg = lean.toFloat(),
                lateralAccelMps2 = latAcc.toFloat(),
                longitudinalAccelMps2 = longAcc.toFloat(),
            )
            prev = f
            prevSpeed = speed
            prevHeading = heading
        }
        return out
    }

    companion object {
        const val G = 9.80665
        private const val MAX_VERTICAL_ACCURACY_M = 20f
        private const val maxGapS = 3.0
        private const val minSpeedForHeadingMps = 2.0
        private const val smoothing = 0.5
    }
}
