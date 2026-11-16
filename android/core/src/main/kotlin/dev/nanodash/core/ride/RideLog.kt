package dev.nanodash.core.ride

/** The files of one ride folder, named in one place so the service that writes them and the code that reads them cannot drift apart. */
object RideLog {
    const val GNSS = "gnss.csv"
    const val IMU = "imu.csv"
    const val EVENTS = "events.jsonl"
    const val META = "meta.json"
    const val HEALTH = "health.csv"

    /** The list's cache of a finished ride's route and figures; never shared. */
    const val ROUTE_CACHE = "route.txt"

    const val FOLDER_PREFIX = "ride-"

    /** `ride.fit`, or `ride-<tag>.fit` when each camera clock needs its own file. */
    fun fitName(suffix: String = "") = "ride$suffix.fit"

    fun isFit(name: String) = name.startsWith("ride") && name.endsWith(".fit")
}

/**
 * Every JSON key the service writes into `meta.json` and `events.jsonl`. [RideFolder] and [RideRecovery] read some of them back by
 * regex, so all of them are named here. In `events.jsonl` [FIT_SHIFT_MS] is the camera's clock lag plus the phone-versus-GPS skew,
 * which is what the .fit is shifted by; `meta.json` keeps the lag alone under [CLOCK_LAG_MS].
 */
object LogKey {
    const val EVENT = "event"
    const val START_UTC_MS = "start_utc_ms"
    const val END_UTC_MS = "end_utc_ms"
    const val GNSS_FIXES = "gnss_fixes"
    const val CAMERA = "camera"
    const val CAMERAS = "cameras"
    const val FIT_SHIFT_MS = "fit_shift_ms"
    const val CLOCK_LAG_MS = "clock_lag_ms"
    const val PHONE_MINUS_GPS_MS = "phone_minus_gps_ms"
    const val CLIP_SPAN = "clip_span_estimate_utc_ms"
    const val COMMAND_SENT = "command_sent"
    const val REPLY = "reply"
    const val RECORDING_FLAG = "recording_flag"
    const val FLAG_CLEARED = "flag_cleared"
    const val START_RESULT = "start_result"
    const val CODE = "code"
    const val UTC_MS = "utc_ms"
    const val ELAPSED_NS = "elapsed_ns"
    const val START_ELAPSED_NS = "start_elapsed_ns"
    const val FORMAT_VERSION = "format_version"
    const val APP = "app"
    const val ALTITUDE_REFERENCE = "altitude_reference"
    const val DEVICE = "device"
    const val ANDROID_SDK = "android_sdk"
    const val IMU_EVENTS_TOTAL = "imu_events_total"
    const val IMU_EVENTS_BY_SENSOR_TYPE = "imu_events_by_sensor_type"
    const val FIT_WRITTEN = "fit_written"
    const val FIT_SAMPLES = "fit_samples"
}

/** The `event` values of `events.jsonl`. */
object LogEvent {
    const val CAMERA_NOT_READY = "camera_not_ready"
    const val CAMERA_START = "camera_start"
    const val CAMERA_START_REJECTED = "camera_start_rejected"
    const val CAMERA_STOP = "camera_stop"
    const val CAMERA_STOP_UNTIMED = "camera_stop_untimed"
}

/** `gnss.csv`: one line per GPS fix, written by the service and read back to rebuild the .fit. */
object GnssCsv {
    const val HEADER = "elapsed_ns,utc_ms,lat,lon,alt_m,speed_mps,bearing_deg,h_acc_m,speed_acc_mps,bearing_acc_deg,v_acc_m"

    /** One line with its line ending; fields the receiver did not supply are empty. */
    fun line(f: GnssFix): String = buildString {
        append(f.elapsedNs ?: "").append(',').append(f.utcMillis).append(',')
            .append(f.latitudeDeg).append(',').append(f.longitudeDeg).append(',')
            .append(f.altitudeM ?: "").append(',').append(f.speedMps ?: "").append(',').append(f.bearingDeg ?: "").append(',')
            .append(f.horizontalAccuracyM ?: "").append(',').append(f.speedAccuracyMps ?: "").append(',').append(f.bearingAccuracyDeg ?: "").append(',')
            .append(f.verticalAccuracyM ?: "").append('\n')
    }

    /** Bad lines are skipped, not repaired. */
    fun parse(text: String): List<GnssFix> =
        text.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 8) return@mapNotNull null
            val utc = p[1].toLongOrNull() ?: return@mapNotNull null
            val lat = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val lon = p[3].toDoubleOrNull() ?: return@mapNotNull null
            fun float(i: Int) = p.getOrNull(i)?.trim()?.toFloatOrNull()
            GnssFix(
                utcMillis = utc, latitudeDeg = lat, longitudeDeg = lon, altitudeM = p[4].toDoubleOrNull(),
                speedMps = float(5), bearingDeg = float(6), horizontalAccuracyM = float(7), verticalAccuracyM = float(10),
                elapsedNs = p[0].toLongOrNull(), speedAccuracyMps = float(8), bearingAccuracyDeg = float(9),
            )
        }.toList()
}
