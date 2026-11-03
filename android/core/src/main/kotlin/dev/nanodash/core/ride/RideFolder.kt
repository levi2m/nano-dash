package dev.nanodash.core.ride

import java.io.File

/** What the ride list shows about one ride folder, read from the files the logger wrote. */
class RideSummary(
    val dir: File,
    val startUtcMillis: Long,
    val durationSeconds: Long?,
    val fixes: Int,
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
        val meta = File(dir, "meta.json").takeIf { it.exists() }?.readText().orEmpty()
        val start = START.find(meta)?.groupValues?.get(1)?.toLong() ?: 0L
        val end = END.find(meta)?.groupValues?.get(1)?.toLong()          // null while recording and for a ride that was cut off
        val (fixCount, route) = trackOf(dir, finished = end != null, metaFixes = FIXES.find(meta)?.groupValues?.get(1)?.toInt())

        val clips = RideRecovery.clipSpansFromEvents(File(dir, "events.jsonl").takeIf { it.exists() }?.readText())
            .map { (it.endUtcMillis - it.startUtcMillis) / 1000.0 }
        val fits = fitFiles(dir)
        val files = fits + SHARED.map { File(dir, it) }.filter { it.exists() }
        return RideSummary(
            dir = dir,
            startUtcMillis = if (start > 0) start else dir.lastModified(),
            durationSeconds = if (start > 0 && end != null && end > start) (end - start) / 1000 else null,
            fixes = fixCount,
            hasFit = fits.isNotEmpty(),
            cameraClipSeconds = clips,
            sizeKb = files.sumOf { it.length() } / 1024,
            route = route,
            files = files,
        )
    }

    /**
     * The fix count and the thinned route. Reading a whole GPS log for every ride on every refresh is slow with many rides, so a
     * finished ride keeps its route in `route.txt` and its count in `meta.json`; a ride that was cut off is always read from its log.
     */
    private fun trackOf(dir: File, finished: Boolean, metaFixes: Int?): Pair<Int, List<Pair<Double, Double>>> {
        val cache = File(dir, "route.txt")
        if (finished && metaFixes != null && cache.exists()) {
            val route = cache.readLines().mapNotNull { line ->
                val p = line.split(',')
                val lat = p.getOrNull(0)?.toDoubleOrNull()
                val lon = p.getOrNull(1)?.toDoubleOrNull()
                if (p.size == 2 && lat != null && lon != null) lat to lon else null
            }
            return metaFixes to route
        }
        val gnss = File(dir, "gnss.csv").takeIf { it.exists() } ?: return 0 to emptyList()
        val fixes = RideRecovery.parseGnssCsv(gnss.readText())
        val step = maxOf(1, fixes.size / MAX_ROUTE_POINTS)
        val route = fixes.filterIndexed { i, _ -> i % step == 0 || i == fixes.lastIndex }.map { it.latitudeDeg to it.longitudeDeg }
        if (finished) runCatching { cache.writeText(route.joinToString("\n") { "${it.first},${it.second}" }) }
        return fixes.size to route
    }

    /** Rebuilds every .fit from the logged fixes (for a ride that was interrupted, or after the writer improved). */
    fun rebuildFit(dir: File): Boolean {
        val gnss = File(dir, "gnss.csv").takeIf { it.exists() } ?: return false
        val events = File(dir, "events.jsonl").takeIf { it.exists() }?.readText()
        val fits = RideRecovery.rebuildFits(gnss.readText(), events) ?: return false
        fitFiles(dir).forEach { it.delete() }
        fits.forEach { File(dir, it.fileName).writeBytes(it.bytes) }
        return true
    }

    fun delete(dir: File): Boolean = dir.deleteRecursively()
}
