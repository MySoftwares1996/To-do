package com.mysoftwares.todo

import android.content.Intent
import android.os.Bundle

/** Settings → Work days & holidays: weekly off days (one tap) and the holiday list. */
class WorkDaysActivity : BaseActivity() {
    private lateinit var list: IosList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setTitle(R.string.set_workdays)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = IosList(this, findViewById(R.id.content))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.clear()
        list.label(getString(R.string.msg_weekly_off))
        val off = list.group()
        WorkDays.OffDays.entries.forEach { o ->
            off.row(o.label, if (WorkDays.offDays == o) "✓" else "") {
                if (WorkDays.offDays != o) {
                    WorkDays.set(this, o)
                    TaskActions.recomputeWorkdays(this) // move upcoming tasks to their new working days
                    render()
                }
            }
        }
        list.label(getString(R.string.msg_holidays))
        list.group().row(getString(R.string.msg_holidays), "${WorkDays.holidays.size} ›") {
            startActivity(Intent(this, HolidaysActivity::class.java))
        }
        list.footer(getString(R.string.ui_used_by_repeating_tasks_set_to_previous_))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
