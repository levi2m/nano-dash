package dev.nanodash.core.service

import dev.nanodash.core.camera.StartResult
import dev.nanodash.core.watch.WatchStartRules

/** What the ride service is doing right now: waiting for Quick start ([armed]), recording ([riding]) or writing the files ([saving]). */
data class ServiceState(val armed: Boolean, val riding: Boolean, val saving: Boolean) {
    /** Neither recording nor writing: nothing is under way that a switch-off, a new ride or a notification change could disturb. */
    val idle: Boolean get() = !riding && !saving
}

/** The answer to a Start from the watch. */
sealed interface WatchStartDecision {
    /** A ride is already recording: starting again is not an error. */
    data object AlreadyRiding : WatchStartDecision
    data object Go : WatchStartDecision
    /** Answer the watch `ERR <reason>`. */
    data class Refuse(val reason: String) : WatchStartDecision
}

/**
 * The decisions of the ride service that are not about Android: when it may start a ride, switch itself off, or answer the
 * watch. Pure functions of the service's state, so they are tested in `core`; `LoggerService` only carries them out.
 */
object ServiceRules {
    /** A ride may start only when none is recording and the last one has finished writing. */
    fun canStartRide(s: ServiceState): Boolean = s.idle

    /** An intent that is none of Start, Stop, Arm or Disarm never starts anything; it only ends a service that has no reason to run. */
    fun switchOffOnStrayIntent(s: ServiceState): Boolean = !s.armed && s.idle

    /** Disarming while a ride records or saves must leave the service running: it switches itself off when that ride is done. */
    fun switchOffOnDisarm(s: ServiceState): Boolean = s.idle

    /** Arming shows the "ready" notification only when no ride is recording or saving, so it never replaces the ride's own. */
    fun showReadyOnArm(s: ServiceState): Boolean = s.idle

    /** A Stop with no ride: switch off unless Quick start keeps the service up, and never under a ride that is still being written. */
    fun switchOffOnStopWithoutRide(s: ServiceState): Boolean = !s.saving && !s.armed

    /**
     * Whether to start a ride because the watch asked. [stopPending] is a Stop from the watch that has been accepted but not yet carried
     * out; a Start now would be answered OK and then undone by it, so it is refused instead. The watch may start with the phone locked,
     * on purpose: the phone is in the rider's pocket.
     */
    fun watchStart(s: ServiceState, stopPending: Boolean, hasLocationPermission: Boolean, gpsOn: Boolean): WatchStartDecision {
        if (stopPending) return WatchStartDecision.Refuse("busy")
        if (s.riding && !s.saving) return WatchStartDecision.AlreadyRiding
        val reason = WatchStartRules.refusal(s.saving, hasLocationPermission, gpsOn) ?: return WatchStartDecision.Go
        return WatchStartDecision.Refuse(reason)
    }

    /**
     * The cameras to send a stop to at the end of a ride: every one that confirmed its start, and also one whose start reply was lost
     * ([StartResult.NoReply]) but whose own status says it is recording, because it may well be. [recordingFlags] is each camera's own
     * recording flag by address; a camera with no flag is left alone.
     */
    fun camerasToStop(results: Map<String, StartResult>, recordingFlags: Map<String, Boolean?>): Set<String> =
        results.filter { (address, r) -> r is StartResult.Started || (r is StartResult.NoReply && recordingFlags[address] == true) }.keys

    /** Null when no camera needed the extra stop; otherwise what to tell the rider. */
    fun strayStopNote(untimedStopped: List<String>): String? = when (untimedStopped.size) {
        0 -> null
        1 -> "${untimedStopped[0]} did not answer the start command but was recording, so it was stopped. Its clip has no timing in the .fit."
        else -> "${untimedStopped.joinToString(" and ")} did not answer the start command but were recording, so they were stopped. Their clips have no timing in the .fit."
    }
}
