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

/** The JSON keys that the service writes into `meta.json` and `events.jsonl` and that [RideFolder] and [RideRecovery] read back. */
object LogKey {
    const val START_UTC_MS = "start_utc_ms"
    const val END_UTC_MS = "end_utc_ms"
    const val GNSS_FIXES = "gnss_fixes"
    const val CAMERA = "camera"
    const val FIT_SHIFT_MS = "fit_shift_ms"
    const val PHONE_MINUS_GPS_MS = "phone_minus_gps_ms"
    const val CLIP_SPAN = "clip_span_estimate_utc_ms"
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
