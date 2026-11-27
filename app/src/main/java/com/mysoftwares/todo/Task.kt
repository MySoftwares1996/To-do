package com.mysoftwares.todo

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

enum class Repeat(val label: String) {
    NONE("Never"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    MONTH_END("Month-end"),
    QUARTERLY("Quarterly"),
    YEARLY("Yearly");
}

/** What to do when a repeating task falls on a weekly off day. */
enum class WorkdayRule(val label: String, val short: String) {
    SAME_DAY("Same day", "Same day"),
    PREVIOUS("Previous working day", "Previous"),
    NEXT("Next working day", "Next");
}

/** The working-day rule applies to repeating tasks except Daily. */
fun Repeat.usesWorkdayRule() = this != Repeat.NONE && this != Repeat.DAILY

/** Options for "remind me before the due time" (in minutes). */
val ADVANCE_OPTIONS = listOf(
    0 to "None",
    15 to "15 min before",
    60 to "1 hour before",
    24 * 60 to "1 day before",
    2 * 24 * 60 to "2 days before",
    3 * 24 * 60 to "3 days before",
    7 * 24 * 60 to "1 week before",
)

data class Task(
    val id: Long = 0,
    val title: String,
    val notes: String = "",
    /** First occurrence chosen by the user. Repeats are counted from here so dates never drift. */
    val startAt: Long,
    /** The current (next pending) occurrence. */
    val dueAt: Long,
    val repeat: Repeat = Repeat.NONE,
    val important: Boolean = true,
    val advanceMinutes: Int = 0,
    val done: Boolean = false,
    /** If > 0 the user snoozed: ring at this time instead of dueAt. */
    val snoozeUntil: Long = 0,
    /** The alarm time that has already rung, so it is not rung twice. */
    val rangFor: Long = 0,
    /** The dueAt for which the advance reminder was already shown. */
    val advanceShownFor: Long = 0,
    /** Move occurrences that fall on a weekly off day (repeating tasks except Daily). */
    val workday: WorkdayRule = WorkdayRule.SAME_DAY,
) {
    /** When the main alarm should ring. */
    val alarmAt: Long get() = if (snoozeUntil > 0) snoozeUntil else dueAt

    /** When the advance reminder should show, or null if none / already shown. */
    val advanceAt: Long?
        get() = if (advanceMinutes <= 0 || done || advanceShownFor == dueAt) null
        else dueAt - advanceMinutes * 60_000L
}

object Recurrence {
    /** Occurrence number [k] (0 = start) of a repeating task, before working-day adjustment. */
    fun occurrence(start: Long, repeat: Repeat, k: Long): Long {
        val s = ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneId.systemDefault())
        val z = when (repeat) {
            Repeat.NONE -> s
            Repeat.DAILY -> s.plusDays(k)
            Repeat.WEEKLY -> s.plusWeeks(k)
            Repeat.MONTHLY -> s.plusMonths(k) // clamps 31st -> 30th/28th without drifting
            Repeat.MONTH_END -> s.plusMonths(k).with(TemporalAdjusters.lastDayOfMonth())
            Repeat.QUARTERLY -> s.plusMonths(3 * k) // same date every 3 months, clamped like monthly
            Repeat.YEARLY -> s.plusYears(k)
        }
        return z.toInstant().toEpochMilli()
    }

    /** Moves [ms] off a weekly off day according to [rule] (keeps the time of day). */
    fun adjust(ms: Long, repeat: Repeat, rule: WorkdayRule): Long {
        if (!repeat.usesWorkdayRule() || rule == WorkdayRule.SAME_DAY) return ms
        var z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
        var guard = 0
        while (!WorkDays.isWorkingDay(z.toLocalDate()) && guard++ < 14) {
            z = if (rule == WorkdayRule.PREVIOUS) z.minusDays(1) else z.plusDays(1)
        }
        return z.toInstant().toEpochMilli()
    }

    /** First (adjusted) occurrence strictly after [after], counting from k = 0. Null for one-time tasks. */
    fun nextAfter(start: Long, repeat: Repeat, after: Long, rule: WorkdayRule = WorkdayRule.SAME_DAY): Long? {
        if (repeat == Repeat.NONE) return null
        var k = 0L
        while (true) {
            val t = adjust(occurrence(start, repeat, k), repeat, rule)
            if (t > after) return t
            k++
        }
    }
}
