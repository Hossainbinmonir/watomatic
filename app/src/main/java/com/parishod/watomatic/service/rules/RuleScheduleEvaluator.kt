package com.parishod.watomatic.service.rules

import com.parishod.watomatic.model.rules.AutoReplyRule
import java.util.Calendar
import java.util.TimeZone

object RuleScheduleEvaluator {

    fun isWithinSchedule(
        rule: AutoReplyRule,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        if (!rule.scheduleEnabled) return true
        if (rule.activeDays.isEmpty()) return false

        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
        }
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayIso = toIsoDayOfWeek(cal.get(Calendar.DAY_OF_WEEK))
        val yesterdayIso = if (todayIso == 1) 7 else todayIso - 1

        val start = rule.startTimeMinutes.coerceIn(0, 1439)
        val end = rule.endTimeMinutes.coerceIn(0, 1439)

        return when {
            start == end -> todayIso in rule.activeDays
            start < end -> {
                todayIso in rule.activeDays && currentMinutes in start..end
            }
            else -> {
                // Overnight window (e.g., 22:00 .. 07:00)
                (todayIso in rule.activeDays && currentMinutes >= start) ||
                    (yesterdayIso in rule.activeDays && currentMinutes <= end)
            }
        }
    }

    fun toIsoDayOfWeek(calendarDayOfWeek: Int): Int {
        return when (calendarDayOfWeek) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }
}
