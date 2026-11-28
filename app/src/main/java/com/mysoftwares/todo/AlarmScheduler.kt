package com.mysoftwares.todo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Arms the system alarms for tasks.
 *
 * Main alarms use AlarmManager.setAlarmClock(): the highest-priority alarm type on
 * Android. It fires on time even in Doze / battery saver, wakes the device, and
 * shows the alarm icon in the status bar.
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    const val ACTION_ALARM = "com.mysoftwares.todo.ALARM"
    const val ACTION_ADVANCE = "com.mysoftwares.todo.ADVANCE"
    const val ACTION_SUMMARY = "com.mysoftwares.todo.SUMMARY"
    const val EXTRA_TASK_ID = "task_id"

    fun schedule(context: Context, task: Task) {
        cancel(context, task.id)
        if (task.done) return
        val now = System.currentTimeMillis()

        // Main alarm. If its time already passed (phone was off, app was killed…)
        // and it has not rung yet, ring a few seconds from now instead of skipping it.
        if (task.alarmAt != task.rangFor) {
            val at = maxOf(task.alarmAt, now + 3_000)
            setAlarm(context, at, alarmIntent(context, task.id, ACTION_ALARM))
        }

        // Advance ("due tomorrow") reminder, only if still in the future.
        task.nextAdvanceAfter(now)?.let { at ->
            if (task.snoozeUntil == 0L) {
                setAlarm(context, at, alarmIntent(context, task.id, ACTION_ADVANCE))
            }
        }
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(context, id, ACTION_ALARM))
        am.cancel(alarmIntent(context, id, ACTION_ADVANCE))
    }

    /** Re-arm every pending task. Safe to call any time (app start, boot, time change). */
    fun rescheduleAll(context: Context) {
        try {
            TaskDb.get(context).all().forEach { schedule(context, it) }
            scheduleSummary(context)
        } catch (e: Exception) {
            Log.e(TAG, "rescheduleAll failed", e)
        }
    }

    /** Arms tomorrow's (or today's) morning summary, or cancels it when switched off. */
    fun scheduleSummary(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, Int.MAX_VALUE - 3,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_SUMMARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (!AlarmPrefs.summaryOn(context)) return
        val m = AlarmPrefs.summaryMinutes(context)
        var at = java.time.LocalDate.now().atTime(m / 60, m % 60)
        if (!at.isAfter(java.time.LocalDateTime.now())) at = at.plusDays(1)
        val ms = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun setAlarm(context: Context, at: Long, op: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            if (canScheduleExact(context)) {
                val show = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm not allowed, falling back", e)
        }
        // Fallback (exact alarms disabled by the user): still fires in Doze, may be a few minutes late.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
    }

    private fun alarmIntent(context: Context, id: Long, action: String): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("todo://task/$id/$action")) // unique per task + type
            .putExtra(EXTRA_TASK_ID, id)
        val code = (id * 2 + if (action == ACTION_ADVANCE) 1 else 0).toInt()
        return PendingIntent.getBroadcast(
            context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
