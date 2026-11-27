package com.mysoftwares.todo

import android.content.Context

/** Done / Snooze logic shared by the alarm screen and notification buttons. */
object TaskActions {
    /**
     * Marks the task done (or moves a repeating task to its next date).
     * [expectedDue] is the due time the user saw; if the task has already moved on
     * (a double tap, or Done pressed on two places) the call is ignored, so a
     * repeating task never skips a date.
     */
    fun done(context: Context, id: Long, expectedDue: Long? = null) {
        val db = TaskDb.get(context)
        val t = db.get(id)
        if (t != null && !t.done && (expectedDue == null || expectedDue == t.dueAt)) {
            db.logDone(t, System.currentTimeMillis()) // for the monthly report
            val next = Recurrence.nextAfter(t.startAt, t.repeat, maxOf(System.currentTimeMillis(), t.dueAt), t.workday)
            val saved = if (next == null) {
                db.save(t.copy(done = true, snoozeUntil = 0))
            } else {
                // Repeating task: move on to the next occurrence.
                db.save(t.copy(dueAt = next, snoozeUntil = 0, rangFor = 0, advanceShownFor = 0))
            }
            AlarmScheduler.schedule(context, saved)
        }
        finishAlarm(context, id)
    }

    fun snooze(context: Context, id: Long, minutes: Int) {
        val db = TaskDb.get(context)
        db.get(id)?.let { t ->
            if (!t.done) {
                AlarmScheduler.schedule(
                    context,
                    db.save(t.copy(snoozeUntil = System.currentTimeMillis() + minutes * 60_000L))
                )
            }
        }
        finishAlarm(context, id)
    }

    /** Snooze until an exact time (e.g. "Tomorrow 9:00 AM" or a picked time). */
    fun snoozeUntil(context: Context, id: Long, at: Long) {
        val db = TaskDb.get(context)
        db.get(id)?.let { t ->
            if (!t.done) AlarmScheduler.schedule(context, db.save(t.copy(snoozeUntil = at)))
        }
        finishAlarm(context, id)
    }

    fun undoDone(context: Context, id: Long) {
        val db = TaskDb.get(context)
        db.get(id)?.let { AlarmScheduler.schedule(context, db.save(it.copy(done = false, rangFor = 0, snoozeUntil = 0))) }
    }

    fun delete(context: Context, id: Long) {
        AlarmScheduler.cancel(context, id)
        TaskDb.get(context).delete(id)
        finishAlarm(context, id)
    }

    private fun finishAlarm(context: Context, id: Long) {
        Notifications.cancel(context, id)
        AlarmService.stopIfRinging(context, id)
    }

    /** After the weekly off days change, move upcoming repeating tasks to their new dates. */
    fun recomputeWorkdays(context: Context) {
        val db = TaskDb.get(context)
        val now = System.currentTimeMillis()
        db.all().filter { !it.done && it.repeat.usesWorkdayRule() && it.workday != WorkdayRule.SAME_DAY && it.dueAt > now }
            .forEach { t ->
                val due = Recurrence.nextAfter(t.startAt, t.repeat, now, t.workday) ?: return@forEach
                if (due != t.dueAt) AlarmScheduler.schedule(context, db.save(t.copy(dueAt = due, rangFor = 0, advanceShownFor = 0)))
            }
    }
}
