package dev.nanodash.core.camera

import dev.nanodash.core.fit.CameraSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CameraTimingTest {
    private fun stamp(utc: Long) = Stamp(utc, 0)

    @Test
    fun theClipRunsFromTheFirstFrameToJustBeforeTheStopCommand() {
        val start = StartResult.Started(stamp(1_000), stamp(1_100), recordingFlagAt = stamp(2_000))
        val stop = StopResult.Stopped(commandSent = stamp(12_000), replyAt = stamp(12_100), flagClearedAt = stamp(12_500))
        assertEquals(CameraSpan(2_255, 11_349), CameraTiming.clipSpan(start, stop))
    }

    @Test
    fun noClipWindowWithoutARecordingFlagOrWhenTheClipWouldBeEmpty() {
        val stop = StopResult.Stopped(stamp(12_000), stamp(12_100), null)
        assertNull(CameraTiming.clipSpan(StartResult.Started(stamp(1_000), stamp(1_100), recordingFlagAt = null), stop))
        assertNull(CameraTiming.clipSpan(StartResult.Started(stamp(1_000), stamp(1_100), recordingFlagAt = stamp(11_500)), stop))
    }
}
