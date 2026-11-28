package com.mysoftwares.todo

import android.content.Context

/**
 * Remembers when each alarm actually rang compared with its scheduled time (last 7 days),
 * so Alarm health can warn when Android delayed alarms (battery saver, phone off…).
 * Device-protected storage: works before the first unlock after a reboot.
 */
object AlarmLog {
    /** More than this late counts as "late". */
    const val LATE_MS = 2 * 60_000L
    private const val KEEP_MS = 7 * 24 * 60 * 60_000L

    data class Entry(val scheduled: Long, val rang: Long) {
        val lateBy: Long get() = rang - scheduled
        val late: Boolean get() = lateBy > LATE_MS
    }

    private fun prefs(c: Context) =
        c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("alarm_log", Context.MODE_PRIVATE)

    @Synchronized
    fun record(c: Context, scheduled: Long, rang: Long) {
        val keep = entries(c).filter { rang - it.rang < KEEP_MS } + Entry(scheduled, rang)
        prefs(c).edit().putString("log", keep.takeLast(200).joinToString(";") { "${it.scheduled},${it.rang}" }).apply()
    }

    /** The user fixed the phone settings: hide late alarms that happened before now. */
    fun acknowledge(c: Context) = prefs(c).edit().putLong("ack", System.currentTimeMillis()).apply()

    /** Late alarms the user has not acknowledged yet. */
    fun unacknowledgedLate(c: Context): List<Entry> {
        val ack = prefs(c).getLong("ack", 0)
        return entries(c).filter { it.late && it.rang > ack }
    }

    /** Alarms of the last 7 days, oldest first. */
    fun entries(c: Context): List<Entry> {
        val now = System.currentTimeMillis()
        return (prefs(c).getString("log", "") ?: "").split(";").mapNotNull { part ->
            val (a, b) = part.split(",").takeIf { it.size == 2 } ?: return@mapNotNull null
            val e = Entry(a.toLongOrNull() ?: return@mapNotNull null, b.toLongOrNull() ?: return@mapNotNull null)
            e.takeIf { now - it.rang < KEEP_MS }
        }
    }
}
