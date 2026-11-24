package com.chencohen.ptt.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** The chat's days (2026-11-24, hen): calendar days in the phone's own time zone. */
class DaysTest {
    private val israel = TimeZone.getTimeZone("Asia/Jerusalem")

    @Test
    fun yesterdayIsTheCalendarDayBeforeNotTwentyFourHours() {
        // 2026-11-24 00:30 in Israel; a message at 23:50 the evening before is yesterday, though only 40 minutes ago.
        val now = 1_795_473_000_000L
        val cal = java.util.Calendar.getInstance(israel).apply { timeInMillis = now }
        assertEquals(0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        val lateLastNight = now - 40 * 60_000L
        assertEquals(1, daysAgo(dayOf(lateLastNight, israel), now, israel))
        assertEquals(0, daysAgo(dayOf(now, israel), now, israel))
        assertEquals(6, daysAgo(dayOf(now - 6 * 86_400_000L, israel), now, israel))
    }
}
