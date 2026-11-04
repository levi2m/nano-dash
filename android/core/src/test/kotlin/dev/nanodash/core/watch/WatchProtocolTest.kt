package dev.nanodash.core.watch

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchProtocolTest {
    @Test
    fun knownCommandsParseAndEverythingElseIsIgnored() {
        assertEquals(WatchCommand.Start, WatchProtocol.parseCommand("START"))
        assertEquals(WatchCommand.Stop, WatchProtocol.parseCommand("STOP\r"))
        assertEquals(WatchCommand.Status, WatchProtocol.parseCommand("STATUS"))
        assertNull(WatchProtocol.parseCommand("start"))
        assertNull(WatchProtocol.parseCommand("START now"))
        assertNull(WatchProtocol.parseCommand(""))
    }

    @Test
    fun helloMustNameThisProtocolVersion() {
        assertTrue(WatchProtocol.isHello("HELLO 1"))
        assertFalse(WatchProtocol.isHello("HELLO 2"))
        assertFalse(WatchProtocol.isHello("HELLO"))
    }

    @Test
    fun aStateSurvivesEncodingAndDecoding() {
        val s = WatchState(RideState.Recording, 754, GpsState.Fix, CameraState.Ready)
        assertEquals("STATE recording 754 fix ready", WatchProtocol.encode(s))
        assertEquals(s, WatchProtocol.decode(WatchProtocol.encode(s)))
        for (ride in RideState.entries) for (gps in GpsState.entries) for (cam in CameraState.entries) {
            val x = WatchState(ride, 0, gps, cam)
            assertEquals(x, WatchProtocol.decode(WatchProtocol.encode(x)))
        }
    }

    @Test
    fun aMalformedStateLineIsRejected() {
        assertNull(WatchProtocol.decode("STATE recording 5 fix"))                 // too short
        assertNull(WatchProtocol.decode("STATE flying 5 fix ready"))              // unknown ride state
        assertNull(WatchProtocol.decode("STATE idle -3 off none"))                // negative seconds
        assertNull(WatchProtocol.decode("STATE idle x off none"))
        assertNull(WatchProtocol.decode("OK"))
    }

    @Test
    fun anErrorReasonCannotCarryControlCharactersOrRunLong() {
        assertEquals("ERR not-ready", WatchProtocol.err("not-ready"))
        assertEquals("ERR ab", WatchProtocol.err("a\n b\r"))                       // a newline cannot start a second message
        assertEquals(4 + 40, WatchProtocol.err("x".repeat(200)).length)
    }

    @Test
    fun theLineReaderSplitsLinesAndDropsCarriageReturns() {
        val r = BoundedLineReader("HELLO 1\r\nSTART\nSTATUS\n".byteInputStream())
        assertEquals("HELLO 1", r.readLine())
        assertEquals("START", r.readLine())
        assertEquals("STATUS", r.readLine())
        assertNull(r.readLine())
    }

    @Test
    fun aHalfLineAtTheEndOfTheStreamIsNotAMessage() {
        assertNull(BoundedLineReader("STAR".byteInputStream()).readLine())
    }

    @Test
    fun aTooLongLineCutsTheConnectionOff() {
        val ok = "x".repeat(WatchProtocol.MAX_LINE_BYTES) + "\n"
        assertEquals(WatchProtocol.MAX_LINE_BYTES, BoundedLineReader(ok.byteInputStream()).readLine()?.length)
        val tooLong = "x".repeat(WatchProtocol.MAX_LINE_BYTES + 1) + "\n"
        assertFailsWith<IOException> { BoundedLineReader(tooLong.byteInputStream()).readLine() }
    }
}
