package dev.nanodash.core.fit

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One logged moment of a ride, 1 Hz in M1 (the GNSS rate). High-rate motion data goes to the sidecar, not the .fit. */
data class RideSample(
    val utcMillis: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Float,
    val speedMps: Float,
    val distanceM: Float,
    val leanAngleDeg: Float? = null,
    val lateralAccelMps2: Float? = null,
    val longitudinalAccelMps2: Float? = null,
)

/** A span during which the camera was recording, in phone UTC. The .fit marks records inside it with `camera_recording = 1`. */
data class CameraSpan(val startUtcMillis: Long, val endUtcMillis: Long)

/**
 * Writes an activity .fit with the same message set as Mimo's sports-training export (file_id, record, session), plus an
 * activity message and developer fields. Unlike Mimo's file, the file_id and session times are real (Mimo wrote times 20
 * years in the future there).
 *
 * The encoder is our own, written from the public FIT file-format description (header, definition and data messages,
 * CRC-16) and the global profile's message and field numbers for the handful of messages used here. It does not use or
 * include Garmin's SDK. Output is cross-checked against independent decoders (see tools/validate_fit.py).
 *
 * Developer fields (developer data index 0): lean_angle, lateral_accel, longitudinal_accel, camera_recording.
 */
class FitActivityWriter {
    fun encode(samples: List<RideSample>, cameraSpans: List<CameraSpan> = emptyList()): ByteArray {
        require(samples.isNotEmpty()) { "no samples" }
        val sorted = samples.sortedBy { it.utcMillis }
        val first = sorted.first()
        val last = sorted.last()
        val start = FitTime.fromUtcMillis(first.utcMillis)
        val end = FitTime.fromUtcMillis(last.utcMillis)
        val elapsedMs = (last.utcMillis - first.utcMillis).coerceAtLeast(0)

        val w = FitWriter()

        // file_id
        w.define(L_FILE_ID, 0, listOf(Field(0, 1, BT_ENUM), Field(1, 2, BT_UINT16), Field(2, 2, BT_UINT16), Field(4, 4, BT_UINT32)))
        w.data(L_FILE_ID) { u8(FILE_TYPE_ACTIVITY); u16(MANUFACTURER_DEVELOPMENT); u16(1); u32(start) }

        // developer data: one application id, four fields
        w.define(L_DEV_DATA_ID, 207, listOf(Field(1, 16, BT_BYTE), Field(3, 1, BT_UINT8)))
        w.data(L_DEV_DATA_ID) { bytes(APP_ID, 16); u8(0) }
        w.define(
            L_FIELD_DESC, 206,
            listOf(Field(0, 1, BT_UINT8), Field(1, 1, BT_UINT8), Field(2, 1, BT_UINT8), Field(3, 32, BT_STRING), Field(8, 16, BT_STRING)),
        )
        for ((num, name, units, type) in DEV_FIELDS) {
            w.data(L_FIELD_DESC) { u8(0); u8(num); u8(type); string(name, 32); string(units, 16) }
        }

        // records
        w.define(
            L_RECORD, 20,
            listOf(
                Field(253, 4, BT_UINT32), Field(0, 4, BT_SINT32), Field(1, 4, BT_SINT32), Field(2, 2, BT_UINT16), Field(5, 4, BT_UINT32),
                Field(6, 2, BT_UINT16), Field(9, 2, BT_SINT16), Field(73, 4, BT_UINT32), Field(78, 4, BT_UINT32),
            ),
            devFields = listOf(DevField(0, 4, 0), DevField(1, 4, 0), DevField(2, 4, 0), DevField(3, 1, 0)),
        )
        for ((i, s) in sorted.withIndex()) {
            val prev = sorted.getOrNull(i - 1)
            // Mimo's records carry a grade (percent); derive it from the change in altitude over the change in distance.
            val grade = if (prev != null && s.distanceM - prev.distanceM > 0.5f) {
                (s.altitudeM - prev.altitudeM) / (s.distanceM - prev.distanceM) * 100f
            } else 0f
            val recording = cameraSpans.any { s.utcMillis in it.startUtcMillis..it.endUtcMillis }
            w.data(L_RECORD) {
                u32(FitTime.fromUtcMillis(s.utcMillis))
                s32(semicircles(s.latitudeDeg))
                s32(semicircles(s.longitudeDeg))
                u16(altitudeRaw(s.altitudeM))
                u32((s.distanceM * 100.0).roundToLong().coerceIn(0, 0xFFFF_FFFEL))
                u16((s.speedMps * 1000.0).roundToInt().coerceIn(0, 0xFFFE).toLong())
                s16((grade.coerceIn(-100f, 100f) * 100f).roundToInt())
                u32((s.speedMps * 1000.0).roundToLong().coerceIn(0, 0xFFFF_FFFEL))
                u32(((s.altitudeM + 500.0) * 5.0).roundToLong().coerceIn(0, 0xFFFF_FFFEL))
                f32(s.leanAngleDeg)
                f32(s.lateralAccelMps2)
                f32(s.longitudinalAccelMps2)
                u8(if (recording) 1 else 0)
            }
        }

        // session
        val maxSpeed = sorted.maxOf { it.speedMps }
        val avgSpeed = if (elapsedMs > 0) last.distanceM / (elapsedMs / 1000f) else 0f
        w.define(
            L_SESSION, 18,
            listOf(
                Field(254, 2, BT_UINT16), Field(253, 4, BT_UINT32), Field(2, 4, BT_UINT32), Field(7, 4, BT_UINT32), Field(8, 4, BT_UINT32),
                Field(9, 4, BT_UINT32), Field(5, 1, BT_ENUM), Field(6, 1, BT_ENUM), Field(124, 4, BT_UINT32), Field(125, 4, BT_UINT32),
            ),
        )
        w.data(L_SESSION) {
            u16(0); u32(end); u32(start); u32(elapsedMs); u32(elapsedMs)
            u32((last.distanceM * 100.0).roundToLong().coerceIn(0, 0xFFFF_FFFEL))
            u8(SPORT_MOTORCYCLING); u8(SUB_SPORT_GENERIC)
            u32((avgSpeed * 1000.0).roundToLong().coerceIn(0, 0xFFFF_FFFEL))
            u32((maxSpeed * 1000.0).roundToLong().coerceIn(0, 0xFFFF_FFFEL))
        }

        // activity
        w.define(
            L_ACTIVITY, 34,
            listOf(Field(253, 4, BT_UINT32), Field(0, 4, BT_UINT32), Field(1, 2, BT_UINT16), Field(2, 1, BT_ENUM), Field(3, 1, BT_ENUM), Field(4, 1, BT_ENUM)),
        )
        w.data(L_ACTIVITY) { u32(end); u32(elapsedMs); u16(1); u8(ACTIVITY_TYPE_MANUAL); u8(EVENT_ACTIVITY); u8(EVENT_TYPE_STOP) }

        return w.finish()
    }

    private fun semicircles(deg: Double): Long = (deg * (2147483648.0 / 180.0)).roundToLong()

    private fun altitudeRaw(altM: Float): Long = ((altM + 500.0) * 5.0).roundToLong().coerceIn(0, 0xFFFE)

    private data class DevFieldDesc(val number: Int, val name: String, val units: String, val baseType: Int)

    companion object {
        /** 16-byte application id for the developer data; the ASCII text "nano-dash-logger". */
        val APP_ID: ByteArray = "nano-dash-logger".toByteArray(Charsets.US_ASCII)

        // local message types (0..15) used in the file
        private const val L_FILE_ID = 0
        private const val L_DEV_DATA_ID = 1
        private const val L_FIELD_DESC = 2
        private const val L_RECORD = 3
        private const val L_SESSION = 4
        private const val L_ACTIVITY = 5

        // enum values from the FIT global profile
        private const val FILE_TYPE_ACTIVITY = 4
        private const val MANUFACTURER_DEVELOPMENT = 255
        private const val SPORT_MOTORCYCLING = 22
        private const val SUB_SPORT_GENERIC = 0
        private const val ACTIVITY_TYPE_MANUAL = 0
        private const val EVENT_ACTIVITY = 26
        private const val EVENT_TYPE_STOP = 1

        private val DEV_FIELDS = listOf(
            DevFieldDesc(0, "lean_angle", "deg", BT_FLOAT32),
            DevFieldDesc(1, "lateral_accel", "m/s^2", BT_FLOAT32),
            DevFieldDesc(2, "longitudinal_accel", "m/s^2", BT_FLOAT32),
            DevFieldDesc(3, "camera_recording", "bool", BT_UINT8),
        )
    }
}
