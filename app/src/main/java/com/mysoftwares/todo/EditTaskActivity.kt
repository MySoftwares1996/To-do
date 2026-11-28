package com.mysoftwares.todo

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Task form: Cancel / Add in the bar, grouped rows, Android date and clock pickers. */
class EditTaskActivity : BaseActivity() {
    override val wrapWithToolbar = false // this screen has its own Cancel | Title | Add bar

    private var existing: Task? = null
    private var whenAt: LocalDateTime = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS)
    private var repeatIndex = 0
    private val advances = mutableSetOf<Int>()
    private var remindType = RemindType.ALARM
    private var priority = Priority.NONE
    private var workdayIndex = 0
    /** Month-end / quarter-end: days before the last day (0 = last day). */
    private var endOffset = 0
    /** Checklist being edited. */
    private val items = mutableListOf<CheckItem>()

    private lateinit var title: EditText
    private lateinit var notes: EditText
    private lateinit var important: SwitchCompat
    private lateinit var whenDate: TextView
    private lateinit var whenTime: TextView
    private lateinit var rowRepeat: View
    private lateinit var rowEarly: View
    private lateinit var rowWorkday: View
    private lateinit var rowPriority: View


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        title = findViewById(R.id.title)
        notes = findViewById(R.id.notes)
        important = findViewById(R.id.important)
        whenDate = findViewById(R.id.when_date)
        whenTime = findViewById(R.id.when_time)
        rowRepeat = findViewById(R.id.row_repeat)
        rowEarly = findViewById(R.id.row_early)
        rowWorkday = findViewById(R.id.row_workday)
        rowPriority = findViewById(R.id.row_priority)

        findViewById<ImageView>(R.id.when_icon).background.mutate().setTint(ContextCompat.getColor(this, R.color.ios_red))
        findViewById<ImageView>(R.id.remind_icon).background.mutate().setTint(ContextCompat.getColor(this, R.color.brand))
        setupRow(rowRepeat, R.drawable.ic_repeat, R.color.ios_gray, getString(R.string.msg_repeat), chevron = true)
        setupRow(rowEarly, R.drawable.ic_bell_small, R.color.ios_indigo, getString(R.string.msg_early_reminders), chevron = true)
        setupRow(rowPriority, R.drawable.ic_priority, R.color.ios_red, getString(R.string.msg_priority), chevron = true)
        setupRow(rowWorkday, R.drawable.ic_calendar, R.color.ios_green, getString(R.string.msg_working_day), chevron = true)
        findViewById<ImageView>(R.id.important_icon).background.mutate()
            .setTint(ContextCompat.getColor(this, R.color.ios_orange))

        val id = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1)
        existing = if (id > 0) TaskDb.get(this).get(id) else null

        val t = existing
        if (t != null) {
            findViewById<TextView>(R.id.bar_title).text = getString(R.string.msg_details)
            findViewById<TextView>(R.id.bar_save).text = getString(R.string.ui_done_2)
            if (savedInstanceState == null) {
                title.setText(t.title)
                notes.setText(t.notes)
                important.isChecked = t.important
            }
            whenAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.dueAt), ZoneId.systemDefault())
            repeatIndex = t.repeat.ordinal
            advances.addAll(t.advances)
            remindType = t.remindType
            priority = t.priority
            workdayIndex = t.workday.ordinal
            endOffset = t.endOffset
            if (savedInstanceState == null) items.addAll(t.checklist)
            findViewById<View>(R.id.delete_group).visibility = View.VISIBLE
            findViewById<View>(R.id.delete).setOnClickListener { confirmDelete() }
        } else {
            findViewById<TextView>(R.id.bar_title).text = getString(R.string.msg_new_task)
            if (savedInstanceState == null) important.isChecked = true
            title.requestFocus()
        }
        savedInstanceState?.let {
            whenAt = LocalDateTime.parse(it.getString("when"))
            repeatIndex = it.getInt("repeat")
            advances.clear(); advances.addAll(it.getIntArray("advances")?.toList().orEmpty())
            remindType = RemindType.valueOf(it.getString("remind") ?: "ALARM")
            priority = runCatching { Priority.valueOf(it.getString("priority") ?: "") }.getOrDefault(Priority.NONE)
            workdayIndex = it.getInt("workday")
            endOffset = it.getInt("endOffset")
            items.clear(); items.addAll(Checklist.fromJson(it.getString("checklist")))
        }
        findViewById<View>(R.id.bar_cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.bar_save).setOnClickListener { save() }
        updateSaveEnabled()
        renderChecklist()
        showSelections()

        whenDate.setOnClickListener {
            if (Repeat.entries[repeatIndex].usesEndOffset()) chooseEndOffset() else pickDate()
        }
        whenTime.setOnClickListener { pickTime() }
        rowRepeat.setOnClickListener {
            choose(getString(R.string.msg_repeat), Repeat.entries.map { it.label }, repeatIndex) { repeatIndex = it; showSelections() }
        }
        rowWorkday.setOnClickListener {
            choose(getString(R.string.msg_if_it_falls_on_a_weekly_off), WorkdayRule.entries.map { it.label }, workdayIndex) { workdayIndex = it; showSelections() }
        }
        rowEarly.setOnClickListener { chooseAdvances() }
        rowPriority.setOnClickListener {
            choose(getString(R.string.msg_priority), Priority.entries.map { p -> if (p.marks.isEmpty()) p.label else "${p.label}   ${p.marks}" }, priority.ordinal) {
                priority = Priority.entries[it]; showSelections()
            }
        }
        val remindGroup = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.remind_group)
        remindGroup.check(if (remindType == RemindType.NOTIFICATION) R.id.remind_notification else R.id.remind_alarm)
        remindGroup.addOnButtonCheckedListener { _, btn, checked ->
            if (checked) { remindType = if (btn == R.id.remind_notification) RemindType.NOTIFICATION else RemindType.ALARM; showSelections() }
        }
        findViewById<View>(R.id.row_important).setOnClickListener { important.toggle() }
        // Re-attach picker listeners after screen rotation.
        @Suppress("UNCHECKED_CAST")
        val oldDate = supportFragmentManager.findFragmentByTag("date") as? MaterialDatePicker<Long>
        oldDate?.let { attachDate(it) }
        (supportFragmentManager.findFragmentByTag("time") as? MaterialTimePicker)?.let { attachTime(it) }

        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateSaveEnabled()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("when", whenAt.toString())
        outState.putInt("repeat", repeatIndex)
        outState.putIntArray("advances", advances.toIntArray())
        outState.putString("remind", remindType.name)
        outState.putString("priority", priority.name)
        outState.putInt("workday", workdayIndex)
        outState.putInt("endOffset", endOffset)
        outState.putString("checklist", Checklist.toJson(items))
    }

    /** "Add" is greyed out until a title is typed. */
    private fun updateSaveEnabled() {
        val ok = !title.text.isNullOrBlank()
        findViewById<TextView>(R.id.bar_save).apply { isEnabled = ok; alpha = if (ok) 1f else 0.35f }
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



    /** Android calendar date picker. */
    private fun pickDate() {
        val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.msg_select_date))
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

    /** Android clock-dial time picker. */
    private fun pickTime() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setHour(whenAt.hour)
            .setMinute(whenAt.minute)
            .setTitleText(getString(R.string.msg_select_time))
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

    private fun chooseAdvances() {
        val options = ADVANCE_OPTIONS.filter { it.first > 0 }
        val checked = options.map { it.first in advances }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_early_reminders))
            .setMultiChoiceItems(options.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(getString(R.string.msg_ok)) { _, _ ->
                advances.clear()
                options.forEachIndexed { i, o -> if (checked[i]) advances.add(o.first) }
                showSelections()
            }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show()
    }

    private fun choose(titleText: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setSingleChoiceItems(items.toTypedArray(), selected) { d, which -> onPick(which); d.dismiss() }
            .show() // tap a choice to pick it, tap outside to close
    }

    private fun showSelections() {
        val ms = whenAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Short date; the year is shown only when it is not this year.
        val pattern = if (whenAt.year == LocalDate.now().year) "EEE, d MMM" else "EEE, d MMM yyyy"
        val rep0 = Repeat.entries[repeatIndex]
        whenDate.text = if (rep0.usesEndOffset()) endOffsetLabel(endOffset)
        else java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
        whenTime.text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))
        setValue(rowRepeat, if (repeatIndex == 0) "Never" else Repeat.entries[repeatIndex].label, accent = false)
        setValue(rowEarly, when (advances.size) {
            0 -> "None"
            else -> advances.sorted().joinToString(", ") { m ->
                // short labels: "1 day, 2 days"
                ADVANCE_OPTIONS.firstOrNull { it.first == m }?.second?.removeSuffix(" before") ?: "$m min"
            }
        }, accent = false)
        setValue(rowPriority, if (priority == Priority.NONE) "None" else "${priority.label} ${priority.marks}", accent = false)
        // For month-end / quarter-end show the next actual date, e.g. "Next: Fri, 28 Nov".
        val nextText = if (rep0.usesEndOffset()) {
            val rule = if (rep0.usesWorkdayRule()) WorkdayRule.entries[workdayIndex] else WorkdayRule.SAME_DAY
            val next = Recurrence.nextAfter(startFor(rep0), rep0, System.currentTimeMillis(), rule, endOffset, keepThisPeriod = true)
            val fmt = java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault())
            // The date before any working-day move, to explain "moved from Sun, 29 Nov".
            val plain = Recurrence.nextAfter(startFor(rep0), rep0, (next ?: 0L) - 7L * 86_400_000L, WorkdayRule.SAME_DAY, endOffset)
            val moved = if (next != null && plain != null && fmt.format(java.util.Date(plain)) != fmt.format(java.util.Date(next)) &&
                kotlin.math.abs(plain - next) < 8L * 86_400_000L
            ) " (moved from ${fmt.format(java.util.Date(plain))} – off day)" else ""
            next?.let { "Next: " + fmt.format(java.util.Date(it)) + moved + ". " }.orEmpty()
        } else ""
        findViewById<TextView>(R.id.important_note).text = nextText +
            (if (remindType == RemindType.ALARM) "Alarm: full-screen ringing. " else "Notification: one alert with Done / Snooze. ") +
                "Important: reminds again every ${AlarmPrefs.nagMinutes(this)} min until Done." +
                if (advances.isNotEmpty()) " Early reminders ring ${EarlyChime.TIMES} times." else ""
        val rep = Repeat.entries[repeatIndex]
        val showWorkday = rep.usesWorkdayRule()
        findViewById<View>(R.id.workday_wrap).visibility = if (showWorkday) View.VISIBLE else View.GONE
        setValue(rowWorkday, WorkdayRule.entries[workdayIndex].short, accent = false)
        findViewById<TextView>(R.id.workday_note).apply {
            visibility = if (showWorkday) View.VISIBLE else View.GONE
            text = "Weekly off: ${WorkDays.offDays.label} (Settings → Work days & holidays)."
        }
    }

    // ---- Checklist ----

    private fun renderChecklist(focusLast: Boolean = false) {
        val box = findViewById<android.widget.LinearLayout>(R.id.checklist_box)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun divider() = box.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.ios_separator))
        }, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(44) })

        // Header row: "Checklist  2/5"
        box.addView(TextView(this).apply {
            text = if (items.isEmpty()) getString(R.string.chk_list_title)
            else getString(R.string.chk_list_progress, items.count { it.done }, items.size)
            setTextColor(ContextCompat.getColor(context, R.color.ios_secondary))
            textSize = 12f
            isAllCaps = true
            setPadding(dp(12), dp(10), dp(12), dp(4))
        })

        items.forEachIndexed { i, item ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(dp(4), 0, dp(4), 0)
            }
            row.addView(ImageView(this).apply {
                setImageResource(if (item.done) R.drawable.ic_circle_checked else R.drawable.ic_circle_empty)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                contentDescription = getString(R.string.ui_mark_done)
                setOnClickListener {
                    items[i] = items[i].copy(done = !items[i].done); renderChecklist()
                    animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(100).start() }.start()
                }
            }, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
            val edit = EditText(this).apply {
                setText(item.text)
                hint = getString(R.string.chk_item_hint)
                background = null
                textSize = 15f
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setTextColor(ContextCompat.getColor(context, if (item.done) R.color.ios_secondary else R.color.ios_label))
                setHintTextColor(ContextCompat.getColor(context, R.color.ios_secondary))
                paintFlags = if (item.done) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (i < items.size) items[i] = items[i].copy(text = s?.toString().orEmpty())
                    }
                })
                // "Next" on the keyboard adds another step
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                setOnEditorActionListener { _, _, _ -> items.add(CheckItem("")); renderChecklist(focusLast = true); true }
            }
            row.addView(edit, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✕"
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.ios_secondary))
                contentDescription = getString(R.string.menu_delete)
                setOnClickListener { items.removeAt(i); renderChecklist() }
            }, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
            box.addView(row)
            divider()
            if (focusLast && i == items.lastIndex) edit.post {
                edit.requestFocus()
                getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(edit, 0)
            }
        }

        // "+ Add item"
        box.addView(TextView(this).apply {
            text = getString(R.string.chk_add_item)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            textSize = 15f
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(dp(14), 0, dp(12), 0)
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { items.add(CheckItem("")); renderChecklist(focusLast = true) }
        })
    }

    /** "Last day", "Last day −2" */
    private fun endOffsetLabel(n: Int) = if (n == 0) getString(R.string.end_last_day) else getString(R.string.end_last_day_minus, n)

    private fun chooseEndOffset() {
        val options = (0..7).toList()
        choose(getString(R.string.end_which_day), options.map { endOffsetLabel(it) }, endOffset) { endOffset = options[it]; showSelections() }
    }

    /**
     * First occurrence to count from. Month-end / quarter-end tasks need no date: they count from
     * this month at the chosen time; the "last day −N" is applied by the repeat rule.
     */
    private fun startFor(rep: Repeat): Long {
        val start = if (rep.usesEndOffset()) LocalDate.now().withDayOfMonth(1).atTime(whenAt.toLocalTime()) else whenAt
        return start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun save() {
        val name = title.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) { title.requestFocus(); return }
        val rep = Repeat.entries[repeatIndex]
        val startMs = startFor(rep)
        val now = System.currentTimeMillis()

        val rule = if (rep.usesWorkdayRule()) WorkdayRule.entries[workdayIndex] else WorkdayRule.SAME_DAY
        // First occurrence (moved to a working day if asked) that is still in the future.
        var due = if (rep == Repeat.NONE) startMs else Recurrence.nextAfter(startMs, rep, now - 1, rule, endOffset, keepThisPeriod = true)!!
        if (due <= now) {
            if (rep == Repeat.NONE) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.msg_time_has_passed))
                    .setMessage(getString(R.string.msg_please_pick_a_time_in_the_future))
                    .setPositiveButton(getString(R.string.msg_ok), null)
                    .show()
                return
            }
            due = Recurrence.nextAfter(startMs, rep, now, rule, endOffset)!!
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
                advances = advances.sorted(),
                remindType = remindType,
                priority = priority,
                done = false,
                snoozeUntil = 0,
                autoRepeat = false,
                checklist = items.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() },
                rangFor = 0,
                advanceShownFor = 0,
                workday = rule,
                endOffset = if (rep.usesEndOffset()) endOffset else 0,
            )
        )
        Notifications.cancel(this, task.id)
        AlarmScheduler.schedule(this, task)
        Toast.makeText(this, "Alarm set: ${TaskAdapter.friendlyTime(task.dueAt)}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_delete_task))
            .setMessage(getString(R.string.msg_this_removes_the_task_and_its_alarms))
            .setPositiveButton(getString(R.string.msg_delete)) { _, _ -> existing?.let { TaskActions.delete(this, it.id) }; finish() }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show()
    }

}
