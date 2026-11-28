package com.mysoftwares.todo

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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

        findViewById<View>(R.id.done).setOnClickListener { TaskActions.done(this, taskId, shownDue.takeIf { it > 0 }); finish() }
        val s1 = AlarmPrefs.snooze1(this)
        val s2 = AlarmPrefs.snooze2(this)
        findViewById<TextView>(R.id.snooze10).apply {
            text = AlarmPrefs.label(s1)
            setOnClickListener { TaskActions.snooze(this@AlarmActivity, taskId, s1); finish() }
        }
        findViewById<TextView>(R.id.snooze60).apply {
            text = AlarmPrefs.label(s2)
            setOnClickListener { TaskActions.snooze(this@AlarmActivity, taskId, s2); finish() }
        }
        findViewById<View>(R.id.snooze_more).setOnClickListener { moreSnooze() }
        show(intent)

        startAnimations()
    }

    /** Soft glow colours; the screen takes the one that matches the task's priority. */
    private enum class Glow(val center: Int, val mid: Int, val accent: Int, val tagText: Int) {
        CORAL(0xFF5A2A33.toInt(), 0xFF22161C.toInt(), 0xFFFF9682.toInt(), 0xFFFFC1B4.toInt()),
        AMBER(0xFF57421F.toInt(), 0xFF211B14.toInt(), 0xFFFFC56E.toInt(), 0xFFFFDDA8.toInt()),
        BLUE(0xFF263A63.toInt(), 0xFF151A26.toInt(), 0xFF8FB8FF.toInt(), 0xFFC4D8FF.toInt()),
    }

    private fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun applyGlow(glow: Glow) {
        val root = findViewById<View>(R.id.alarm_root)
        val edge = getColor(R.color.alarm_bg)
        root.background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(glow.center, glow.mid, edge)
            setGradientCenter(0.5f, 0.45f)
            gradientRadius = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.62f
        }
        fun oval(fill: Int, stroke: Int, strokeDp: Float) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp).toInt(), stroke)
        }
        findViewById<View>(R.id.bell_disc).background = oval(withAlpha(glow.accent, 0.16f), 0, 0f)
        findViewById<View>(R.id.ripple1).background = oval(0, withAlpha(glow.accent, 0.5f), 1.5f)
        findViewById<View>(R.id.ripple2).background = oval(0, withAlpha(glow.accent, 0.5f), 1.5f)
        findViewById<View>(R.id.card).background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(24f)
            setColor(0x14FFFFFF)
            setStroke(dp(1f).toInt(), 0x17FFFFFF)
        }
        findViewById<TextView>(R.id.important).apply {
            setTextColor(glow.tagText)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12f); setColor(withAlpha(glow.accent, 0.2f))
            }
        }
        // Three small dots in the glow colours; the current one is lit.
        findViewById<android.widget.LinearLayout>(R.id.dots).apply {
            removeAllViews()
            Glow.entries.forEach { g ->
                addView(View(context).apply {
                    background = oval(if (g == glow) g.accent else withAlpha(g.accent, 0.28f), 0, 0f)
                }, android.widget.LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply {
                    marginStart = dp(3f).toInt(); marginEnd = dp(3f).toInt()
                })
            }
        }
    }

    private fun startAnimations() {
        // Two soft ripples spreading out from the bell, half a beat apart.
        listOf(R.id.ripple1 to 0L, R.id.ripple2 to 1200L).forEach { (id, delay) ->
            ObjectAnimator.ofPropertyValuesHolder(
                findViewById<View>(id),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.7f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.7f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f),
            ).apply {
                duration = 2400
                startDelay = delay
                interpolator = android.view.animation.DecelerateInterpolator()
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
        // Calm breathing of the bell.
        ObjectAnimator.ofPropertyValuesHolder(
            findViewById<View>(R.id.bell_disc),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
        ).apply {
            duration = 1200
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        // The card floats in gently.
        findViewById<View>(R.id.card).apply {
            alpha = 0f; translationY = dp(18f)
            animate().alpha(1f).translationY(0f).setDuration(450)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    /** More snooze choices: 5 min, 30 min, 2 hours, tomorrow 9 AM, or a picked time. */
    private fun moreSnooze() {
        val morning = AlarmPrefs.morningMinutes(this)
        val morningText = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            .format(java.util.Date(java.time.LocalDate.now().atTime(morning / 60, morning % 60)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
        val labels = arrayOf(
            getString(R.string.snooze_5_min), getString(R.string.snooze_30_min), getString(R.string.snooze_2_hours),
            getString(R.string.snooze_tomorrow, morningText), getString(R.string.snooze_pick_time),
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_snooze_until))
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> { TaskActions.snooze(this, taskId, 5); finish() }
                    1 -> { TaskActions.snooze(this, taskId, 30); finish() }
                    2 -> { TaskActions.snooze(this, taskId, 120); finish() }
                    3 -> {
                        val at = java.time.LocalDate.now().plusDays(1).atTime(morning / 60, morning % 60)
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
            .setTitleText(getString(R.string.msg_snooze_until_2))
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
        val due = getString(R.string.alarm_due, TaskAdapter.friendlyTime(task.dueAt))
        findViewById<TextView>(R.id.due).text = if (task.checklist.isEmpty()) due
        else due + "\n" + getString(R.string.alarm_checklist, task.checklist.count { it.done }, task.checklist.size)
        findViewById<TextView>(R.id.notes).apply {
            text = task.notes
            visibility = if (task.notes.isBlank()) View.GONE else View.VISIBLE
        }
        // Glow colour and tag follow the task: coral for important/high, amber for medium, blue otherwise.
        val glow = when {
            task.important || task.priority == Priority.HIGH -> Glow.CORAL
            task.priority == Priority.MEDIUM -> Glow.AMBER
            else -> Glow.BLUE
        }
        applyGlow(glow)
        findViewById<TextView>(R.id.important).apply {
            text = when {
                task.important -> getString(R.string.alarm_tag_important)
                task.priority != Priority.NONE -> getString(R.string.alarm_tag_priority, task.priority.label)
                else -> ""
            }
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
