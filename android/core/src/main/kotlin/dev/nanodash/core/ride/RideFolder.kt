package dev.nanodash.core.ride

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the ride list shows about one ride folder, read from the files the logger wrote. */
class RideSummary(
    val dir: File,
    val startUtcMillis: Long,
    val durationSeconds: Long?,
    val fixes: Int,
    /** The longest wait between two GPS fixes; a long one on a screen-off ride means the phone paused the app. Null when unknown. */
    val longestGapSeconds: Long?,
    /** Distance in metres counted as the .fit counts it (not across GPS gaps or at a standstill), and the highest speed the GPS reported. Null when unknown. */
    val distanceM: Double?,
    val topSpeedMps: Float?,
    val hasFit: Boolean,
    /** Length of each camera's clip, in the order the cameras were stopped. */
    val cameraClipSeconds: List<Double>,
    val sizeKb: Long,
    /** Latitude and longitude of the track, thinned to at most [RideFolder.MAX_ROUTE_POINTS] points. */
    val route: List<Pair<Double, Double>>,
    val files: List<File>,
) {
    /** `ride.fit`, or one `ride-<camera>.fit` per camera clock when the cameras' clocks differ. */
    val fits: List<File> get() = RideFolder.fitFiles(dir)
}

/** The ride folders on disk: listing, rebuilding the .fit files, deleting. Plain files only, so it is tested on a PC. */
object RideFolder {
    const val MAX_ROUTE_POINTS = 160

    /** Ride files are read whole, so one far larger than any real ride (a corrupt or hostile file) is skipped instead of exhausting memory. */
    const val MAX_TEXT_BYTES = 64L * 1024 * 1024

    /** The file's text, or null when it is missing, not a file, or larger than [maxBytes]. */
    fun readTextCapped(file: File, maxBytes: Long = MAX_TEXT_BYTES): String? =
        if (file.isFile && file.length() <= maxBytes) file.readText() else null

    private val SHARED = listOf("gnss.csv", "imu.csv", "events.jsonl", "meta.json")
    private val START = Regex("\"start_utc_ms\"\\s*:\\s*(\\d+)")
    private val END = Regex("\"end_utc_ms\"\\s*:\\s*(\\d+)")
    private val FIXES = Regex("\"gnss_fixes\"\\s*:\\s*(\\d+)")

    fun fitFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("ride") && f.name.endsWith(".fit") }?.sortedBy { it.name } ?: emptyList()

    /** Newest first. A folder that cannot be read is skipped rather than failing the list. */
    fun list(root: File): List<RideSummary> =
        root.listFiles { f -> f.isDirectory && f.name.startsWith("ride-") }
            ?.sortedByDescending { it.name }?.mapNotNull { runCatching { summarize(it) }.getOrNull() } ?: emptyList()

    fun summarize(dir: File): RideSummary {
        val meta = readTextCapped(File(dir, "meta.json")).orEmpty()
        val start = START.find(meta)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = END.find(meta)?.groupValues?.get(1)?.toLongOrNull()        // null while recording and for a ride that was cut off
        val track = trackOf(dir, finished = end != null, metaFixes = FIXES.find(meta)?.groupValues?.get(1)?.toIntOrNull())

        val clips = RideRecovery.clipSpansFromEvents(readTextCapped(File(dir, "events.jsonl")))
            .map { (it.endUtcMillis - it.startUtcMillis) / 1000.0 }
        val fits = fitFiles(dir)
        val files = fits + SHARED.map { File(dir, it) }.filter { it.exists() }
        return RideSummary(
            dir = dir,
            startUtcMillis = if (start > 0) start else dir.lastModified(),
            durationSeconds = if (start > 0 && end != null && end > start) (end - start) / 1000 else null,
            fixes = track.fixes,
            longestGapSeconds = track.longestGapSeconds,
            distanceM = track.distanceM,
            topSpeedMps = track.topSpeedMps,
            hasFit = fits.isNotEmpty(),
            cameraClipSeconds = clips,
            sizeKb = files.sumOf { it.length() } / 1024,
            route = track.route,
            files = files,
        )
    }

    /**
     * What the list shows about the GPS track. Reading a whole GPS log for every ride on every refresh is slow with many rides, so a
     * finished ride keeps its route and figures in `route.txt` (a first line `gap=<s>;dist=<m>;top=<m/s>`, then one `lat,lon` per
     * line) and its fix count in `meta.json`; a ride that was cut off is always read from its log. A cache written before the
     * distance was kept is read again from the log while that still exists, otherwise its missing figures stay unknown.
     */
    private fun trackOf(dir: File, finished: Boolean, metaFixes: Int?): Track {
        val cache = File(dir, "route.txt")
        val gnss = File(dir, "gnss.csv").takeIf { it.exists() && it.length() <= MAX_TEXT_BYTES }
        if (finished && metaFixes != null && cache.exists() && cache.length() <= MAX_TEXT_BYTES) {
            val lines = cache.readLines()
            val header = lines.firstOrNull()?.takeIf { it.startsWith("gap=") }?.split(';')
                ?.mapNotNull { part -> part.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()
            val route = lines.mapNotNull { line ->
                val p = line.split(',')
                val lat = p.getOrNull(0)?.toDoubleOrNull()
                val lon = p.getOrNull(1)?.toDoubleOrNull()
                if (p.size == 2 && lat != null && lon != null) lat to lon else null
            }
            val dist = header["dist"]?.toDoubleOrNull()
            if (dist != null || gnss == null) return Track(metaFixes, header["gap"]?.toLongOrNull(), dist, header["top"]?.toFloatOrNull(), route)
        }
        gnss ?: return Track(0, null, null, null, emptyList())
        val fixes = RideRecovery.parseGnssCsv(gnss.readText())   // size checked above
        val step = maxOf(1, fixes.size / MAX_ROUTE_POINTS)
        val route = fixes.filterIndexed { i, _ -> i % step == 0 || i == fixes.lastIndex }.map { it.latitudeDeg to it.longitudeDeg }
        val gap = longestGapSeconds(fixes)
        // The same rule as the .fit (RideAssembler): steps across a gap or at a standstill are not counted. A route.txt cached by an
        // older build keeps its old, slightly larger figure until the ride is read from its log again.
        val dist = RideAssembler.distanceM(fixes)
        val top = fixes.mapNotNull { it.speedMps }.maxOrNull()
        if (finished) runCatching {
            cache.writeText("gap=$gap;dist=$dist" + (top?.let { ";top=$it" } ?: "") + "\n" + route.joinToString("\n") { "${it.first},${it.second}" })
        }
        return Track(fixes.size, gap, dist, top, route)
    }

    private class Track(val fixes: Int, val longestGapSeconds: Long?, val distanceM: Double?, val topSpeedMps: Float?, val route: List<Pair<Double, Double>>)

    /** The longest wait between two neighbouring fixes, in seconds; 0 for fewer than two fixes. */
    fun longestGapSeconds(fixes: List<GnssFix>): Long =
        fixes.zipWithNext { a, b -> b.utcMillis - a.utcMillis }.maxOrNull()?.coerceAtLeast(0)?.div(1000) ?: 0

    /** Rebuilds every .fit from the logged fixes (for a ride that was interrupted, or after the writer improved). */
    fun rebuildFit(dir: File): Boolean {
        val gnss = File(dir, "gnss.csv").takeIf { it.exists() && it.length() <= MAX_TEXT_BYTES } ?: return false
        val events = readTextCapped(File(dir, "events.jsonl"))
        val fits = RideRecovery.rebuildFits(gnss.readText(), events) ?: return false
        val keep = fits.map { it.fileName }.toSet()
        fits.forEach { writeAtomically(File(dir, it.fileName), it.bytes) }
        fitFiles(dir).filter { it.name !in keep }.forEach { it.delete() }   // old files go only after the new ones are in place
        return true
    }

    /** Writes through a temporary file and renames it, so a kill during the write leaves the old file or the new one, never half of one. */
    fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("could not replace ${file.name}") }
        }
    }

    fun delete(dir: File): Boolean = dir.deleteRecursively()

    /** Writes the ride's track to [out] as GPX so a map app can show it. False when the ride has no GPS fixes. */
    fun writeGpx(dir: File, out: File): Boolean {
        val gnss = File(dir, "gnss.csv").takeIf { it.exists() && it.length() <= MAX_TEXT_BYTES } ?: return false
        val fixes = RideRecovery.parseGnssCsv(gnss.readText()).takeIf { it.isNotEmpty() } ?: return false
        out.writeText(Gpx.write(dir.name, fixes))
        return true
    }

    /** Packs every ride's files into one zip (`<ride folder>/<file>`) so uninstalling the app does not lose the rides. Returns the ride count. */
    fun zipAll(root: File, out: File): Int {
        val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("ride-") }?.sortedBy { it.name } ?: emptyList()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (dir in dirs) for (f in dir.listFiles { f -> f.isFile && f.name != "route.txt" }?.sortedBy { it.name }.orEmpty()) {   // route.txt is only a cache
                zip.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return dirs.size
    }
}
