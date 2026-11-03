package dev.nanodash.core.camera

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CameraLinkTest {
    private val baseUtc = 1_793_676_000_000L
    private val id = "00112233445566778899aabbccddeeff"

    private fun TestScope.clock() = object : Clock {
        override fun utcMillis() = baseUtc + currentTime
        override fun monotonicNanos() = currentTime * 1_000_000
    }

    /** One camera that stays recording across link drops. [reachable] says whether a new connection attempt finds it. */
    private class Camera(val nano: FakeNano) {
        var reachable = true
        var opens = 0
        suspend fun open(): CameraTransport {
            opens++
            if (!reachable) throw IOException("camera not found")
            nano.reconnect()
            return nano
        }
    }

    private fun TestScope.link(camera: Camera) = CameraLink(backgroundScope, clock(), id) { camera.open() }
    private fun TestScope.camera() = Camera(FakeNano(backgroundScope) { currentTime })

    @Test
    fun aDroppedLinkComesBackOnItsOwn() = runTest {
        val cam = camera()
        val link = link(cam)
        assertTrue(link.connect())
        assertIs<StartResult.Started>(link.startRecording())

        cam.reachable = false
        cam.nano.linkUp = false
        delay(3_000)
        assertEquals(CameraState.Reconnecting, link.state.value)
        assertFalse(link.isReady())

        cam.reachable = true
        delay(12_000)
        assertEquals(CameraState.Ready, link.state.value)
        assertEquals(true, link.recording.value)             // the camera kept recording while the phone was away
        link.close()
    }

    @Test
    fun aReconnectDuringARideDoesNotSetTheClockAgainButRemembersItWasSet() = runTest {
        val cam = camera()
        val link = link(cam)
        assertTrue(link.connect())
        assertTrue(link.clockSet())
        assertEquals(1, cam.nano.clockWrites.size)
        assertIs<StartResult.Started>(link.startRecording())

        cam.nano.linkUp = false
        delay(12_000)                                          // dropped, then back, while the camera kept recording
        assertEquals(CameraState.Ready, link.state.value)
        assertEquals(1, cam.nano.clockWrites.size, "the clock must not be set under a running clip")
        assertTrue(link.clockSet(), "the ride still counts as having a set clock")
        link.close()
    }

    @Test
    fun stopWaitsForTheLinkAndStillStopsTheCamera() = runTest {
        val cam = camera()
        val link = link(cam)
        assertTrue(link.connect())
        assertIs<StartResult.Started>(link.startRecording())
        delay(5_000)

        cam.reachable = false
        cam.nano.linkUp = false
        val stop = async { link.stopRecording() }
        delay(4_000)                                          // the rider presses Stop while the camera is out of reach
        cam.reachable = true
        assertIs<StopResult.Stopped>(stop.await())
        assertEquals(false, link.recording.value)
        link.close()
    }

    @Test
    fun aStopRightAfterTheLinkBreaksDoesNotThrowAndRetries() = runTest {
        val cam = camera()
        val link = link(cam)
        assertTrue(link.connect())
        assertIs<StartResult.Started>(link.startRecording())

        cam.nano.linkUp = false                                // the link is dead but nobody has noticed yet
        val stop = async { link.stopRecording() }
        delay(2_000)
        assertIs<StopResult.Stopped>(stop.await())
        link.close()
    }

    @Test
    fun aCameraThatNeverComesBackGivesUpAfterABoundedWait() = runTest {
        val cam = camera()
        val link = link(cam)
        assertTrue(link.connect())
        assertIs<StartResult.Started>(link.startRecording())

        cam.reachable = false
        cam.nano.linkUp = false
        delay(3_000)
        val before = currentTime
        val stop = link.stopRecording()
        assertEquals(StopResult.NotReady, stop)
        assertTrue(currentTime - before <= 2 * CameraLink.RECONNECT_WAIT_MS + 1_000, "waited ${currentTime - before} ms")
        link.close()
    }

    @Test
    fun retriesBackOffInsteadOfHammeringTheCamera() = runTest {
        val cam = camera()
        val link = link(cam)
        assertTrue(link.connect())
        cam.reachable = false
        cam.nano.linkUp = false
        delay(60_000)
        // waits of 1, 2, 4, 8 then 10 s: about 6 tries in a minute, not 60
        assertTrue(cam.opens in 5..9, "opens: ${cam.opens}")
        link.close()
    }

    @Test
    fun closeStopsTheRetries() = runTest {
        val cam = camera()
        val link = link(cam)
        assertTrue(link.connect())
        cam.reachable = false
        cam.nano.linkUp = false
        delay(5_000)
        link.close()
        val opens = cam.opens
        delay(60_000)
        assertEquals(opens, cam.opens)
        assertEquals(CameraState.Disconnected, link.state.value)
    }

    @Test
    fun aFirstConnectionThatFailsReportsWhyAndRetriesNothing() = runTest {
        val cam = camera().apply { reachable = false }
        val link = link(cam)
        assertFalse(link.connect())
        val st = link.state.value
        assertIs<CameraState.Failed>(st)
        assertEquals("camera not found", st.reason)
        delay(30_000)
        assertEquals(1, cam.opens)
    }
}
