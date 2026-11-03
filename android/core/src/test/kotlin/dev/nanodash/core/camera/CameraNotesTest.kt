package dev.nanodash.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraNotesTest {
    private val t = Stamp(1, 1)
    private val ok = StartResult.Started(t, t, t)
    private fun row(a: String, text: String = "Camera ready", ready: Boolean = true, busy: Boolean = false, error: Boolean = false, rec: Boolean? = null) =
        CameraRow(a, text, ready, busy, error, rec)

    @Test
    fun oneCameraReadsAsItself() {
        val s = CameraNotes.summarize(listOf(row("AA:BB:CC:DD:EE:FF")))
        assertEquals("Camera ready", s.text)
        assertTrue(s.ready)
    }

    @Test
    fun severalCamerasReadAsCountsUntilOneFails() {
        val rows = listOf(row("AA:00:00:00:1A:2B"), row("AA:00:00:00:77:D4", text = "Pairing", ready = false, busy = true))
        assertEquals("1 of 2 cameras ready", CameraNotes.summarize(rows).text)
        assertFalse(CameraNotes.summarize(rows).ready)
        val bad = rows + row("AA:00:00:00:99:E5", text = "Camera lost, reconnecting", ready = false, error = true)
        assertEquals("Osmo Nano 99:E5: Camera lost, reconnecting", CameraNotes.summarize(bad).text)
        assertTrue(CameraNotes.summarize(bad).error)
    }

    @Test
    fun theApprovalPromptWinsAndNamesTheCameraWhenThereAreSeveral() {
        val one = CameraNotes.summarize(listOf(row("AA:00:00:00:1A:2B", text = "Approve this phone on the camera's screen", ready = false, busy = true)))
        assertEquals("Approve this phone on the camera's screen", one.text)
        val two = CameraNotes.summarize(listOf(row("AA:00:00:00:1A:2B"), row("AA:00:00:00:77:D4", text = "Approve this phone on the camera's screen", ready = false, busy = true)))
        assertEquals("Approve this phone on the screen of Osmo Nano 77:D4", two.text)
    }

    @Test
    fun recordingIsTrueIfAnyRecordsAndUnknownWhenMixed() {
        assertEquals(true, CameraNotes.summarize(listOf(row("A", rec = true), row("B", rec = false))).recording)
        assertEquals(false, CameraNotes.summarize(listOf(row("A", rec = false), row("B", rec = false))).recording)
        assertNull(CameraNotes.summarize(listOf(row("A", rec = false), row("B", rec = null))).recording)
        assertNull(CameraNotes.summarize(emptyList()).recording)
    }

    @Test
    fun startNoteCountsRecordingCamerasDirectly() {
        val noFlag = StartResult.Started(t, t, null)
        assertEquals("Camera recording", CameraNotes.startNote(1, mapOf("A" to ok)))
        assertEquals("2 cameras recording", CameraNotes.startNote(2, mapOf("A" to ok, "B" to ok)))
        // a camera that accepted the command but never reported recording is a problem, not a recording camera
        assertEquals("1 of 2 cameras recording. Osmo Nano B accepted the command but did not report recording.",
            CameraNotes.startNote(2, mapOf("A" to ok, "B" to noFlag)))
    }

    @Test
    fun startNoteNamesWhatWentWrongAndWhoWasLeftOut() {
        assertEquals("Camera refused to record (code D6). Is USB connected?", CameraNotes.startNote(1, mapOf("A" to StartResult.Rejected(0xD6, t))))
        assertEquals("1 of 3 cameras recording. Osmo Nano B is not ready. 1 camera was not ready and has no video.",
            CameraNotes.startNote(3, mapOf("A" to ok, "B" to StartResult.NotReady)))
        assertEquals("Camera did not answer the start command.", CameraNotes.startNote(1, mapOf("A" to StartResult.NoReply)))
    }

    @Test
    fun stopNoteIsSilentUnlessSomeCameraDidNotConfirm() {
        assertNull(CameraNotes.stopNote(2, emptyList()))
        assertEquals("The camera did not confirm the stop. Check that it stopped recording.", CameraNotes.stopNote(1, listOf("A")))
        assertEquals("Osmo Nano A and Osmo Nano B did not confirm the stop. Check that they stopped recording.", CameraNotes.stopNote(2, listOf("Osmo Nano A", "Osmo Nano B")))
    }
}
