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

    /** "10 min", "1 hour", "2 hours" */
    fun label(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes == 60 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
