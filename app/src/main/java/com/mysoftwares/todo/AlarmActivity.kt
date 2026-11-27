package com.mysoftwares.todo

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full-screen alarm shown over the lock screen. */
class AlarmActivity : BaseActivity() {
    override val wrapWithToolbar = false

    private var taskId: Long = -1
    private var shownDue: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)
        // AM/PM only for 12-hour phones.
        if (android.text.format.DateFormat.is24HourFormat(this)) findViewById<View>(R.id.ampm).visibility = View.GONE

        findViewById<Button>(R.id.done).setOnClickListener { TaskActions.done(this, taskId, shownDue.takeIf { it > 0 }); finish() }
        val s1 = AlarmPrefs.snooze1(this)
        val s2 = AlarmPrefs.snooze2(this)
        findViewById<Button>(R.id.snooze10).apply {
            text = AlarmPrefs.label(s1)
            setOnClickListener { TaskActions.snooze(this@AlarmActivity, taskId, s1); finish() }
        }
        findViewById<Button>(R.id.snooze60).apply {
            text = AlarmPrefs.label(s2)
            setOnClickListener { TaskActions.snooze(this@AlarmActivity, taskId, s2); finish() }
        }
        findViewById<Button>(R.id.snooze_more).setOnClickListener { moreSnooze() }
        show(intent)

        // Gentle pulsing bell.
        ObjectAnimator.ofPropertyValuesHolder(
            findViewById<View>(R.id.bell),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.1f),
        ).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    /** More snooze choices: 5 min, 30 min, 2 hours, tomorrow 9 AM, or a picked time. */
    private fun moreSnooze() {
        val labels = arrayOf("5 minutes", "30 minutes", "2 hours", "Tomorrow, 9:00 AM", "Pick a time…")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Snooze until…")
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> { TaskActions.snooze(this, taskId, 5); finish() }
                    1 -> { TaskActions.snooze(this, taskId, 30); finish() }
                    2 -> { TaskActions.snooze(this, taskId, 120); finish() }
                    3 -> {
                        val at = java.time.LocalDate.now().plusDays(1).atTime(9, 0)
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        TaskActions.snoozeUntil(this, taskId, at); finish()
                    }
                    4 -> pickTime()
                }
            }
            .show()
    }

    private fun pickTime() {
        val now = java.time.LocalDateTime.now().plusHours(1)
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(
                if (android.text.format.DateFormat.is24HourFormat(this)) com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                else com.google.android.material.timepicker.TimeFormat.CLOCK_12H
            )
            .setHour(now.hour).setMinute(0)
            .setTitleText("Snooze until")
            .build()
        picker.addOnPositiveButtonClickListener {
            var at = java.time.LocalDate.now().atTime(picker.hour, picker.minute)
            if (!at.isAfter(java.time.LocalDateTime.now())) at = at.plusDays(1) // time passed → tomorrow
            TaskActions.snoozeUntil(this, taskId, at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            finish()
        }
        picker.show(supportFragmentManager, "snooze_time")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        taskId = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1)
        val task = TaskDb.get(this).get(taskId)
        if (task == null || task.done) { finish(); return }
        shownDue = task.dueAt
        findViewById<TextView>(R.id.title).text = task.title
        findViewById<TextView>(R.id.due).text = "Due " + TaskAdapter.friendlyTime(task.dueAt)
        findViewById<TextView>(R.id.notes).apply {
            text = task.notes
            visibility = if (task.notes.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.important).visibility = if (task.important) View.VISIBLE else View.GONE
    }
}
