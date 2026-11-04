package dev.nanodash.core.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeedUnitTest {
    @Test
    fun milesInTheFewCountriesThatUseThemAndKilometresElsewhere() {
        assertEquals(SpeedUnit.Imperial, SpeedUnit.forCountry("US"))
        assertEquals(SpeedUnit.Imperial, SpeedUnit.forCountry("gb"))
        assertEquals(SpeedUnit.Metric, SpeedUnit.forCountry("BR"))
        assertEquals(SpeedUnit.Metric, SpeedUnit.forCountry(""))
    }

    @Test
    fun aHundredKilometresAnHourIsAboutSixtyTwoMiles() {
        val mps = 100 / SpeedUnit.Metric.speedPerMps
        assertEquals(62.1, mps * SpeedUnit.Imperial.speedPerMps, 0.05)
    }

    @Test
    fun theRidersChoiceIsSavedByName() {
        assertEquals(SpeedUnit.Metric, SpeedUnit.saved(SpeedUnit.Metric.name))
        assertNull(SpeedUnit.saved(null))
        assertNull(SpeedUnit.saved("furlongs"))                      // nothing valid saved: the language region decides
    }
}
