package dev.nanodash.core.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchStartRulesTest {
    @Test fun goesAheadWhenAllIsWell() = assertNull(WatchStartRules.refusal(saving = false, hasLocationPermission = true, gpsOn = true))

    @Test fun savingComesFirst() = assertEquals("busy", WatchStartRules.refusal(saving = true, hasLocationPermission = false, gpsOn = false))

    @Test fun permissionBeforeGps() = assertEquals("no-permission", WatchStartRules.refusal(saving = false, hasLocationPermission = false, gpsOn = false))

    @Test fun gpsOff() = assertEquals("gps-off", WatchStartRules.refusal(saving = false, hasLocationPermission = true, gpsOn = false))
}
