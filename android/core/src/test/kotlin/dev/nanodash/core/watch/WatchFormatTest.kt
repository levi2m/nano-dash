package dev.nanodash.core.watch

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchFormatTest {
    @Test
    fun theTimerShowsMinutesAndSecondsUnderAnHourAndHoursAfter() {
        assertEquals("0:00", WatchFormat.elapsed(0))
        assertEquals("0:09", WatchFormat.elapsed(9))
        assertEquals("12:45", WatchFormat.elapsed(765))
        assertEquals("59:59", WatchFormat.elapsed(3599))
        assertEquals("1:00:00", WatchFormat.elapsed(3600))
        assertEquals("2:05:07", WatchFormat.elapsed(7507))
        assertEquals("0:00", WatchFormat.elapsed(-5))                    // a clock step backwards never shows a minus sign
    }

    @Test
    fun theSpokenFormReadsLikeAPerson() {
        assertEquals("1 second", WatchFormat.spoken(1))
        assertEquals("45 seconds", WatchFormat.spoken(45))
        assertEquals("12 minutes 45 seconds", WatchFormat.spoken(765))
        assertEquals("1 minute", WatchFormat.spoken(60))
        assertEquals("1 hour", WatchFormat.spoken(3600))
        assertEquals("2 hours 5 minutes", WatchFormat.spoken(7507))
    }
}
