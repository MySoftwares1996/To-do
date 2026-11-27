package com.mysoftwares.todo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms all alarms after reboot, app update, time/timezone change, or permission change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
    }
}
