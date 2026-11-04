package dev.nanodash.core.ride

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideFolderTest {
    private val root: File = Files.createTempDirectory("rides").toFile()
    private val t0 = 1_793_676_577_000L

    @AfterTest fun clean() { root.deleteRecursively() }

    private val header = "elapsed_ns,utc_ms,lat,lon,alt_m,speed_mps,bearing_deg,h_acc_m,speed_acc_mps,bearing_acc_deg,v_acc_m\n"

    private fun ride(name: String, fixes: Int, finished: Boolean, events: String? = null): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "gnss.csv").writeText(header + (0 until fixes).joinToString("\n") { "${it * 1_000_000_000L},${t0 + it * 1000L},-20.2${it % 9},-40.2,5.0,8.0,0.0,3.0,0.5,2.0,4.0" } + "\n")
        File(dir, "meta.json").writeText("""{"start_utc_ms":$t0,"end_utc_ms":${if (finished) (t0 + fixes * 1000L).toString() else "null"},"gnss_fixes":${if (finished) fixes else 0}}""")
        events?.let { File(dir, "events.jsonl").writeText(it) }
        return dir
    }

    @Test
    fun listsNewestFirstAndSummarisesAFinishedRide() {
        ride("ride-20261103T100000Z", 30, finished = true)
        val newer = ride("ride-20261103T120000Z", 12, finished = true,
            events = """{"event":"camera_stop","camera":"A","clip_span_estimate_utc_ms":[${t0 + 1000},${t0 + 9000}]}""")
        val list = RideFolder.list(root)
        assertEquals(listOf("ride-20261103T120000Z", "ride-20261103T100000Z"), list.map { it.dir.name })
        val s = list.first()
        assertEquals(12, s.fixes)
        assertEquals(12L, s.durationSeconds)
        assertEquals(listOf(8.0), s.cameraClipSeconds)
        assertFalse(s.hasFit)
        assertEquals(newer, s.dir)
    }

    @Test
    fun aRideThatWasCutOffIsReadFromItsLogAndNotCached() {
        val dir = ride("ride-20261103T100000Z", 20, finished = false)
        val s = RideFolder.summarize(dir)
        assertEquals(20, s.fixes)
        assertNull(s.durationSeconds)
        assertFalse(File(dir, "route.txt").exists())
    }

    @Test
    fun aFinishedRidesRouteIsCachedAndTheLogIsNotReadAgain() {
        val dir = ride("ride-20261103T100000Z", 500, finished = true)
        val first = RideFolder.summarize(dir)
        assertTrue(File(dir, "route.txt").exists())
        File(dir, "gnss.csv").delete()                       // the second look must not need the big file
        val second = RideFolder.summarize(dir)
        assertEquals(500, second.fixes)
        assertEquals(first.route, second.route)
        assertTrue(second.route.size <= 2 * RideFolder.MAX_ROUTE_POINTS, "route points: ${second.route.size}")   // the step is a whole number, so up to twice the target
    }

    @Test
    fun reportsTheLongestWaitBetweenFixesAndKeepsItInTheCache() {
        val dir = ride("ride-4", 10, finished = true)
        val seconds = (0 until 10).map { if (it < 5) it else it + 89 }          // a 90 s hole after the fifth fix
        File(dir, "gnss.csv").writeText(header + seconds.joinToString("\n") { "${it}000000000,${t0 + it * 1000L},-20.2,-40.2,5.0,8.0,0.0,3.0,0.5,2.0,4.0" } + "\n")
        assertEquals(90L, RideFolder.summarize(dir).longestGapSeconds)
        File(dir, "gnss.csv").delete()
        assertEquals(90L, RideFolder.summarize(dir).longestGapSeconds)           // read back from route.txt
        File(dir, "route.txt").writeText("-20.2,-40.2\n-20.3,-40.2")             // a cache written before the gap was kept
        assertNull(RideFolder.summarize(dir).longestGapSeconds)
        assertEquals(0L, RideFolder.longestGapSeconds(emptyList()))
    }

    @Test
    fun rebuildWritesOneFileOrOnePerClockAndReplacesOldOnes() {
        val one = ride("ride-1", 30, finished = true,
            events = """{"event":"camera_stop","camera":"Osmo Nano AA:1A:2B","fit_shift_ms":-360,"clip_span_estimate_utc_ms":[${t0 + 2000},${t0 + 20000}]}""")
        assertTrue(RideFolder.rebuildFit(one))
        assertEquals(listOf("ride.fit"), RideFolder.fitFiles(one).map { it.name })
        assertTrue(RideFolder.summarize(one).hasFit)

        val two = ride("ride-2", 30, finished = true,
            events = """{"event":"camera_stop","camera":"Osmo Nano AA:1A:2B","fit_shift_ms":-360,"clip_span_estimate_utc_ms":[${t0 + 2000},${t0 + 20000}]}""" + "\n" +
                """{"event":"camera_stop","camera":"Osmo Nano AA:77:D4","fit_shift_ms":-4200,"clip_span_estimate_utc_ms":[${t0 + 2000},${t0 + 20000}]}""")
        File(two, "ride.fit").writeBytes(byteArrayOf(1))               // an old single file must go
        assertTrue(RideFolder.rebuildFit(two))
        assertEquals(listOf("ride-1A2B.fit", "ride-77D4.fit"), RideFolder.fitFiles(two).map { it.name })
        assertEquals(2, assertNotNull(RideFolder.summarize(two)).fits.size)
        assertTrue(RideFolder.summarize(two).files.map { it.name }.containsAll(listOf("ride-1A2B.fit", "ride-77D4.fit", "gnss.csv", "meta.json")))
    }

    @Test
    fun writeGpxNeedsFixesAndNamesTheTrackAfterTheRide() {
        val out = File(root, "route.gpx")
        assertFalse(RideFolder.writeGpx(ride("ride-5", 0, finished = true), out))
        assertFalse(out.exists())
        assertTrue(RideFolder.writeGpx(ride("ride-6", 3, finished = true), out))
        assertEquals(3, Regex("<trkpt ").findAll(out.readText()).count())
        assertTrue(out.readText().contains("<name>ride-6</name>"))
    }

    @Test
    fun zipAllPacksEveryRidesFilesUnderItsFolderName() {
        ride("ride-a", 5, finished = true, events = "{}")
        ride("ride-b", 5, finished = true)
        RideFolder.summarize(File(root, "ride-a"))                     // leaves route.txt, which is only a cache
        val zip = File(root, "all.zip")
        assertEquals(2, RideFolder.zipAll(root, zip))
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("ride-a/events.jsonl", "ride-a/gnss.csv", "ride-a/meta.json", "ride-b/gnss.csv", "ride-b/meta.json"), names.sorted())
    }

    @Test
    fun rebuildNeedsAtLeastOneFixAndDeleteRemovesTheFolder() {
        val empty = ride("ride-3", 0, finished = true)
        assertFalse(RideFolder.rebuildFit(empty))
        assertTrue(RideFolder.delete(empty))
        assertFalse(empty.exists())
    }
}
