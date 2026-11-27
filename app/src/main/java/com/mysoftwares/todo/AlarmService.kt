package com.mysoftwares.todo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Foreground service that rings (looping alarm sound on the ALARM stream, so it is
 * heard even in silent mode) and vibrates until the user taps Done/Snooze, or
 * until it times out. Important tasks then ring again every 10 minutes until done.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "com.mysoftwares.todo.RING"
        private const val FG_ID = Int.MAX_VALUE - 7
        private const val RING_TIMEOUT_MS = 3 * 60_000L
        private const val NAG_MINUTES = 10

        @Volatile var ringingTaskId: Long = -1
            private set

        fun stopIfRinging(context: Context, id: Long) {
            if (ringingTaskId == id) context.stopService(Intent(context, AlarmService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var restoreVolume: Int = -1

    private val timeout = Runnable { onTimeout() }
    private val beep = object : Runnable {
        override fun run() {
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
            handler.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1) ?: -1
        val task = if (id >= 0) TaskDb.get(this).get(id) else null

        // Must call startForeground() promptly after startForegroundService().
        val notification = Notifications.buildAlarm(
            this, task ?: Task(title = "Reminder", startAt = 0, dueAt = System.currentTimeMillis()), fallback = false
        )
        try {
            ServiceCompat.startForeground(
                this, FG_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
            )
        } catch (e: Exception) {
            Log.e("AlarmService", "startForeground failed, using backup alarm", e)
            if (task != null) Notifications.notify(this, task.id.toInt(), Notifications.buildAlarm(this, task, true))
            stopSelf()
            return START_NOT_STICKY
        }

        if (task == null || task.done) {
            if (ringingTaskId < 0) stopSelf()
            return START_NOT_STICKY
        }

        // A second task came due while one is ringing: keep the first as a sticky reminder.
        if (ringingTaskId >= 0 && ringingTaskId != task.id) {
            TaskDb.get(this).get(ringingTaskId)?.let { prev -> handleUnanswered(prev) }
        }
        ringingTaskId = task.id
        Notifications.cancel(this, task.id)
        startRinging()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RING_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun startRinging() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfficeToDo:ringing")
                .apply { acquire(RING_TIMEOUT_MS + 30_000) }
        }
        if (player != null || tone != null) return

        val audio = getSystemService(AudioManager::class.java)
        // If the alarm volume is muted, raise it while ringing so the alarm is never silent.
        try {
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                restoreVolume = 0
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.7).toInt().coerceAtLeast(1), 0)
            }
        } catch (_: Exception) { }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(this@AlarmService, Notifications.alarmSound()!!)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // e.g. ringtone file not readable before first unlock – use a built-in tone.
            Log.w("AlarmService", "Alarm sound failed, using beep tone", e)
            player?.release(); player = null
            try {
                tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                handler.post(beep)
            } catch (_: Exception) { }
        }

        try {
            vibrator = getSystemService(Vibrator::class.java)?.also {
                it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), attrs)
            }
        } catch (_: Exception) { }
    }

    /** Alarm was not answered: leave a sticky reminder; important tasks ring again soon. */
    private fun handleUnanswered(task: Task) {
        val db = TaskDb.get(this)
        val updated = if (task.important) {
            db.save(task.copy(snoozeUntil = System.currentTimeMillis() + NAG_MINUTES * 60_000L))
        } else task
        AlarmScheduler.schedule(this, updated)
        Notifications.showMissed(this, updated)
    }

    private fun onTimeout() {
        TaskDb.get(this).get(ringingTaskId)?.let { if (!it.done) handleUnanswered(it) }
        ringingTaskId = -1
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.run { try { stop() } catch (_: Exception) { }; release() }
        player = null
        tone?.release(); tone = null
        vibrator?.cancel()
        if (restoreVolume >= 0) {
            try {
                getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, restoreVolume, 0)
            } catch (_: Exception) { }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        ringingTaskId = -1
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
