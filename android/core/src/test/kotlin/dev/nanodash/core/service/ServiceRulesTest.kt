package dev.nanodash.core.service

import dev.nanodash.core.camera.StartResult
import dev.nanodash.core.camera.Stamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceRulesTest {
    private val idle = ServiceState(armed = false, riding = false, saving = false)
    private val armed = idle.copy(armed = true)
    private val riding = idle.copy(riding = true)
    private val saving = idle.copy(saving = true)
    private val t = Stamp(1, 1)

    private fun watch(s: ServiceState, stopPending: Boolean = false, permission: Boolean = true, gps: Boolean = true) =
        ServiceRules.watchStart(s, stopPending, permission, gps)

    @Test
    fun disarmingWhileSavingKeepsTheServiceUpUntilTheRideIsWritten() {
        assertFalse(ServiceRules.switchOffOnDisarm(saving))
        assertFalse(ServiceRules.switchOffOnDisarm(riding))
        assertTrue(ServiceRules.switchOffOnDisarm(armed))
        assertTrue(ServiceRules.switchOffOnDisarm(idle))
        assertFalse(ServiceRules.showReadyOnArm(saving), "arming during saving must not replace the saving notification")
        assertTrue(ServiceRules.showReadyOnArm(idle))
    }

    @Test
    fun aStartRightAfterSavingEndsIsAllowedButNotBefore() {
        assertFalse(ServiceRules.canStartRide(saving))
        assertFalse(ServiceRules.canStartRide(riding))
        assertTrue(ServiceRules.canStartRide(armed))                            // saving just ended, still armed
        assertTrue(ServiceRules.canStartRide(idle))
        assertEquals(WatchStartDecision.Refuse("busy"), watch(saving))
        assertEquals(WatchStartDecision.Go, watch(armed))
    }

    @Test
    fun aStopWhileArmedWithNoRideLeavesTheServiceRunning() {
        assertFalse(ServiceRules.switchOffOnStopWithoutRide(armed))
        assertTrue(ServiceRules.switchOffOnStopWithoutRide(idle))
        assertFalse(ServiceRules.switchOffOnStopWithoutRide(saving), "a Stop during saving must not switch the service off under the writer")
        assertFalse(ServiceRules.switchOffOnStopWithoutRide(saving.copy(armed = true)))
    }

    @Test
    fun aStrayIntentOnlyEndsAServiceThatHasNoReasonToRun() {
        assertTrue(ServiceRules.switchOffOnStrayIntent(idle))
        assertFalse(ServiceRules.switchOffOnStrayIntent(armed))
        assertFalse(ServiceRules.switchOffOnStrayIntent(riding))
        assertFalse(ServiceRules.switchOffOnStrayIntent(saving))
    }

    @Test
    fun theWatchStartIsRefusedWhenBusyWithoutPermissionOrWithGpsOff() {
        assertEquals(WatchStartDecision.Refuse("busy"), watch(saving, permission = false, gps = false))
        assertEquals(WatchStartDecision.Refuse("no-permission"), watch(armed, permission = false, gps = false))
        assertEquals(WatchStartDecision.Refuse("gps-off"), watch(armed, gps = false))
        assertEquals(WatchStartDecision.Go, watch(idle))
    }

    @Test
    fun aWatchStartWhileRidingIsNotAnError() {
        assertEquals(WatchStartDecision.AlreadyRiding, watch(riding))
        assertEquals(WatchStartDecision.AlreadyRiding, watch(riding, permission = false))
    }

    @Test
    fun aWatchStartThatRacesAnAcceptedStopIsRefusedHonestly() {
        assertEquals(WatchStartDecision.Refuse("busy"), watch(riding, stopPending = true))
        assertEquals(WatchStartDecision.Refuse("busy"), watch(armed, stopPending = true))
    }

    @Test
    fun aCameraWhoseStartReplyWasLostIsStoppedIfItSaysItIsRecording() {
        val started = StartResult.Started(t, t, t)
        val results = mapOf(
            "A" to started, "B" to StartResult.NoReply, "C" to StartResult.NoReply, "D" to StartResult.NoReply,
            "E" to StartResult.Rejected(0xDF, t), "F" to StartResult.NotReady,
        )
        val flags = mapOf("A" to true, "B" to true, "C" to false, "D" to null, "E" to true, "F" to true)
        assertEquals(setOf("A", "B"), ServiceRules.camerasToStop(results, flags))
        assertEquals(setOf("A"), ServiceRules.camerasToStop(results, emptyMap()))
    }

    @Test
    fun theExtraStopIsReportedToTheRider() {
        assertNull(ServiceRules.strayStopNote(emptyList()))
        assertTrue(ServiceRules.strayStopNote(listOf("Osmo Nano A"))!!.startsWith("Osmo Nano A did not answer"))
        assertTrue(ServiceRules.strayStopNote(listOf("Osmo Nano A", "Osmo Nano B"))!!.contains("Osmo Nano A and Osmo Nano B"))
    }
}
