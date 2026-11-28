package com.mysoftwares.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Early reminders and backup file format. */
class TaskLogicTest {
    private val due = 1_800_000_000_000L
    private val min = 60_000L

    private fun task(advances: List<Int>, shown: Long = 0, done: Boolean = false) =
        Task(id = 7, title = "EMI", startAt = due, dueAt = due, advances = advances, advanceShownFor = shown, done = done)

    @Test fun earliestReminderFirst() =
        assertEquals(due - 7 * 1440 * min, task(listOf(1440, 2880, 7 * 1440)).advanceAt)

    @Test fun nextReminderAfterOneShown() {
        val t = task(listOf(1440, 2880, 7 * 1440), shown = due - 7 * 1440 * min)
        assertEquals(due - 2880 * min, t.advanceAt)
    }

    @Test fun pastEarlyReminderDoesNotBlockLaterOnes() {
        // Created 2 days before due with "1 week" and "1 day" reminders: the 1-week time has passed.
        val t = task(listOf(1440, 7 * 1440))
        val now = due - 2 * 1440 * min
        assertEquals(due - 1440 * min, t.nextAdvanceAfter(now))
    }

    @Test fun dueAdvancePicksLatestPassedOne() {
        val t = task(listOf(1440, 7 * 1440))
        assertEquals(due - 1440 * min, t.dueAdvance(due - 1440 * min + 30_000))
        // 3 days before: the "1 week" reminder was not shown yet (e.g. phone off) → show it now.
        assertEquals(due - 7 * 1440 * min, t.dueAdvance(due - 3 * 1440 * min))
        // 8 days before: nothing is due yet.
        assertNull(t.dueAdvance(due - 8 * 1440 * min))
    }

    @Test fun noneLeftAfterLastShown() = assertNull(task(listOf(1440), shown = due - 1440 * min).advanceAt)

    @Test fun noRemindersWhenDone() = assertNull(task(listOf(1440), done = true).advanceAt)

    @Test fun noRemindersWhenEmpty() = assertNull(task(emptyList()).advanceAt)

    @Test fun alarmAtUsesSnooze() {
        assertEquals(due, task(emptyList()).alarmAt)
        assertEquals(due + 10 * min, task(emptyList()).copy(snoozeUntil = due + 10 * min).alarmAt)
    }

    @Test fun backupRoundTrip() {
        val original = listOf(
            Task(
                id = 3, title = "Bank \"EMI\", A/c 4521", notes = "₹25,000\nline 2", startAt = due, dueAt = due + 5,
                repeat = Repeat.QUARTER_END, important = true, advances = listOf(1440, 2880), workday = WorkdayRule.PREVIOUS,
                remindType = RemindType.NOTIFICATION, priority = Priority.HIGH,
            ),
            Task(id = 4, title = "Plain", startAt = due, dueAt = due, done = true),
        )
        assertEquals(original, Backup.fromJson(Backup.toJson(original)))
    }

    @Test fun oldBackupWithSingleAdvanceStillLoads() {
        val json = """{"app":"OfficeToDo","format":1,"tasks":[{"id":1,"title":"Old","startAt":5,"dueAt":5,"advanceMinutes":60}]}"""
        val t = Backup.fromJson(json).single()
        assertEquals(listOf(60), t.advances)
        assertEquals(RemindType.ALARM, t.remindType)
        assertEquals(Priority.NONE, t.priority)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherFiles() { Backup.fromJson("""{"hello":1}""") }
}
