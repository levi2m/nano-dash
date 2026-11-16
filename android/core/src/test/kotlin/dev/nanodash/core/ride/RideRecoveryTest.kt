package dev.nanodash.core.ride

import dev.nanodash.core.camera.CameraTiming
import dev.nanodash.core.fit.CameraSpan
import dev.nanodash.core.fit.FitTime
import dev.nanodash.core.fit.ParsedFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideRecoveryTest {
    private val header = "elapsed_ns,utc_ms,lat,lon,alt_m,speed_mps,bearing_deg,h_acc_m,speed_acc_mps,bearing_acc_deg\n"

    private fun csv(vararg rows: String) = header + rows.joinToString("\n") + "\n"

    private fun rebuildFit(gnssCsv: String, eventsJsonl: String? = null, shiftMs: Long = CameraTiming.FIT_SHIFT_MS): ByteArray? =
        RideRecovery.buildFit(GnssCsv.parse(gnssCsv), RideRecovery.clipSpansFromEvents(eventsJsonl), shiftMs)?.bytes

    @Test
    fun parsesTheLoggersCsvIncludingEmptyFields() {
        val fixes = GnssCsv.parse(
            csv(
                "100,1793676577000,-20.265,-40.295,5.5,12.5,90.0,3.0,0.4,2.0",
                "200,1793676578000,-20.2651,-40.2951,,,,,,",
            ),
        )
        assertEquals(2, fixes.size)
        assertEquals(1793676577000L, fixes[0].utcMillis)
        assertEquals(12.5f, fixes[0].speedMps)
        assertNull(fixes[1].altitudeM)
        assertNull(fixes[1].speedMps)
        assertNull(fixes[1].bearingDeg)
    }

    @Test
    fun skipsBadLinesInsteadOfFailing() {
        val fixes = GnssCsv.parse(csv("garbage", "1,notanumber,1,2,3,4,5,6,7,8", "1,1793676577000,-20.0,-40.0,1,2,3,4,5,6"))
        assertEquals(1, fixes.size)
    }

    @Test
    fun aHeaderOnlyLogGivesNoFit() {
        assertNull(rebuildFit(header))
        assertNull(rebuildFit(""))
    }

    @Test
    fun readsTheCameraClipWindowFromEvents() {
        val events = """
            {"event":"camera_start","command_sent":{"utc_ms":1793677574001,"elapsed_ns":1}}
            {"event":"camera_stop","clip_span_estimate_utc_ms":[1793677575401,1793677590378]}
        """.trimIndent()
        assertEquals(listOf(CameraSpan(1793677575401L, 1793677590378L)), RideRecovery.clipSpansFromEvents(events))
        assertEquals(emptyList(), RideRecovery.clipSpansFromEvents("""{"event":"camera_stop","clip_span_estimate_utc_ms":null}"""))
        assertEquals(emptyList(), RideRecovery.clipSpansFromEvents(null))
    }

    private fun clipLine(camera: String, shift: Long, start: Long) =
        """{"event":"camera_stop","camera":"$camera","fit_shift_ms":$shift,"clip_span_estimate_utc_ms":[$start,${start + 3000}]}"""

    private val fixes5 get() = GnssCsv.parse(fiveSeconds)

    @Test
    fun camerasWithTheSameClockOffsetShareOneFile() {
        val events = clipLine("Osmo Nano AA:1A:2B", -1700, t0) + "\n" + clipLine("Osmo Nano AA:77:D4", -1700, t0 + 500)
        val fits = assertNotNull(RideRecovery.buildFits(fixes5, RideRecovery.clipsFromEvents(events)))
        assertEquals(listOf("ride.fit"), fits.map { it.fileName })
    }

    @Test
    fun camerasWithDifferentClockOffsetsEachGetTheirOwnFileOnTheirOwnClock() {
        val events = clipLine("Osmo Nano AA:1A:2B", -1700, t0) + "\n" + clipLine("Osmo Nano AA:77:D4", -4200, t0)
        val fits = assertNotNull(RideRecovery.buildFits(fixes5, RideRecovery.clipsFromEvents(events)))
        assertEquals(listOf("ride-1A2B.fit", "ride-77D4.fit"), fits.map { it.fileName })
        assertEquals(FitTime.fromUtcMillis(t0 - 1700), timeCreated(fits[0].bytes))
        assertEquals(FitTime.fromUtcMillis(t0 - 4200), timeCreated(fits[1].bytes))
    }

    @Test
    fun oldLogsWithoutCameraNamesStillRebuildWithTheDefaultShift() {
        val clips = RideRecovery.clipsFromEvents("""{"event":"camera_stop","clip_span_estimate_utc_ms":[1000,5000]}""")
        assertEquals(listOf(RideRecovery.CameraClip("", CameraSpan(1000, 5000), CameraTiming.LEGACY_FIT_SHIFT_MS)), clips)
    }

    @Test
    fun aPhoneClockThatRunsAheadMovesTheRecordsButNotTheCameraWindowOnTheCamerasClock() {
        val fixes = fixes5
        val phoneAhead = 800L                                  // the phone's clock reads 0.8 s ahead of the GPS
        val lag = 360L
        val span = CameraSpan(t0 + 1_000 + phoneAhead, t0 + 3_000 + phoneAhead)          // logged on the phone's clock
        val withSkew = assertNotNull(RideRecovery.buildFit(fixes, listOf(span), -lag + phoneAhead, phoneAhead))
        val plain = assertNotNull(RideRecovery.buildFit(fixes, listOf(CameraSpan(t0 + 1_000, t0 + 3_000)), -lag, 0))
        // records: GPS time moved onto the camera's clock by (skew - lag); camera window: phone clock minus only the lag
        assertEquals(FitTime.fromUtcMillis(t0 - lag + phoneAhead), timeCreated(withSkew.bytes))
        assertEquals(FitTime.fromUtcMillis(t0 - lag), timeCreated(plain.bytes))
        assertEquals(withSkew.samples, plain.samples)
    }

    @Test
    fun fitSecondsAreRoundedNotCut() {
        assertEquals(FitTime.fromUtcMillis(t0), FitTime.fromUtcMillis(t0 + 499))
        assertEquals(FitTime.fromUtcMillis(t0) + 1, FitTime.fromUtcMillis(t0 + 500))
        assertEquals(FitTime.fromUtcMillis(t0) - 1, FitTime.fromUtcMillis(t0 - 501))     // a 360 ms shift must not become a full second
        assertEquals(FitTime.fromUtcMillis(t0), FitTime.fromUtcMillis(t0 - 360))
    }

    @Test
    fun readsTheSkewFromTheEventsAndGroupsOnTheShift() {
        val line = """{"event":"camera_stop","camera":"Osmo Nano AA:1A:2B","fit_shift_ms":440,"phone_minus_gps_ms":800,"clip_span_estimate_utc_ms":[1000,5000]}"""
        val clip = RideRecovery.clipsFromEvents(line).single()
        assertEquals(440L, clip.shiftMs)
        assertEquals(800L, clip.phoneMinusGpsMs)
    }

    @Test
    fun aCameraStopLineBuiltFromTheLogKeysIsReadBackWithItsCameraShiftAndSkew() {
        val line = """{"${LogKey.EVENT}":"${LogEvent.CAMERA_STOP}","${LogKey.CAMERA}":"Osmo Nano AA:1A:2B","${LogKey.FIT_SHIFT_MS}":-360,""" +
            """"${LogKey.PHONE_MINUS_GPS_MS}":120,"${LogKey.CLIP_SPAN}":[1000,5000]}"""
        assertEquals(
            listOf(RideRecovery.CameraClip("Osmo Nano AA:1A:2B", CameraSpan(1000, 5000), -360, 120)),
            RideRecovery.clipsFromEvents(line),
        )
    }

    @Test
    fun readsOneClipWindowPerCamera() {
        val events = """
            {"event":"camera_stop","camera":"A","clip_span_estimate_utc_ms":[1000,5000]}
            {"event":"camera_stop","camera":"B","clip_span_estimate_utc_ms":[1200,4800]}
        """.trimIndent()
        assertEquals(listOf(CameraSpan(1000, 5000), CameraSpan(1200, 4800)), RideRecovery.clipSpansFromEvents(events))
    }

    private val t0 = 1793676577000L
    private val FILE_ID = 0
    private val TIME_CREATED = 4
    private val fiveSeconds = csv(*(0 until 5).map { "${it},${t0 + it * 1000L},-20.2,-40.2,5.0,8.0,0.0,3.0,0.5,2.0" }.toTypedArray())

    /** The file_id message's time_created (field 4), read back by the independent FIT reader. */
    private fun timeCreated(fit: ByteArray): Long = ParsedFit.u(ParsedFit(fit).of(FILE_ID).single().fields.getValue(TIME_CREATED), 0, 4)

    @Test
    fun fitTimesAreShiftedOntoTheCamerasClockByDefault() {
        val fit = assertNotNull(rebuildFit(fiveSeconds))
        assertEquals(FitTime.fromUtcMillis(t0 - CameraTiming.CLOCK_BEHIND_PHONE_MS), timeCreated(fit))
    }

    @Test
    fun anExplicitShiftReplacesTheDefault() {
        assertEquals(FitTime.fromUtcMillis(t0), timeCreated(assertNotNull(rebuildFit(fiveSeconds, null, 0))))
        assertEquals(FitTime.fromUtcMillis(t0 + 3_600_000), timeCreated(assertNotNull(rebuildFit(fiveSeconds, null, 3_600_000))))
    }

    @Test
    fun theCameraWindowIsShiftedWithTheRecords() {
        val fixes = GnssCsv.parse(fiveSeconds)
        val span = CameraSpan(t0 + 1_000, t0 + 3_000)
        val built = assertNotNull(RideRecovery.buildFit(fixes, listOf(span)))
        assertEquals(5, built.samples)
        // Same shift on both sides keeps the flag on the same records: compare against an unshifted build with the shift applied by hand.
        val byHand = assertNotNull(RideRecovery.buildFit(fixes.map { it.copy(utcMillis = it.utcMillis + CameraTiming.FIT_SHIFT_MS) }, listOf(CameraSpan(span.startUtcMillis + CameraTiming.FIT_SHIFT_MS, span.endUtcMillis + CameraTiming.FIT_SHIFT_MS)), 0))
        assertEquals(byHand.bytes.toList(), built.bytes.toList())
    }
}
