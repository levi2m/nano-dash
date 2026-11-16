package dev.nanodash.core.ride

import dev.nanodash.core.fit.RideSample
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthDataTest {
    private val root: File = Files.createTempDirectory("health").toFile()
    private val t0 = 1_793_676_577_000L

    @AfterTest fun clean() { root.deleteRecursively() }

    private fun sample(i: Int) = RideSample(t0 + i * 1000L, 0.0, 0.0, 0f, 0f, 0f)

    @Test
    fun csvRoundTripsAndSortsByTime() {
        val text = HealthCsv.write(listOf(HeartRateReading(t0 + 2000, 90), HeartRateReading(t0, 80)))
        assertEquals("utc_ms,heart_rate_bpm\n$t0,80\n${t0 + 2000},90\n", text)
        assertEquals(listOf(HeartRateReading(t0, 80), HeartRateReading(t0 + 2000, 90)), HealthCsv.parse(text))
    }

    @Test
    fun parseSkipsBadLinesAndImpossibleRatesAndAcceptsNull() {
        val text = "utc_ms,heart_rate_bpm\n$t0,80\nnot,a,line\n$t0,0\n$t0,251\n$t0,29\n${t0 + 1000},\n${t0 + 2000},250\n\n"
        assertEquals(listOf(HeartRateReading(t0, 80), HeartRateReading(t0 + 2000, 250)), HealthCsv.parse(text))
        assertEquals(emptyList(), HealthCsv.parse(null))
        assertEquals(emptyList(), HealthCsv.parse(""))
    }

    @Test
    fun attachUsesTheNearestReadingWithinFiveSecondsOnly() {
        val samples = (0..20).map(::sample)
        val out = HealthMerge.attach(samples, listOf(HeartRateReading(t0 + 2000, 100), HeartRateReading(t0 + 12_000, 140)))
        assertEquals(100, out[0].heartRateBpm)      // 2 s away
        assertEquals(100, out[7].heartRateBpm)      // 5 s from the first reading, and the nearer one
        assertEquals(140, out[8].heartRateBpm)      // 4 s from the second
        assertEquals(140, out[17].heartRateBpm)     // 5 s after the second
        assertNull(out[18].heartRateBpm)            // 6 s: too far
    }

    @Test
    fun aGapLongerThanTheWindowStaysAGap() {
        val samples = (0..30).map(::sample)
        val out = HealthMerge.attach(samples, listOf(HeartRateReading(t0, 100), HeartRateReading(t0 + 20_000, 140)))
        assertEquals(100, out[5].heartRateBpm)
        assertNull(out[6].heartRateBpm)
        assertNull(out[14].heartRateBpm)
        assertEquals(140, out[15].heartRateBpm)
        assertEquals(140, out[25].heartRateBpm)
        assertNull(out[26].heartRateBpm)
    }

    @Test
    fun aTieGoesToTheEarlierReading() {
        val out = HealthMerge.attach(listOf(sample(1)), listOf(HeartRateReading(t0, 100), HeartRateReading(t0 + 2000, 110)))
        assertEquals(100, out.single().heartRateBpm)
    }

    @Test
    fun noReadingsLeavesTheSamplesAsTheyWere() {
        val samples = (0..3).map(::sample)
        assertEquals(samples, HealthMerge.attach(samples, emptyList()))
    }

    @Test
    fun readingShiftMovesTheReadingsLikeTheGpsRecords() {
        val out = HealthMerge.attach(listOf(sample(100)), listOf(HeartRateReading(t0, 90)), readingShiftMs = 100_000)
        assertEquals(90, out.single().heartRateBpm)
        assertNull(HealthMerge.attach(listOf(sample(100)), listOf(HeartRateReading(t0, 90))).single().heartRateBpm)
    }

    private fun ride(): File {
        val dir = File(root, "ride").apply { mkdirs() }
        val header = "elapsed_ns,utc_ms,lat,lon,alt_m,speed_mps,bearing_deg,h_acc_m,speed_acc_mps,bearing_acc_deg,v_acc_m\n"
        File(dir, "gnss.csv").writeText(header + (0 until 30).joinToString("\n") { "${it * 1_000_000_000L},${t0 + it * 1000L},-20.2,-40.2,5.0,8.0,0.0,3.0,0.5,2.0,4.0" } + "\n")
        File(dir, "meta.json").writeText("""{"start_utc_ms":$t0,"end_utc_ms":${t0 + 30_000},"gnss_fixes":30}""")
        return dir
    }

    @Test
    fun rebuildWithHealthDataAddsHeartRateAndRemovingItRestoresThePlainFile() {
        val dir = ride()
        assertEquals(0, RideFolder.summarize(dir).heartRateReadings)
        assertTrue(RideFolder.rebuildFit(dir))
        val plain = File(dir, "ride.fit").readBytes()

        RideFolder.saveHealth(dir, (0 until 30).map { HeartRateReading(t0 + it * 1000L, 100 + it) })
        assertEquals(30, RideFolder.summarize(dir).heartRateReadings)
        assertTrue(RideFolder.rebuildFit(dir))
        assertTrue(File(dir, "ride.fit").readBytes().size > plain.size, "heart_rate adds bytes to every record")
        assertTrue(RideFolder.summarize(dir).files.any { it.name == HealthCsv.FILE })

        RideFolder.saveHealth(dir, emptyList())
        assertFalse(File(dir, HealthCsv.FILE).exists())
        assertTrue(RideFolder.rebuildFit(dir))
        assertTrue(plain.contentEquals(File(dir, "ride.fit").readBytes()))
    }

    @Test
    fun theListCountsReadingsWithoutParsingTheFile() {
        val dir = ride()
        RideFolder.rebuildFit(dir)
        RideFolder.saveHealth(dir, (0 until 12).map { HeartRateReading(t0 + it * 1000L, 90) })
        val summary = RideFolder.summarize(dir)
        assertEquals(12, summary.heartRateReadings)
        assertEquals(0, HealthCsv.count(File(root, "missing.csv")))
        File(root, "header-only.csv").writeText("utc_ms,heart_rate_bpm\n")
        assertEquals(0, HealthCsv.count(File(root, "header-only.csv")))
    }

    private fun summary(startMs: Long, durationS: Long? = 600, fixes: Int = 100, hasFit: Boolean = true, readings: Int = 0) = RideSummary(
        dir = File(root, "r$startMs"), startUtcMillis = startMs, durationSeconds = durationS, fixes = fixes, longestGapSeconds = null, distanceM = null,
        topSpeedMps = null, hasFit = hasFit, cameraClipSeconds = emptyList(), sizeKb = 0, route = emptyList(), files = emptyList(), heartRateReadings = readings,
    )

    @Test
    fun dueRidesAreRecentWithAFitAndFixesAndNoReadings() {
        val now = t0 + 10L * 86_400_000
        val day = 86_400_000L
        val fresh = summary(now - day)
        val old = summary(now - 5 * day)
        val noFit = summary(now - day + 1, hasFit = false)
        val noFixes = summary(now - day + 2, fixes = 0)
        val done = summary(now - day + 3, readings = 40)
        assertEquals(listOf(fresh), HealthImport.due(listOf(fresh, old, noFit, noFixes, done), now))
    }

    @Test
    fun theThreeDayLimitCountsFromTheRidesEndAndACutOffRideUsesItsStart() {
        val now = t0 + 10L * 86_400_000
        val day = 86_400_000L
        val endedJustInside = summary(now - 3 * day - 300_000, durationS = 600)    // started before the limit but ended 5 min after it
        val endedJustOutside = summary(now - 3 * day - 700_000, durationS = 600)   // ended 100 s before the limit
        val cutOffInside = summary(now - 3 * day + 1, durationS = null)
        val cutOffOutside = summary(now - 3 * day - 1, durationS = null)
        assertEquals(listOf(endedJustInside, cutOffInside), HealthImport.due(listOf(endedJustInside, endedJustOutside, cutOffInside, cutOffOutside), now))
    }

    @Test
    fun noMoreThanFiveRidesAreDueAtOnce() {
        val now = t0 + 86_400_000
        val rides = (0 until 8).map { summary(now - it * 60_000L - 600_000) }
        assertEquals(rides.take(HealthImport.MAX_RIDES), HealthImport.due(rides, now))
    }

    @Test
    fun onlyReadingsInRangeAreWrittenSoTheCountMatchesTheFit() {
        val readings = listOf(HeartRateReading(t0, 0), HeartRateReading(t0 + 1000, 80), HeartRateReading(t0 + 2000, 300))
        assertEquals(listOf(HeartRateReading(t0 + 1000, 80)), HealthCsv.parse(HealthCsv.write(readings)))
        val dir = ride()
        RideFolder.saveHealth(dir, readings.filter { it.bpm !in HealthCsv.MIN_BPM..HealthCsv.MAX_BPM })
        assertFalse(File(dir, HealthCsv.FILE).exists(), "nothing valid, so no file and no false 'done'")
        RideFolder.saveHealth(dir, readings)
        assertEquals(1, RideFolder.summarize(dir).heartRateReadings)
    }

    @Test
    fun readingsMoveWithThePhoneMinusGpsOffsetLikeTheCameraSpans() {
        val fixes = RideRecovery.parseGnssCsv(File(ride(), "gnss.csv").readText())
        val readings = (0 until 30).map { HeartRateReading(t0 + it * 1000L, 100 + it) }
        val plain = RideRecovery.buildFit(fixes, emptyList(), shiftMs = 0, phoneMinusGpsMs = 0, heartRate = readings)!!.bytes
        // The phone clock is 3 s ahead of GPS: a reading stamped 3 s later on the phone is the same moment, so the file must not change.
        val ahead = RideRecovery.buildFit(fixes, emptyList(), shiftMs = 0, phoneMinusGpsMs = 3000, heartRate = readings.map { it.copy(utcMillis = it.utcMillis + 3000) })!!.bytes
        assertTrue(plain.contentEquals(ahead))
        // The same readings with the offset not accounted for would land 3 s off.
        val wrong = RideRecovery.buildFit(fixes, emptyList(), shiftMs = 0, phoneMinusGpsMs = 0, heartRate = readings.map { it.copy(utcMillis = it.utcMillis + 3000) })!!.bytes
        assertFalse(plain.contentEquals(wrong))
    }

    @Test
    fun fixWindowIsFirstAndLastFixWidenedByTheMargin() {
        val dir = ride()
        assertEquals((t0 - 60_000) to (t0 + 29_000 + 60_000), RideFolder.fixWindow(dir, 60_000))
        assertNull(RideFolder.fixWindow(File(root, "none")))
    }
}
