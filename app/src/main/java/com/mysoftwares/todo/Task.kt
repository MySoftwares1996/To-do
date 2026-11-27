package com.mysoftwares.todo

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

enum class Repeat(val label: String) {
    NONE("Once"),
    DAILY("Every day"),
    WEEKLY("Every week"),
    MONTHLY("Every month (same date)"),
    MONTH_END("Every month-end (last day)"),
    YEARLY("Every year");
}

/** Options for "remind me before the due time" (in minutes). */
val ADVANCE_OPTIONS = listOf(
    0 to "No advance reminder",
    15 to "15 minutes before",
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
) {
    /** When the main alarm should ring. */
    val alarmAt: Long get() = if (snoozeUntil > 0) snoozeUntil else dueAt

    /** When the advance reminder should show, or null if none / already shown. */
    val advanceAt: Long?
        get() = if (advanceMinutes <= 0 || done || advanceShownFor == dueAt) null
        else dueAt - advanceMinutes * 60_000L
}

object Recurrence {
    /** Occurrence number [k] (0 = start) of a repeating task. */
    fun occurrence(start: Long, repeat: Repeat, k: Long): Long {
        val s = ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneId.systemDefault())
        val z = when (repeat) {
            Repeat.NONE -> s
            Repeat.DAILY -> s.plusDays(k)
            Repeat.WEEKLY -> s.plusWeeks(k)
            Repeat.MONTHLY -> s.plusMonths(k) // clamps 31st -> 30th/28th without drifting
            Repeat.MONTH_END -> s.plusMonths(k).with(TemporalAdjusters.lastDayOfMonth())
            Repeat.YEARLY -> s.plusYears(k)
        }
        return z.toInstant().toEpochMilli()
    }

    /** First occurrence strictly after [after]. Returns null for one-time tasks. */
    fun nextAfter(start: Long, repeat: Repeat, after: Long): Long? {
        if (repeat == Repeat.NONE) return null
        var k = 0L
        while (true) {
            val t = occurrence(start, repeat, k)
            if (t > after) return t
            k++
        }
    }
}
