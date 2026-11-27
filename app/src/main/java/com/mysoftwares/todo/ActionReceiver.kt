package com.mysoftwares.todo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Handles the Done / Snooze buttons on notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1)
        if (id < 0) return
        when (intent.action) {
            ACTION_DONE -> TaskActions.done(
                context, id, intent.getLongExtra(EXTRA_DUE, 0).takeIf { it > 0 }
            )
            ACTION_SNOOZE -> TaskActions.snooze(context, id, 10)
            ACTION_SNOOZE_HOUR -> TaskActions.snooze(context, id, 60)
        }
    }

    companion object {
        const val ACTION_DONE = "com.mysoftwares.todo.DONE"
        const val ACTION_SNOOZE = "com.mysoftwares.todo.SNOOZE"
        const val ACTION_SNOOZE_HOUR = "com.mysoftwares.todo.SNOOZE_HOUR"
        /** The due time shown when the button was created (protects against double taps). */
        const val EXTRA_DUE = "due"

        fun intent(context: Context, id: Long, action: String, due: Long = 0): PendingIntent =
            PendingIntent.getBroadcast(
                context, id.toInt(),
                Intent(context, ActionReceiver::class.java)
                    .setAction(action)
                    .setData(Uri.parse("todo://action/$id/$action"))
                    .putExtra(AlarmScheduler.EXTRA_TASK_ID, id)
                    .putExtra(EXTRA_DUE, due),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
