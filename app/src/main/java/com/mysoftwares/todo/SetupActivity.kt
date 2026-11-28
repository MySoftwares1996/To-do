package com.mysoftwares.todo

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Alarm health: a score out of 100, every phone setting alarms depend on (checked live,
 * with a Fix button), and "Last 7 days" — whether any alarm rang late.
 */
class SetupActivity : BaseActivity() {
    private lateinit var list: IosList
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setTitle(R.string.health_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = IosList(this, findViewById(R.id.content))
    }

    override fun onResume() {
        super.onResume()
        render() // settings may have changed in the system screen
    }

    private data class Item(val title: String, val ok: Boolean, val detail: String, val warnOnly: Boolean, val fix: (() -> Unit)?)

    private fun render() {
        list.clear()
        val checks = Reliability.checks(this)
        val log = AlarmLog.entries(this)
        val late = AlarmLog.unacknowledgedLate(this)

        // "Last 7 days" from the alarm log
        val history = if (late.isEmpty()) Item(
            getString(R.string.health_last7), true,
            if (log.isEmpty()) getString(R.string.health_no_alarms_yet) else getString(R.string.health_all_on_time, log.size),
            false, null,
        ) else {
            val worst = late.last()
            val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
            Item(
                getString(R.string.health_last7), false,
                getString(R.string.health_late, late.size, fmt.format(Date(worst.scheduled)), (worst.lateBy / 60_000).toInt()),
                true, { lateAlarmHelp() },
            )
        }
        val items = listOf(history) + checks.map { c -> Item(c.title, c.ok, if (c.ok) getString(R.string.health_ok) else c.explain, false) { c.fix(this) } }

        // Score: each failed setting −15, late alarms −10
        val problems = items.count { !it.ok }
        val score = (100 - checks.count { !it.ok } * 15 - if (late.isNotEmpty()) 10 else 0).coerceIn(0, 100)

        list.spacer()
        list.group().custom(scoreCard(score, problems))

        list.label(getString(R.string.health_checked_live))
        val g = list.group()
        // Problems first, then the ones that are fine
        items.sortedBy { it.ok }.forEach { g.custom(itemRow(it)) }

        Reliability.brandAdvice()?.let { list.footer(it) }

        list.spacer()
        val accent = com.google.android.material.color.MaterialColors.getColor(list.root, androidx.appcompat.R.attr.colorPrimary)
        list.group().row(getString(R.string.set_test_alarm_button), titleColor = accent, centered = true) {
            val at = System.currentTimeMillis() + 60_000
            val t = TaskDb.get(this).save(Task(title = getString(R.string.msg_test_alarm), notes = getString(R.string.health_test_note), startAt = at, dueAt = at))
            AlarmScheduler.schedule(this, t)
            Toast.makeText(this, getString(R.string.msg_test_alarm_in_1_minute_lock_the_phone_), Toast.LENGTH_LONG).show()
        }
        list.footer(getString(R.string.health_force_stop))
    }

    /** Fix for late alarms: brand steps, open the phone's auto-launch screen, then "I've done this". */
    private fun lateAlarmHelp() {
        val steps = Reliability.brandAdvice() ?: getString(R.string.health_generic_steps)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.health_late_title))
            .setMessage(getString(R.string.health_late_explain) + "\n\n" + steps)
            .setPositiveButton(getString(R.string.health_open_autostart)) { _, _ -> Reliability.openAutostart(this) }
            .setNeutralButton(getString(R.string.health_done_this)) { _, _ -> AlarmLog.acknowledge(this); render() }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show()
    }

    private fun scoreCard(score: Int, problems: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        row.addView(ScoreRing(this).also { it.setScore(score) }, LinearLayout.LayoutParams(dp(84), dp(84)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        texts.addView(TextView(this).apply {
            text = if (problems == 0) getString(R.string.health_all_set) else resources.getQuantityString(R.plurals.health_to_fix, problems, problems)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.ios_label))
        })
        texts.addView(TextView(this).apply {
            text = if (problems == 0) getString(R.string.health_all_set_sub) else getString(R.string.health_score_sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.ios_secondary))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun itemRow(item: Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val color = ContextCompat.getColor(this, when {
            item.ok -> R.color.ios_green
            item.warnOnly -> R.color.ios_orange
            else -> R.color.ios_red
        })
        row.addView(TextView(this).apply {
            text = if (item.ok) "✓" else "!"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        texts.addView(TextView(this).apply {
            text = item.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.ios_label))
        })
        texts.addView(TextView(this).apply {
            text = item.detail
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(ContextCompat.getColor(context, R.color.ios_secondary))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!item.ok && item.fix != null) {
            row.addView(MaterialButton(this).apply {
                text = getString(R.string.health_fix)
                textSize = 13f
                minHeight = 0; minimumHeight = 0
                insetTop = 0; insetBottom = 0
                setPadding(dp(14), dp(6), dp(14), dp(6))
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                cornerRadius = dp(16)
                setOnClickListener { item.fix.invoke() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        }
        return row
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
