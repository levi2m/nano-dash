package dev.nanodash.core.watch

/** Whether the phone may start a ride when the watch asks. Returns the reason to refuse, or null to go ahead. */
object WatchStartRules {
    fun refusal(saving: Boolean, hasLocationPermission: Boolean, gpsOn: Boolean): String? = when {
        saving -> "busy"
        !hasLocationPermission -> "no-permission"
        !gpsOn -> "gps-off"
        else -> null
    }
}
