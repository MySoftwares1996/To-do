package com.mysoftwares.todo

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Settings → Holidays: dates that count as off days for "Previous / Next working day". */
class HolidaysActivity : BaseActivity() {
    private lateinit var list: IosList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        title = "Holidays"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = IosList(this, findViewById(R.id.content))
        WorkDays.pruneOld(this)
        @Suppress("UNCHECKED_CAST")
        (supportFragmentManager.findFragmentByTag("date") as? MaterialDatePicker<Long>)?.let { attach(it) }
        render()
    }

    private fun render() {
        list.clear()
        val fmt = DateTimeFormatter.ofPattern("EEE, d MMM")
        val byYear = WorkDays.holidays.toSortedMap().entries.groupBy { it.key.year }
        if (byYear.isEmpty()) {
            list.label("Holidays")
            list.group().row("No holidays yet", "", centered = true)
        }
        byYear.forEach { (year, items) ->
            list.label(year.toString())
            val g = list.group()
            items.forEach { (date, name) ->
                g.row(name.ifBlank { "Holiday" }, fmt.format(date)) { confirmDelete(date, name) }
            }
        }
        list.footer(
            "Holidays count as off days for tasks set to “Previous working day” or “Next working day”. " +
                "Tap a holiday to delete it. Holidays older than a year are removed automatically."
        )
    }

    private fun confirmDelete(date: LocalDate, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${name.ifBlank { "holiday" }}?")
            .setPositiveButton("Delete") { _, _ -> update(WorkDays.holidays - date) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun update(newList: Map<LocalDate, String>) {
        WorkDays.setHolidays(this, newList)
        TaskActions.recomputeWorkdays(this) // move upcoming tasks off new holidays
        render()
    }

    private fun addHoliday() {
        val picker = MaterialDatePicker.Builder.datePicker().setTitleText("Holiday date").build()
        attach(picker)
        picker.show(supportFragmentManager, "date")
    }

    private fun attach(picker: MaterialDatePicker<Long>) {
        picker.addOnPositiveButtonClickListener { sel ->
            val date = Instant.ofEpochMilli(sel ?: return@addOnPositiveButtonClickListener).atZone(ZoneOffset.UTC).toLocalDate()
            askName(date)
        }
    }

    private fun askName(date: LocalDate) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "Name, e.g. Diwali"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(ContextCompat.getColor(context, R.color.ios_label))
        }
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy").format(date))
            .setView(box)
            .setPositiveButton("Add") { _, _ -> update(WorkDays.holidays + (date to input.text.toString().trim())) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Add").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { addHoliday(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
