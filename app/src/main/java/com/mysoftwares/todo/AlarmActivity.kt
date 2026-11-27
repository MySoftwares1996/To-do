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
class AlarmActivity : AppCompatActivity() {
    private var taskId: Long = -1

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

        findViewById<Button>(R.id.done).setOnClickListener { TaskActions.done(this, taskId); finish() }
        findViewById<Button>(R.id.snooze10).setOnClickListener { TaskActions.snooze(this, taskId, 10); finish() }
        findViewById<Button>(R.id.snooze60).setOnClickListener { TaskActions.snooze(this, taskId, 60); finish() }
        show(intent)

        // Gentle pulsing bell.
        ObjectAnimator.ofPropertyValuesHolder(
            findViewById<View>(R.id.bell),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.15f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f),
        ).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        taskId = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1)
        val task = TaskDb.get(this).get(taskId)
        if (task == null || task.done) { finish(); return }
        findViewById<TextView>(R.id.title).text = task.title
        findViewById<TextView>(R.id.due).text = "Due " + TaskAdapter.friendlyTime(task.dueAt)
        findViewById<TextView>(R.id.notes).apply {
            text = task.notes
            visibility = if (task.notes.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.important).visibility = if (task.important) View.VISIBLE else View.GONE
    }
}
