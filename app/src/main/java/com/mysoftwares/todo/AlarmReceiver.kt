package com.mysoftwares.todo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Receives the system alarm and starts ringing. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1)
        if (id < 0) return
        // Keep the CPU awake until the ringing service has started.
        val wl = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfficeToDo:alarm")
        wl.acquire(60_000)

        val db = TaskDb.get(context)
        val task = db.get(id) ?: return
        if (task.done) return
        val now = System.currentTimeMillis()

        when (intent.action) {
            AlarmScheduler.ACTION_ADVANCE -> {
                // Stale (task edited) – just re-arm the next one.
                val at = task.dueAdvance(now) ?: run { AlarmScheduler.schedule(context, task); return }
                Notifications.showAdvance(context, task) // silent notification…
                EarlyChime.play(context, goAsync())       // …plus the sound 5 times
                // Remember which early reminder was shown; the next one (if any) is armed.
                AlarmScheduler.schedule(context, db.save(task.copy(advanceShownFor = at)))
            }
            AlarmScheduler.ACTION_ALARM -> {
                // Stale alarm (task was edited to a later time) – just re-arm.
                if (task.alarmAt > now + 60_000 || task.alarmAt == task.rangFor) {
                    AlarmScheduler.schedule(context, task)
                    return
                }
                val rang = db.save(task.copy(rangFor = task.alarmAt))
                if (task.remindType == RemindType.NOTIFICATION) notifyOnly(context, rang) else ring(context, task)
            }
        }
    }

    companion object {
        /** "Notification" reminder type: one notification with sound, no ringing screen. */
        fun notifyOnly(context: Context, task: Task) {
            Notifications.showDue(context, task)
            if (task.important) {
                // Important: come back every few minutes until Done.
                val db = TaskDb.get(context)
                AlarmScheduler.schedule(
                    context,
                    db.save(task.copy(snoozeUntil = System.currentTimeMillis() + AlarmPrefs.nagMinutes(context) * 60_000L))
                )
            }
        }

        fun ring(context: Context, task: Task) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AlarmService::class.java)
                        .setAction(AlarmService.ACTION_RING)
                        .putExtra(AlarmScheduler.EXTRA_TASK_ID, task.id)
                )
            } catch (e: Exception) {
                // Backup path: a looping, insistent alarm notification.
                Log.e("AlarmReceiver", "Could not start ringing service, using backup alarm", e)
                Notifications.notify(context, task.id.toInt(), Notifications.buildAlarm(context, task, fallback = true))
            }
        }
    }
}
