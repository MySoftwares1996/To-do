package com.mysoftwares.todo

import android.content.Context
import android.net.Uri

/**
 * Alarm settings: sound, vibrate only, the two quick snooze buttons, how often important
 * tasks ring again and how long an alarm rings. Device-protected storage, so they work
 * right after a reboot before the phone is unlocked.
 */
object AlarmPrefs {
    /** Choices offered in Settings (minutes). */
    val SNOOZE_CHOICES = listOf(5, 10, 15, 30, 60, 120)
    val NAG_CHOICES = listOf(5, 10, 15, 30)
    val RING_CHOICES = listOf(1, 3, 5, 10)

    private fun prefs(c: Context) =
        c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("alarm", Context.MODE_PRIVATE)

    fun sound(c: Context): Uri? = prefs(c).getString("sound", null)?.let(Uri::parse)
    fun setSound(c: Context, uri: Uri?) = prefs(c).edit().putString("sound", uri?.toString()).apply()

    fun vibrateOnly(c: Context) = prefs(c).getBoolean("vibrate_only", false)
    fun setVibrateOnly(c: Context, on: Boolean) = prefs(c).edit().putBoolean("vibrate_only", on).apply()

    fun snooze1(c: Context) = prefs(c).getInt("snooze1", 10)
    fun snooze2(c: Context) = prefs(c).getInt("snooze2", 60)
    fun setSnooze(c: Context, first: Int, second: Int) =
        prefs(c).edit().putInt("snooze1", first).putInt("snooze2", second).apply()

    fun nagMinutes(c: Context) = prefs(c).getInt("nag", 10)
    fun setNagMinutes(c: Context, m: Int) = prefs(c).edit().putInt("nag", m).apply()

    fun ringMinutes(c: Context) = prefs(c).getInt("ring", 3)
    fun setRingMinutes(c: Context, m: Int) = prefs(c).edit().putInt("ring", m).apply()

    /** Morning summary notification: on/off and time (minutes after midnight, default 8:30). */
    fun summaryOn(c: Context) = prefs(c).getBoolean("summary_on", true)
    fun setSummaryOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("summary_on", on).apply()
    fun summaryMinutes(c: Context) = prefs(c).getInt("summary_at", 8 * 60 + 30)
    fun setSummaryMinutes(c: Context, m: Int) = prefs(c).edit().putInt("summary_at", m).apply()

    /** Time used by "Snooze until tomorrow" (minutes after midnight, default 9:00). */
    fun morningMinutes(c: Context) = prefs(c).getInt("morning", 9 * 60)
    fun setMorningMinutes(c: Context, m: Int) = prefs(c).edit().putInt("morning", m).apply()

    /** Report: how late a completion may be and still count as "on time" (minutes; -1 = same day). */
    val ON_TIME_CHOICES: List<Pair<Int, String>>
        get() = listOf(0 to Res.s(R.string.ontime_due), 60 to Res.s(R.string.ontime_hour), -1 to Res.s(R.string.ontime_day))
    fun onTimeMinutes(c: Context) = prefs(c).getInt("on_time", 60)
    fun setOnTimeMinutes(c: Context, m: Int) = prefs(c).edit().putInt("on_time", m).apply()

    /** True if a task done at [doneAt] counts as on time for [dueAt]. */
    fun isOnTime(c: Context, dueAt: Long, doneAt: Long): Boolean {
        val m = onTimeMinutes(c)
        if (m >= 0) return doneAt <= dueAt + m * 60_000L
        val zone = java.time.ZoneId.systemDefault()
        val dueDay = java.time.Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        val doneDay = java.time.Instant.ofEpochMilli(doneAt).atZone(zone).toLocalDate()
        return !doneDay.isAfter(dueDay)
    }

    /** "10 min", "1 hour", "2 hours" */
    fun label(minutes: Int): String = when {
        minutes < 60 -> Res.f(R.string.dur_min, minutes)
        minutes == 60 -> Res.s(R.string.dur_1h)
        minutes % 60 == 0 -> Res.f(R.string.dur_hours, minutes / 60)
        else -> Res.f(R.string.dur_h_min, minutes / 60, minutes % 60)
    }
}
