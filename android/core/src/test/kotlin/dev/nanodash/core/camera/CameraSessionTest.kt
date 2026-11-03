package dev.nanodash.core.camera

import dev.nanodash.core.duml.DumlFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CameraSessionTest {
    private val baseUtc = 1_793_676_000_000L
    private val id = "00112233445566778899aabbccddeeff"

    private fun TestScope.clock() = object : Clock {
        override fun utcMillis() = baseUtc + currentTime
        override fun monotonicNanos() = currentTime * 1_000_000
    }

    private fun TestScope.fake(configure: FakeNano.() -> Unit = {}) = FakeNano(backgroundScope) { currentTime }.apply(configure)
    private fun TestScope.session(nano: FakeNano) = CameraSession(nano, clock(), id, backgroundScope)

    private fun FakeNano.keepalives() = sent.count { it.cmdSet == 0x00 && it.cmdId == 0x2B && it.payload.contentEquals(byteArrayOf(1, 1)) }

    @Test
    fun alreadyPairedPhoneConnectsAndKeepsTheLinkAlive() = runTest {
        val nano = fake()
        val s = session(nano)
        assertTrue(s.connect())
        assertEquals(CameraState.Ready, s.state.value)
        delay(30_000)                                    // far beyond the 6 s idle limit: only keepalives keep the link up
        assertTrue(nano.linkUp)
        assertEquals(CameraState.Ready, s.state.value)
        assertTrue(nano.keepalives() in 29..33, "keepalives: ${nano.keepalives()}")
        s.close()
    }

    @Test
    fun connectSetsTheCamerasClockJustAfterASecondBoundary() = runTest {
        val nano = fake()
        val zone = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
        val s = CameraSession(nano, clock(), id, backgroundScope, zone)
        assertTrue(s.connect())
        assertTrue(s.clockSet)
        val (arrivedAt, unix) = nano.clockWrites.single()
        val boundaryMs = unix * 1000
        // the write arrives CameraTiming.SET_CLOCK_LEAD_MS before the boundary of the phone's clock, carrying that boundary's second
        assertEquals(baseUtc + arrivedAt + CameraTiming.SET_CLOCK_LEAD_MS, boundaryMs)
        val p = nano.clockPayload!!
        assertEquals(0x01, p[0].toInt()); assertEquals(0x00, p[1].toInt())
        assertEquals(-180, (p[10].toInt() and 0xFF) or (p[11].toInt() shl 8))
        assertEquals("America/Sao_Paulo", String(p, 13, p[12].toInt(), Charsets.US_ASCII))
        s.close()
    }

    @Test
    fun aCameraThatIgnoresTheClockCommandStillConnectsButIsMarked() = runTest {
        val nano = fake { acceptsClock = false }
        val s = session(nano)
        assertTrue(s.connect())
        assertFalse(s.clockSet)
        assertEquals(CameraState.Ready, s.state.value)
        s.close()
    }

    @Test
    fun newPhoneIsAskedToApproveThenConnects() = runTest {
        val nano = fake { alreadyPaired = false; approvalDelayMs = 2_000 }
        val s = session(nano)
        val states = mutableListOf<CameraState>()
        backgroundScope.launch { s.state.collect { states += it } }
        assertTrue(s.connect())
        assertTrue(CameraState.AwaitingApproval in states, "states: $states")
        val ack = nano.sent.single { it.cmdSet == 0x07 && it.cmdId == 0x46 }
        assertEquals(0x80, ack.cmdType)
        assertEquals(CameraState.Ready, s.state.value)
        s.close()
    }

    @Test
    fun nobodyApprovesSoConnectFails() = runTest {
        val nano = fake { alreadyPaired = false; approvalDelayMs = null }
        val s = session(nano)
        assertFalse(s.connect())
        val st = s.state.value
        assertIs<CameraState.Failed>(st)
        assertTrue("not approved" in st.reason, st.reason)
        assertTrue(currentTime in 40_000..45_000, "took $currentTime ms")
    }

    @Test
    fun startAndStopMatchTheTimingSeenOnTheRealCamera() = runTest {
        val nano = fake()
        val s = session(nano)
        assertTrue(s.connect())

        val start = s.startRecording()
        assertIs<StartResult.Started>(start)
        assertEquals(550L, start.replyAt.utcMillis - start.commandSent.utcMillis)
        val flagDelay = start.recordingFlagAt!!.utcMillis - start.commandSent.utcMillis
        assertTrue(flagDelay in 1_300..1_550, "flag after $flagDelay ms")      // real camera: 1274 to 1500 ms
        assertEquals(true, s.recording.value)

        delay(6_000)
        val stop = s.stopRecording()
        assertIs<StopResult.Stopped>(stop)
        assertEquals(47L, stop.replyAt.utcMillis - stop.commandSent.utcMillis)
        val clearDelay = stop.flagClearedAt!!.utcMillis - stop.commandSent.utcMillis
        assertTrue(clearDelay in 2_050..2_250, "flag cleared after $clearDelay ms")  // real camera: about 2.09 s
        assertEquals(false, s.recording.value)

        val span = CameraTiming.clipSpan(start, stop)
        assertNotNull(span)
        assertEquals(start.recordingFlagAt!!.utcMillis + CameraTiming.FIRST_FRAME_AFTER_FLAG_MS, span.startUtcMillis)
        assertEquals(stop.commandSent.utcMillis - CameraTiming.CLIP_END_BEFORE_STOP_SENT_MS, span.endUtcMillis)
        s.close()
    }

    @Test
    fun cameraOnUsbRefusesAndTheSessionSaysSo() = runTest {
        val nano = fake { usbAttached = true }
        val s = session(nano)
        assertTrue(s.connect())
        val r = s.startRecording()
        assertIs<StartResult.Rejected>(r)
        assertEquals(0xD6, r.code)
        assertEquals(false, s.recording.value)
        s.close()
    }

    @Test
    fun startingWhileAlreadyRecordingIsRejected() = runTest {
        val nano = fake()
        val s = session(nano)
        assertTrue(s.connect())
        assertIs<StartResult.Started>(s.startRecording())
        val second = s.startRecording()
        assertIs<StartResult.Rejected>(second)
        assertEquals(0xDF, second.code)
        s.close()
    }

    @Test
    fun workWhenNotificationsArriveInTinyPieces() = runTest {
        val nano = fake { chunkSize = 7 }
        val s = session(nano)
        assertTrue(s.connect())
        assertIs<StartResult.Started>(s.startRecording())
        s.close()
    }

    @Test
    fun aDeadLinkIsReportedAsDisconnected() = runTest {
        val nano = fake()
        val s = session(nano)
        assertTrue(s.connect())
        nano.linkUp = false
        delay(2_500)
        assertEquals(CameraState.Disconnected, s.state.value)
        assertEquals(StartResult.NotReady, s.startRecording())
        s.close()
    }

    @Test
    fun aWriteOnADeadLinkIsNoReplyNotACrash() = runTest {
        val nano = fake()
        val s = session(nano)
        assertTrue(s.connect())
        assertIs<StartResult.Started>(s.startRecording())
        nano.linkUp = false                              // dropped, and the keepalive has not noticed yet
        assertEquals(StopResult.NoReply, s.stopRecording())
        assertEquals(CameraState.Disconnected, s.state.value)
        s.close()
    }

    @Test
    fun noRecordingBeforeTheHandshakeIsDone() = runTest {
        val s = session(fake())
        assertEquals(StartResult.NotReady, s.startRecording())
        assertEquals(StopResult.NotReady, s.stopRecording())
        assertNull(s.recording.value)
    }

    @Test
    fun clipSpanIsNullWhenTheCameraNeverReportedRecording() {
        val t = Stamp(1_000, 0)
        val start = StartResult.Started(t, t, null)
        assertNull(CameraTiming.firstFrameUtcMillis(start))
        assertNull(CameraTiming.clipSpan(start, StopResult.Stopped(Stamp(9_000, 0), t, null)))
    }
}
