package com.mysoftwares.todo

import android.os.Bundle
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

class EditTaskActivity : AppCompatActivity() {
    private var existing: Task? = null
    private var whenAt: LocalDateTime = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS)
    private var repeatIndex = 0
    private var advanceIndex = 0

    private lateinit var titleLayout: TextInputLayout
    private lateinit var title: TextInputEditText
    private lateinit var notes: TextInputEditText
    private lateinit var dateBtn: MaterialButton
    private lateinit var timeBtn: MaterialButton
    private lateinit var repeat: MaterialAutoCompleteTextView
    private lateinit var advance: MaterialAutoCompleteTextView
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

        titleLayout = findViewById(R.id.title_layout)
        title = findViewById(R.id.title)
        notes = findViewById(R.id.notes)
        dateBtn = findViewById(R.id.date)
        timeBtn = findViewById(R.id.time)
        repeat = findViewById(R.id.repeat)
        advance = findViewById(R.id.advance)
        important = findViewById(R.id.important)
        dateBtn.setIconResource(R.drawable.ic_calendar)
        timeBtn.setIconResource(R.drawable.ic_clock)

        repeat.setSimpleItems(Repeat.entries.map { it.label }.toTypedArray())
        advance.setSimpleItems(ADVANCE_OPTIONS.map { it.second }.toTypedArray())
        repeat.setOnItemClickListener { _, _, pos, _ -> repeatIndex = pos }
        advance.setOnItemClickListener { _, _, pos, _ -> advanceIndex = pos }

        val id = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1)
        existing = if (id > 0) TaskDb.get(this).get(id) else null

        val t = existing
        if (t != null) {
            setTitle(R.string.edit_task)
            if (savedInstanceState == null) {
                title.setText(t.title)
                notes.setText(t.notes)
                important.isChecked = t.important
            }
            whenAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.dueAt), ZoneId.systemDefault())
            repeatIndex = t.repeat.ordinal
            advanceIndex = ADVANCE_OPTIONS.indexOfFirst { it.first == t.advanceMinutes }.coerceAtLeast(0)
            findViewById<View>(R.id.templates_label).visibility = View.GONE
            findViewById<View>(R.id.templates_scroll).visibility = View.GONE
        } else {
            setTitle(R.string.new_task)
            if (savedInstanceState == null) important.isChecked = true
            buildTemplateChips()
            title.requestFocus()
        }
        savedInstanceState?.let {
            whenAt = LocalDateTime.parse(it.getString("when"))
            repeatIndex = it.getInt("repeat")
            advanceIndex = it.getInt("advance")
        }
        buildQuickDateChips()
        showSelections()

        dateBtn.setOnClickListener { pickDate() }
        timeBtn.setOnClickListener { pickTime() }
        findViewById<MaterialButton>(R.id.save).setOnClickListener { save() }

        // Re-attach picker listeners after screen rotation.
        @Suppress("UNCHECKED_CAST")
        val oldDate = supportFragmentManager.findFragmentByTag("date") as? MaterialDatePicker<Long>
        oldDate?.let { attachDate(it) }
        (supportFragmentManager.findFragmentByTag("time") as? MaterialTimePicker)?.let { attachTime(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("when", whenAt.toString())
        outState.putInt("repeat", repeatIndex)
        outState.putInt("advance", advanceIndex)
    }

    private fun buildTemplateChips() {
        val group = findViewById<ChipGroup>(R.id.templates)
        templates.forEach { tpl ->
            group.addView(Chip(this).apply {
                text = tpl.title
                setOnClickListener {
                    title.setText(tpl.title)
                    title.setSelection(tpl.title.length)
                    repeatIndex = tpl.repeat.ordinal
                    advanceIndex = ADVANCE_OPTIONS.indexOfFirst { o -> o.first == tpl.advanceMin }.coerceAtLeast(0)
                    if (tpl.repeat == Repeat.MONTH_END) {
                        whenAt = whenAt.with(TemporalAdjusters.lastDayOfMonth())
                        if (whenAt.isBefore(LocalDateTime.now())) whenAt = whenAt.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())
                    }
                    important.isChecked = true
                    showSelections()
                }
            })
        }
    }

    private fun buildQuickDateChips() {
        val group = findViewById<ChipGroup>(R.id.quick_dates)
        val today = LocalDate.now()
        val options = listOf(
            "Today" to today,
            "Tomorrow" to today.plusDays(1),
            "Next Monday" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
            "Month-end" to today.with(TemporalAdjusters.lastDayOfMonth()),
        )
        options.forEach { (label, date) ->
            group.addView(Chip(this).apply {
                text = label
                setOnClickListener {
                    whenAt = LocalDateTime.of(date, whenAt.toLocalTime())
                    // "Today" with a time already passed → move to the next hour.
                    if (whenAt.isBefore(LocalDateTime.now())) {
                        whenAt = LocalDateTime.of(date, LocalTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS))
                    }
                    showSelections()
                }
            })
        }
    }

    private fun pickDate() {
        val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select date")
            .setSelection(whenAt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .setCalendarConstraints(
                CalendarConstraints.Builder().setValidator(DateValidatorPointForward.from(todayUtc)).build()
            )
            .build()
        attachDate(picker)
        picker.show(supportFragmentManager, "date")
    }

    private fun attachDate(picker: MaterialDatePicker<Long>) {
        picker.addOnPositiveButtonClickListener { sel ->
            val ms: Long = sel ?: return@addOnPositiveButtonClickListener
            val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
            whenAt = LocalDateTime.of(d, whenAt.toLocalTime())
            showSelections()
        }
    }

    private fun pickTime() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(whenAt.hour)
            .setMinute(whenAt.minute)
            .setTitleText("Select alarm time")
            .build()
        attachTime(picker)
        picker.show(supportFragmentManager, "time")
    }

    private fun attachTime(picker: MaterialTimePicker) {
        picker.addOnPositiveButtonClickListener {
            whenAt = whenAt.withHour(picker.hour).withMinute(picker.minute).withSecond(0).withNano(0)
            showSelections()
        }
    }

    private fun showSelections() {
        val ms = whenAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        dateBtn.text = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
        timeBtn.text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))
        repeat.setText(Repeat.entries[repeatIndex].label, false)
        advance.setText(ADVANCE_OPTIONS[advanceIndex].second, false)
    }

    private fun save() {
        val name = title.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            titleLayout.error = "Please enter what you need to do"
            title.requestFocus()
            return
        }
        titleLayout.error = null
        val rep = Repeat.entries[repeatIndex]
        var start = whenAt
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
                notes = notes.text?.toString()?.trim().orEmpty(),
                startAt = startMs,
                dueAt = due,
                repeat = rep,
                important = important.isChecked,
                advanceMinutes = ADVANCE_OPTIONS[advanceIndex].first,
                done = false,
                snoozeUntil = 0,
                rangFor = 0,
                advanceShownFor = 0,
            )
        )
        Notifications.cancel(this, task.id)
        AlarmScheduler.schedule(this, task)
        Toast.makeText(this, "Alarm set: ${TaskAdapter.friendlyTime(task.dueAt)}", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (existing != null) menuInflater.inflate(R.menu.edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_delete) {
            AlertDialog.Builder(this)
                .setTitle("Delete task?")
                .setMessage("This removes the task and its alarms.")
                .setPositiveButton("Delete") { _, _ -> existing?.let { TaskActions.delete(this, it.id) }; finish() }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
