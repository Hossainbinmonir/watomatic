package com.parishod.watomatic.service.rules

import com.parishod.watomatic.model.rules.AutoReplyRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RuleScheduleEvaluatorTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun timestampUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `schedule disabled always returns true`() {
        val rule = AutoReplyRule(
            name = "Always on",
            replyMessage = "Reply",
            scheduleEnabled = false,
            activeDays = emptySet()
        )
        val ts = timestampUtc(2026, Calendar.MONDAY, 16, 3, 0)
        assertTrue(RuleScheduleEvaluator.isWithinSchedule(rule, ts, utc))
    }

    @Test
    fun `same day schedule restricts by time window and active day`() {
        // 2026-06-15 is Monday (ISO 1)
        val rule = AutoReplyRule(
            name = "Work hours",
            replyMessage = "In meeting",
            scheduleEnabled = true,
            startTimeMinutes = 9 * 60,
            endTimeMinutes = 17 * 60,
            activeDays = setOf(1, 2, 3, 4, 5)
        )

        val mondayInside = timestampUtc(2026, Calendar.JUNE, 15, 11, 30)
        val mondayBefore = timestampUtc(2026, Calendar.JUNE, 15, 8, 59)
        val mondayAfter = timestampUtc(2026, Calendar.JUNE, 15, 17, 1)
        val saturdayInsideTime = timestampUtc(2026, Calendar.JUNE, 20, 11, 30)

        assertTrue(RuleScheduleEvaluator.isWithinSchedule(rule, mondayInside, utc))
        assertFalse(RuleScheduleEvaluator.isWithinSchedule(rule, mondayBefore, utc))
        assertFalse(RuleScheduleEvaluator.isWithinSchedule(rule, mondayAfter, utc))
        assertFalse(RuleScheduleEvaluator.isWithinSchedule(rule, saturdayInsideTime, utc))
    }

    @Test
    fun `overnight schedule handles midnight rollover properly`() {
        // Active Friday (ISO 5) 22:00 through Saturday 07:00
        val rule = AutoReplyRule(
            name = "Night mode",
            replyMessage = "Sleeping",
            scheduleEnabled = true,
            startTimeMinutes = 22 * 60,
            endTimeMinutes = 7 * 60,
            activeDays = setOf(5)
        )

        val fridayNight = timestampUtc(2026, Calendar.JUNE, 19, 23, 15) // Fri 23:15
        val saturdayEarly = timestampUtc(2026, Calendar.JUNE, 20, 6, 30) // Sat 06:30 (continuation of Fri)
        val saturdayMidday = timestampUtc(2026, Calendar.JUNE, 20, 12, 0) // Sat 12:00
        val thursdayNight = timestampUtc(2026, Calendar.JUNE, 18, 23, 15) // Thu 23:15

        assertTrue(RuleScheduleEvaluator.isWithinSchedule(rule, fridayNight, utc))
        assertTrue(RuleScheduleEvaluator.isWithinSchedule(rule, saturdayEarly, utc))
        assertFalse(RuleScheduleEvaluator.isWithinSchedule(rule, saturdayMidday, utc))
        assertFalse(RuleScheduleEvaluator.isWithinSchedule(rule, thursdayNight, utc))
    }
}
