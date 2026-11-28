package com.mysoftwares.todo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import java.text.DateFormat
import java.util.Date

object Notifications {
    /** Used while the alarm service is ringing (service plays the sound itself). */
    const val CH_ALARM = "alarm_ringing_v1"
    /** Used if the ringing service could not start: the notification itself loops the alarm sound. */
    const val CH_ALARM_FALLBACK = "alarm_fallback_v1"
    /** Advance reminders and "you missed this" reminders. */
    const val CH_REMINDER = "reminder_v1"
    /** Early reminders: silent channel, the app plays the sound 5 times itself (EarlyChime). */
    const val CH_EARLY = "early_v1"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, context.getString(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.ch_alarm_desc)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM_FALLBACK, context.getString(R.string.ch_backup), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.ch_backup_desc)
                setSound(alarmSound(), alarmAudio)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EARLY, context.getString(R.string.ch_early), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.ch_early_desc)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, context.getString(R.string.ch_reminder), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.ch_reminder_desc)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun alarmSound() =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun formatTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun alarmText(task: Task): String =
        if (task.important) Res.f(R.string.n_important_due, formatTime(task.dueAt))
        else Res.f(R.string.n_due, formatTime(task.dueAt))

    /** The ringing notification: full-screen alarm over the lock screen + Done/Snooze buttons. */
    fun buildAlarm(context: Context, task: Task, fallback: Boolean): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, task.id.toInt(),
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_TASK_ID, task.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, if (fallback) CH_ALARM_FALLBACK else CH_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle((if (task.important) "⚠ " else "⏰ ") + task.title)
            .setContentText(alarmText(task))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                alarmText(task) + if (task.notes.isNotBlank()) "\n${task.notes}" else ""))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, context.getString(R.string.msg_done), ActionReceiver.intent(context, task.id, ActionReceiver.ACTION_DONE, task.dueAt))
            .addAction(0, context.getString(R.string.n_snooze, AlarmPrefs.label(AlarmPrefs.snooze1(context))), ActionReceiver.intent(context, task.id, ActionReceiver.ACTION_SNOOZE))
        if (fallback) {
            b.setSound(alarmSound(), android.media.AudioManager.STREAM_ALARM)
            b.setVibrate(longArrayOf(0, 800, 400, 800))
        }
        val n = b.build()
        if (fallback) n.flags = n.flags or Notification.FLAG_INSISTENT // loop sound until handled
        return n
    }

    /** Sticky reminder left in the notification shade after an alarm was not answered. */
    fun showMissed(context: Context, task: Task) {
        val open = PendingIntent.getActivity(
            context, task.id.toInt(),
            Intent(context, AlarmActivity::class.java).putExtra(AlarmScheduler.EXTRA_TASK_ID, task.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (task.important) context.getString(R.string.n_not_done_again, AlarmPrefs.nagMinutes(context))
            else context.getString(R.string.n_not_done)
        val n = NotificationCompat.Builder(context, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.n_pending, task.title))
            .setContentText(text + " " + context.getString(R.string.n_due, formatTime(task.dueAt)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(task.important)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.msg_done), ActionReceiver.intent(context, task.id, ActionReceiver.ACTION_DONE, task.dueAt))
            .addAction(0, context.getString(R.string.msg_snooze_1_hour), ActionReceiver.intent(context, task.id, ActionReceiver.ACTION_SNOOZE_HOUR))
            .build()
        notify(context, task.id.toInt(), n)
    }

    /** Due-time reminder for tasks set to "Notification" (no ringing screen). */
    fun showDue(context: Context, task: Task) {
        val open = PendingIntent.getActivity(
            context, task.id.toInt(),
            Intent(context, AlarmActivity::class.java).putExtra(AlarmScheduler.EXTRA_TASK_ID, task.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle((if (task.important) "⚠ " else "") + task.title)
            .setContentText(alarmText(task))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                alarmText(task) + if (task.notes.isNotBlank()) "\n${task.notes}" else ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(task.important)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.msg_done), ActionReceiver.intent(context, task.id, ActionReceiver.ACTION_DONE, task.dueAt))
            .addAction(0, context.getString(R.string.n_snooze, AlarmPrefs.label(AlarmPrefs.snooze1(context))), ActionReceiver.intent(context, task.id, ActionReceiver.ACTION_SNOOZE))
            .build()
        notify(context, task.id.toInt(), n)
    }

    /** Morning summary: "Today: 3 tasks · 1 overdue" with the task names. Nothing if the day is empty. */
    fun showSummary(context: Context) {
        val now = System.currentTimeMillis()
        val endOfDay = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val due = TaskDb.get(context).all().filter { !it.done && it.dueAt < endOfDay }
            .sortedWith(compareByDescending<Task> { it.dueAt < now }.thenByDescending { it.priority.ordinal }.thenBy { it.dueAt })
        if (due.isEmpty()) return
        val overdue = due.count { it.dueAt < now }
        val title = context.getString(R.string.summary_title, due.size) +
            if (overdue > 0) context.getString(R.string.summary_overdue, overdue) else ""
        val style = NotificationCompat.InboxStyle()
        due.take(6).forEach { t ->
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t.dueAt))
            style.addLine((if (t.dueAt < now) "⚠ " else "") + "$time  ${t.priority.marks} ${t.title}".replace("  ", " "))
        }
        if (due.size > 6) style.setSummaryText(context.getString(R.string.summary_more, due.size - 6))
        val open = PendingIntent.getActivity(
            context, 7, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(due.first().title)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notify(context, Int.MAX_VALUE - 4, n)
    }

    fun showAdvance(context: Context, task: Task) {
        val open = PendingIntent.getActivity(
            context, task.id.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CH_EARLY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.n_coming, task.title))
            .setContentText(context.getString(R.string.n_due, formatTime(task.dueAt)) + if (task.important) context.getString(R.string.n_plan) else "")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.msg_already_done), ActionReceiver.intent(context, task.id, ActionReceiver.ACTION_DONE, task.dueAt))
            .build()
        notify(context, ADVANCE_ID_OFFSET + task.id.toInt(), n)
    }

    const val ADVANCE_ID_OFFSET = 1_000_000

    fun cancel(context: Context, taskId: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(taskId.toInt())
        nm.cancel(ADVANCE_ID_OFFSET + taskId.toInt())
    }

    fun notify(context: Context, id: Int, n: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // Notification permission missing – Setup screen tells the user.
        }
    }
}
