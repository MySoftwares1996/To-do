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

    /** The chime that is playing now: task id and a function that stops it. */
    @Volatile private var current: Pair<Long, () -> Unit>? = null

    /** Stops the chime for [taskId] (e.g. "Already done" was tapped). */
    fun stop(taskId: Long) {
        current?.takeIf { it.first == taskId }?.second?.invoke()
    }

    fun play(context: Context, taskId: Long, pending: BroadcastReceiver.PendingResult?) {
        current?.second?.invoke() // only one chime at a time
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

        var finished = false
        lateinit var step: Runnable
        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacks(step)
            current = null
            runCatching { ringtone?.stop() }
            if (wl.isHeld) wl.release()
            runCatching { pending?.finish() }
        }

        step = object : Runnable {
            override fun run() {
                if (count >= TIMES) { finish(); return }
                count++
                runCatching { ringtone?.stop(); ringtone?.play() }
                runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)) }
                handler.postDelayed(this, GAP_MS)
            }
        }
        current = taskId to { handler.post { finish() }; Unit }
        handler.post(step)
    }
}
