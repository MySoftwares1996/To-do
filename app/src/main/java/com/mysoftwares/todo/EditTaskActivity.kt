package com.mysoftwares.todo

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** iOS-style task form: Cancel / Add in the bar, grouped rows, wheel pickers. */
class EditTaskActivity : BaseActivity() {
    private var existing: Task? = null
    private var whenAt: LocalDateTime = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS)
    private var repeatIndex = 0
    private var advanceIndex = 0

    private lateinit var title: EditText
    private lateinit var notes: EditText
    private lateinit var important: SwitchCompat
    private lateinit var rowDate: View
    private lateinit var rowTime: View
    private lateinit var rowRepeat: View
    private lateinit var rowEarly: View

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

        title = findViewById(R.id.title)
        notes = findViewById(R.id.notes)
        important = findViewById(R.id.important)
        rowDate = findViewById(R.id.row_date)
        rowTime = findViewById(R.id.row_time)
        rowRepeat = findViewById(R.id.row_repeat)
        rowEarly = findViewById(R.id.row_early)

        setupRow(rowDate, R.drawable.ic_calendar, R.color.ios_red, "Date", chevron = false)
        setupRow(rowTime, R.drawable.ic_clock, R.color.brand, "Time", chevron = false)
        setupRow(rowRepeat, R.drawable.ic_repeat, R.color.ios_gray, "Repeat", chevron = true)
        setupRow(rowEarly, R.drawable.ic_bell_small, R.color.ios_indigo, "Early Reminder", chevron = true)
        findViewById<ImageView>(R.id.important_icon).background.mutate()
            .setTint(ContextCompat.getColor(this, R.color.ios_orange))

        val id = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1)
        existing = if (id > 0) TaskDb.get(this).get(id) else null

        val t = existing
        if (t != null) {
            setTitle("Details")
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
            findViewById<View>(R.id.delete_group).visibility = View.VISIBLE
            findViewById<View>(R.id.delete).setOnClickListener { confirmDelete() }
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
        addCancelButton()
        showSelections()

        rowDate.setOnClickListener { pickDate() }
        rowTime.setOnClickListener { pickTime() }
        rowRepeat.setOnClickListener {
            choose("Repeat", Repeat.entries.map { it.label }, repeatIndex) { repeatIndex = it; showSelections() }
        }
        rowEarly.setOnClickListener {
            choose("Early Reminder", ADVANCE_OPTIONS.map { it.second }, advanceIndex) { advanceIndex = it; showSelections() }
        }
        findViewById<View>(R.id.row_important).setOnClickListener { important.toggle() }
        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = invalidateOptionsMenu()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("when", whenAt.toString())
        outState.putInt("repeat", repeatIndex)
        outState.putInt("advance", advanceIndex)
    }

    /** iOS puts "Cancel" on the left of the navigation bar. */
    private fun addCancelButton() {
        val bar = toolbar ?: return
        bar.addView(TextView(this).apply {
            text = "Cancel"
            textSize = 17f
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            setOnClickListener { finish() }
            layoutParams = Toolbar.LayoutParams(Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.WRAP_CONTENT, Gravity.START)
        })
    }

    private fun setupRow(row: View, icon: Int, color: Int, label: String, chevron: Boolean) {
        row.findViewById<ImageView>(R.id.row_icon).apply {
            setImageResource(icon)
            background.mutate().setTint(ContextCompat.getColor(context, color))
        }
        row.findViewById<TextView>(R.id.row_title).text = label
        row.findViewById<View>(R.id.row_chevron).visibility = if (chevron) View.VISIBLE else View.GONE
    }

    private fun setValue(row: View, value: String, accent: Boolean) {
        row.findViewById<TextView>(R.id.row_value).apply {
            text = value
            setTextColor(
                if (accent) MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
                else ContextCompat.getColor(context, R.color.ios_secondary)
            )
        }
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
        listOf(
            "Today" to today,
            "Tomorrow" to today.plusDays(1),
            "Next Monday" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
            "Month-end" to today.with(TemporalAdjusters.lastDayOfMonth()),
        ).forEach { (label, date) ->
            group.addView(Chip(this).apply {
                text = label
                setOnClickListener {
                    whenAt = LocalDateTime.of(date, whenAt.toLocalTime())
                    if (whenAt.isBefore(LocalDateTime.now())) {
                        whenAt = LocalDateTime.of(date, LocalTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS))
                    }
                    showSelections()
                }
            })
        }
    }

    /** Bottom sheet with Cancel / title / Done and a wheel picker – like iOS. */
    private fun sheet(titleText: String, content: View, onDone: () -> Unit) {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_picker, null)
        v.findViewById<TextView>(R.id.sheet_title).text = titleText
        v.findViewById<android.widget.FrameLayout>(R.id.sheet_container).addView(content)
        v.findViewById<View>(R.id.sheet_cancel).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.sheet_done).setOnClickListener { onDone(); dialog.dismiss() }
        dialog.setContentView(v)
        dialog.show()
    }

    private fun pickDate() {
        val picker = layoutInflater.inflate(R.layout.picker_date, null) as DatePicker
        picker.minDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        picker.updateDate(whenAt.year, whenAt.monthValue - 1, whenAt.dayOfMonth)
        sheet("Date", picker) {
            whenAt = whenAt.withYear(picker.year).withMonth(picker.month + 1).withDayOfMonth(picker.dayOfMonth)
            showSelections()
        }
    }

    private fun pickTime() {
        val picker = layoutInflater.inflate(R.layout.picker_time, null) as TimePicker
        picker.setIs24HourView(DateFormat.is24HourFormat(this))
        picker.hour = whenAt.hour
        picker.minute = whenAt.minute
        sheet("Time", picker) {
            whenAt = whenAt.withHour(picker.hour).withMinute(picker.minute).withSecond(0).withNano(0)
            showSelections()
        }
    }

    private fun choose(titleText: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setSingleChoiceItems(items.toTypedArray(), selected) { d, which -> onPick(which); d.dismiss() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSelections() {
        val ms = whenAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = LocalDate.now()
        val d = whenAt.toLocalDate()
        val dateText = when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
        }
        setValue(rowDate, dateText, accent = true)
        setValue(rowTime, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms)), accent = true)
        setValue(rowRepeat, if (repeatIndex == 0) "Never" else Repeat.entries[repeatIndex].label, accent = false)
        setValue(rowEarly, if (advanceIndex == 0) "None" else ADVANCE_OPTIONS[advanceIndex].second, accent = false)
    }

    private fun save() {
        val name = title.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) { title.requestFocus(); return }
        val rep = Repeat.entries[repeatIndex]
        var start = whenAt
        if (rep == Repeat.MONTH_END) start = start.with(TemporalAdjusters.lastDayOfMonth())
        val startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        var due = startMs
        if (due <= now) {
            if (rep == Repeat.NONE) {
                AlertDialog.Builder(this)
                    .setTitle("Time has passed")
                    .setMessage("Please pick a time in the future.")
                    .setPositiveButton("OK", null)
                    .show()
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

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Task?")
            .setMessage("This removes the task and its alarms.")
            .setPositiveButton("Delete") { _, _ -> existing?.let { TaskActions.delete(this, it.id) }; finish() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, if (existing == null) "Add" else "Done")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(1)?.isEnabled = !title.text.isNullOrBlank()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { save(); return true }
        return super.onOptionsItemSelected(item)
    }
}
