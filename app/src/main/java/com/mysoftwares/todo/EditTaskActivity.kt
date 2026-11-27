package com.mysoftwares.todo

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

class EditTaskActivity : AppCompatActivity() {
    private var existing: Task? = null
    private var when_: LocalDateTime = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS)

    private lateinit var title: EditText
    private lateinit var notes: EditText
    private lateinit var dateBtn: Button
    private lateinit var timeBtn: Button
    private lateinit var repeat: Spinner
    private lateinit var advance: Spinner
    private lateinit var important: MaterialSwitch

    private data class Template(val title: String, val repeat: Repeat, val advanceMin: Int)
    private val templates = listOf(
        Template("Bank loan / EMI due", Repeat.MONTHLY, 2 * 24 * 60),
        Template("Credit card bill due", Repeat.MONTHLY, 2 * 24 * 60),
        Template("Month-end closing", Repeat.MONTH_END, 24 * 60),
        Template("Tax / GST filing", Repeat.MONTHLY, 3 * 24 * 60),
        Template("Salary / payroll", Repeat.MONTH_END, 2 * 24 * 60),
        Template("Vendor payment", Repeat.NONE, 24 * 60),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        title = findViewById(R.id.title)
        notes = findViewById(R.id.notes)
        dateBtn = findViewById(R.id.date)
        timeBtn = findViewById(R.id.time)
        repeat = findViewById(R.id.repeat)
        advance = findViewById(R.id.advance)
        important = findViewById(R.id.important)

        repeat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Repeat.entries.map { it.label })
        advance.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ADVANCE_OPTIONS.map { it.second })

        val id = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1)
        existing = if (id > 0) TaskDb.get(this).get(id) else null
        val chips = findViewById<ChipGroup>(R.id.templates)
        existing?.let { t ->
            setTitle(R.string.edit_task)
            title.setText(t.title)
            notes.setText(t.notes)
            when_ = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.dueAt), ZoneId.systemDefault())
            repeat.setSelection(t.repeat.ordinal)
            advance.setSelection(ADVANCE_OPTIONS.indexOfFirst { it.first == t.advanceMinutes }.coerceAtLeast(0))
            important.isChecked = t.important
            findViewById<View>(R.id.delete).visibility = View.VISIBLE
            findViewById<View>(R.id.templates_label).visibility = View.GONE
            chips.visibility = View.GONE
        } ?: run {
            setTitle(R.string.new_task)
            important.isChecked = true
            templates.forEach { tpl ->
                chips.addView(Chip(this).apply {
                    text = tpl.title
                    setOnClickListener {
                        title.setText(tpl.title)
                        repeat.setSelection(tpl.repeat.ordinal)
                        advance.setSelection(ADVANCE_OPTIONS.indexOfFirst { o -> o.first == tpl.advanceMin }.coerceAtLeast(0))
                        important.isChecked = true
                    }
                })
            }
        }
        showWhen()

        dateBtn.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                when_ = when_.withYear(y).withMonth(m + 1).withDayOfMonth(d); showWhen()
            }, when_.year, when_.monthValue - 1, when_.dayOfMonth).show()
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, min ->
                when_ = when_.withHour(h).withMinute(min).withSecond(0).withNano(0); showWhen()
            }, when_.hour, when_.minute, DateFormat.is24HourFormat(this)).show()
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.delete).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete this task and its alarms?")
                .setPositiveButton("Delete") { _, _ -> existing?.let { TaskActions.delete(this, it.id) }; finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showWhen() {
        val ms = when_.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        dateBtn.text = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
        timeBtn.text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))
    }

    private fun save() {
        val name = title.text.toString().trim()
        if (name.isEmpty()) { title.error = "Please enter what you need to do"; return }
        val rep = Repeat.entries[repeat.selectedItemPosition]
        var start = when_
        if (rep == Repeat.MONTH_END) start = start.with(TemporalAdjusters.lastDayOfMonth())
        val startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        var due = startMs
        if (due <= now) {
            if (rep == Repeat.NONE) {
                Toast.makeText(this, "That time has already passed. Please pick a future time.", Toast.LENGTH_LONG).show()
                return
            }
            due = Recurrence.nextAfter(startMs, rep, now)!!
        }

        val base = existing ?: Task(title = name, startAt = startMs, dueAt = due)
        val task = TaskDb.get(this).save(
            base.copy(
                title = name,
                notes = notes.text.toString().trim(),
                startAt = startMs,
                dueAt = due,
                repeat = rep,
                important = important.isChecked,
                advanceMinutes = ADVANCE_OPTIONS[advance.selectedItemPosition].first,
                done = false,
                snoozeUntil = 0,
                rangFor = 0,
                advanceShownFor = 0,
            )
        )
        Notifications.cancel(this, task.id)
        AlarmScheduler.schedule(this, task)
        Toast.makeText(this, "Alarm set for ${Notifications.formatTime(task.dueAt)}", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
