package com.mysoftwares.todo

import android.content.Context

/** Done / Snooze logic shared by the alarm screen and notification buttons. */
object TaskActions {
    fun done(context: Context, id: Long) {
        val db = TaskDb.get(context)
        val t = db.get(id)
        if (t != null) {
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
