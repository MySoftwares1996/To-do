package com.mysoftwares.todo

import android.content.BroadcastReceiver
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Early reminders: the notification plus the reminder sound played [TIMES] times
 * (about every 3 seconds) on the alarm volume, so it is heard even in silent mode.
 * With "Vibrate only" it vibrates instead. The due-time reminder is not affected.
 */
object EarlyChime {
    const val TIMES = 5
    private const val GAP_MS = 3_000L

    fun play(context: Context, pending: BroadcastReceiver.PendingResult?) {
        val app = context.applicationContext
        val wl = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfficeToDo:early")
            .apply { acquire(TIMES * GAP_MS + 10_000) }
        val vibrateOnly = AlarmPrefs.vibrateOnly(app)
        val ringtone = if (vibrateOnly) null else runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: Notifications.alarmSound()
            RingtoneManager.getRingtone(app, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
        }.getOrNull()
        val vibrator = runCatching { app.getSystemService(Vibrator::class.java) }.getOrNull()
        val handler = Handler(Looper.getMainLooper())
        var count = 0

        fun finish() {
            runCatching { ringtone?.stop() }
            if (wl.isHeld) wl.release()
            runCatching { pending?.finish() }
        }

        val step = object : Runnable {
            override fun run() {
                if (count >= TIMES) { finish(); return }
                count++
                runCatching { ringtone?.stop(); ringtone?.play() }
                runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)) }
                handler.postDelayed(this, GAP_MS)
            }
        }
        handler.post(step)
    }
}
