package com.mysoftwares.todo

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** One item of the "will my alarms really ring?" checklist. */
data class Check(
    val title: String,
    val ok: Boolean,
    val explain: String,
    val fixLabel: String,
    val fix: (Context) -> Unit,
)

object Reliability {
    fun checks(c: Context): List<Check> {
        val list = mutableListOf<Check>()
        val nm = c.getSystemService(NotificationManager::class.java)

        val channelsOk = listOf(Notifications.CH_ALARM, Notifications.CH_ALARM_FALLBACK, Notifications.CH_REMINDER, Notifications.CH_EARLY)
            .all { (nm.getNotificationChannel(it)?.importance ?: 1) != NotificationManager.IMPORTANCE_NONE }
        list += Check(
            "Notifications allowed",
            NotificationManagerCompat.from(c).areNotificationsEnabled() && channelsOk,
            "Needed to show the alarm and the Done / Snooze buttons.",
            "Allow"
        ) { open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)) }

        list += Check(
            "Exact alarms allowed",
            AlarmScheduler.canScheduleExact(c),
            "Lets the alarm ring at the exact minute, even when the phone is idle.",
            "Allow"
        ) {
            if (Build.VERSION.SDK_INT >= 31)
                open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${it.packageName}")))
        }

        if (Build.VERSION.SDK_INT >= 34) {
            list += Check(
                "Full-screen alarm allowed",
                nm.canUseFullScreenIntent(),
                "Shows the alarm screen over the lock screen.",
                "Allow"
            ) { open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${it.packageName}"))) }
        }

        list += Check(
            "Battery optimisation off",
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
            "Stops Android from putting this app to sleep.",
            "Turn off"
        ) {
            if (!open(it, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))))
                open(it, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 28) {
            list += Check(
                "Background activity allowed",
                !c.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
                "If 'Restricted' battery mode is on, alarms can be blocked. Set battery usage to 'Unrestricted'.",
                "Open"
            ) { appDetails(it) }
        }

        val audio = c.getSystemService(AudioManager::class.java)
        list += Check(
            "Alarm volume is up",
            audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0,
            "Alarm volume is muted. (The app will raise it while ringing, but please turn it up.)",
            "Raise volume"
        ) {
            val am = it.getSystemService(AudioManager::class.java)
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.7).toInt(), AudioManager.FLAG_SHOW_UI)
            } catch (_: Exception) { }
        }
        return list
    }

    fun problemCount(c: Context) = checks(c).count { !it.ok }

    /** Extra advice for phone brands known to kill background apps. */
    fun brandAdvice(): String? {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                "Xiaomi/Redmi/POCO: App info → Autostart: ON, Battery saver: No restrictions. In Recents, long-press this app and tap the lock icon."
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                "OPPO/realme/OnePlus: App info → Battery → Allow background activity and Allow auto launch. Lock the app in Recents."
            m.contains("vivo") || m.contains("iqoo") ->
                "vivo/iQOO: Settings → Battery → Background power consumption → allow this app. Enable Autostart."
            m.contains("huawei") || m.contains("honor") ->
                "Huawei/Honor: Battery → App launch → this app → Manage manually, turn ON all three switches."
            m.contains("samsung") ->
                "Samsung: Battery → Background usage limits → make sure this app is NOT in 'Sleeping' or 'Deep sleeping' apps. Set battery to 'Unrestricted'."
            else -> null
        }
    }

    fun appDetails(c: Context) {
        open(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
    }

    private fun open(c: Context, i: Intent): Boolean = try {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) {
        try { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        false
    }
}
