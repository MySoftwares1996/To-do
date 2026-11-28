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
            c.getString(R.string.chk_notif),
            NotificationManagerCompat.from(c).areNotificationsEnabled() && channelsOk,
            c.getString(R.string.chk_notif_exp),
            c.getString(R.string.chk_allow)
        ) { open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)) }

        list += Check(
            c.getString(R.string.chk_exact),
            AlarmScheduler.canScheduleExact(c),
            c.getString(R.string.chk_exact_exp),
            c.getString(R.string.chk_allow)
        ) {
            if (Build.VERSION.SDK_INT >= 31)
                open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${it.packageName}")))
        }

        if (Build.VERSION.SDK_INT >= 34) {
            list += Check(
                c.getString(R.string.chk_full),
                nm.canUseFullScreenIntent(),
                c.getString(R.string.chk_full_exp),
                c.getString(R.string.chk_allow)
            ) { open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${it.packageName}"))) }
        }

        list += Check(
            c.getString(R.string.chk_batt),
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
            c.getString(R.string.chk_batt_exp),
            c.getString(R.string.chk_turn_off)
        ) {
            if (!open(it, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))))
                open(it, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 28) {
            list += Check(
                c.getString(R.string.chk_bg),
                !c.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
                c.getString(R.string.chk_bg_exp),
                c.getString(R.string.chk_open)
            ) { appDetails(it) }
        }

        val audio = c.getSystemService(AudioManager::class.java)
        list += Check(
            c.getString(R.string.chk_vol),
            audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0,
            c.getString(R.string.chk_vol_exp),
            c.getString(R.string.chk_raise)
        ) {
            val am = it.getSystemService(AudioManager::class.java)
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.7).toInt(), AudioManager.FLAG_SHOW_UI)
            } catch (_: Exception) { }
        }
        return list
    }

    fun problemCount(c: Context) = checks(c).count { !it.ok }

    private fun prefs(c: Context) = try {
        c.applicationContext.getSharedPreferences("reliability", Context.MODE_PRIVATE)
    } catch (_: Exception) { null }

    /**
     * Problems the user has not been told about yet. The banner on the Today tab shows only
     * these: after install, or when a new problem appears – not again after every update.
     */
    fun newProblemCount(c: Context): Int {
        val seen = prefs(c)?.getStringSet("seen", emptySet()) ?: emptySet()
        return checks(c).count { !it.ok && it.title !in seen }
    }

    /** The user saw the banner (tapped or closed it): remember the current problems. */
    fun markSeen(c: Context) {
        val failing = checks(c).filter { !it.ok }.map { it.title }.toSet()
        prefs(c)?.edit()?.putStringSet("seen", failing)?.apply()
    }

    /** Extra advice for phone brands known to kill background apps. */
    fun brandAdvice(): String? {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                Res.s(R.string.brand_xiaomi)
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                Res.s(R.string.brand_oppo)
            m.contains("vivo") || m.contains("iqoo") ->
                Res.s(R.string.brand_vivo)
            m.contains("huawei") || m.contains("honor") ->
                Res.s(R.string.brand_huawei)
            m.contains("samsung") ->
                Res.s(R.string.brand_samsung)
            else -> null
        }
    }

    /** Battery settings for this app (where "Unrestricted" / "Don't optimise" is chosen). */
    fun batterySettings(c: Context) {
        if (!open(c, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")))) appDetails(c)
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
