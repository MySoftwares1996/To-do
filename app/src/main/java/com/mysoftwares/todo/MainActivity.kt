package com.mysoftwares.todo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.media.RingtoneManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MainActivity : BaseActivity() {
    override val wrapWithToolbar = false

    private lateinit var adapter: TaskAdapter
    private lateinit var list: RecyclerView
    private var tab = Tab.TODAY
    private var onSettings = false
    private var query = ""
    private var searchOpen = false
    private lateinit var search: android.widget.EditText
    private lateinit var bottomNav: PillTabBar
    private lateinit var settingsPage: View
    private var askedPermission = false
    private val io = AppExecutors.io
    private val updates by lazy { UpdateUi(this) }
    private val grouped by lazy { Appearance.listStyle(this) == Appearance.ListStyle.COMPACT }

    /** Refresh once a minute so "in 5 minutes" / overdue colours stay current. */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ticker.postDelayed(this, 60_000) }
    }

    /** Back from another tab returns to Today first. */
    private val backToToday = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { bottomNav.select(R.id.tab_today) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val bar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(bar)
        supportActionBar?.setDisplayShowTitleEnabled(false) // own title + summary views (never cut off)
        list = findViewById(R.id.list)
        settingsPage = findViewById(R.id.settings_page)
        search = findViewById(R.id.search)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString().orEmpty(); refresh() }
        })
        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setItems(listOf(
            PillTabBar.Item(R.id.tab_today, R.drawable.ic_calendar, getString(R.string.ui_today)),
            PillTabBar.Item(R.id.tab_upcoming, R.drawable.ic_clock, getString(R.string.ui_upcoming)),
            PillTabBar.Item(R.id.tab_done, R.drawable.ic_check_circle, getString(R.string.ui_done_2)),
            PillTabBar.Item(R.id.tab_settings, R.drawable.ic_settings, getString(R.string.ui_settings)),
        ))
        onBackPressedDispatcher.addCallback(this, backToToday)
        setupSettings()

        adapter = TaskAdapter(
            onClick = { startActivity(Intent(this, EditTaskActivity::class.java).putExtra(AlarmScheduler.EXTRA_TASK_ID, it.id)) },
            onCheck = { toggleDone(it) },
            onBanner = { startActivity(Intent(this, SetupActivity::class.java)) },
            grouped = grouped,
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator?.changeDuration = 150
        attachSwipe()

        bottomNav.onSelect = { id ->
            when (id) {
                R.id.tab_today -> showTab(Tab.TODAY)
                R.id.tab_upcoming -> showTab(Tab.UPCOMING)
                R.id.tab_done -> showTab(Tab.DONE)
                R.id.tab_settings -> showSettings()
            }
        }
        val saved = savedInstanceState?.getString("tab")
        bottomNav.select(when (saved) {
            "UPCOMING" -> R.id.tab_upcoming
            "DONE" -> R.id.tab_done
            "SETTINGS" -> R.id.tab_settings
            else -> R.id.tab_today
        }, animate = false, notify = false)
        when (saved) {
            "UPCOMING" -> showTab(Tab.UPCOMING)
            "DONE" -> showTab(Tab.DONE)
            "SETTINGS" -> showSettings()
            else -> showTab(Tab.TODAY)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", if (onSettings) "SETTINGS" else tab.name)
    }

    private fun showTab(t: Tab) {
        tab = t
        onSettings = false
        setHeader(t.title, null)
        list.visibility = View.VISIBLE
        settingsPage.visibility = View.GONE
        search.visibility = if (searchOpen) View.VISIBLE else View.GONE
        backToToday.isEnabled = t != Tab.TODAY
        invalidateOptionsMenu()
        refresh()
        list.scrollToPosition(0)
        // Live animation: the list slides in and tasks drop in one after another.
        animateIn(list)
        list.scheduleLayoutAnimation()
    }

    private fun showSettings() {
        onSettings = true
        setHeader(getString(R.string.ui_settings), "Office To-Do ${Updater.currentVersionName}")
        list.visibility = View.GONE
        settingsPage.visibility = View.VISIBLE
        search.visibility = View.GONE
        backToToday.isEnabled = true
        invalidateOptionsMenu()
        refreshSettings()
        animateIn(settingsPage)
    }

    // ---- Settings tab ----

    private fun settingRow(id: Int, icon: Int, color: Int, label: String, onClick: () -> Unit) {
        val row = findViewById<View>(id)
        row.findViewById<android.widget.ImageView>(R.id.row_icon).apply {
            setImageResource(icon)
            background.mutate().setTint(ContextCompat.getColor(context, color))
        }
        row.findViewById<android.widget.TextView>(R.id.row_title).text = label
        row.setOnClickListener { onClick() }
    }

    private fun settingValue(id: Int, value: String) {
        findViewById<View>(id).findViewById<android.widget.TextView>(R.id.row_value).text = value
    }

    private fun setupSettings() {
        settingRow(R.id.set_reliability, R.drawable.ic_check_circle, R.color.ios_green, getString(R.string.setup_title)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        settingRow(R.id.set_sound, R.drawable.ic_bell_small, R.color.ios_indigo, getString(R.string.msg_alarm_sound)) { pickSound() }
        settingRow(R.id.set_vibrate, R.drawable.ic_repeat, R.color.ios_gray, getString(R.string.msg_vibrate_only)) {
            AlarmPrefs.setVibrateOnly(this, !AlarmPrefs.vibrateOnly(this)); refreshSettings()
        }
        settingRow(R.id.set_snooze, R.drawable.ic_clock, R.color.ios_orange, getString(R.string.msg_snooze_buttons)) { chooseSnooze() }
        settingRow(R.id.set_morning, R.drawable.ic_clock, R.color.ios_indigo, getString(R.string.set_morning)) { pickMorning() }
        settingRow(R.id.set_nag, R.drawable.ic_flag, R.color.ios_orange, getString(R.string.msg_repeat_important_every)) {
            pickMinutes(getString(R.string.msg_repeat_important_every), AlarmPrefs.NAG_CHOICES, AlarmPrefs.nagMinutes(this)) {
                AlarmPrefs.setNagMinutes(this, it); refreshSettings()
            }
        }
        settingRow(R.id.set_ring, R.drawable.ic_bell_small, R.color.ios_red, getString(R.string.msg_ring_for)) {
            pickMinutes(getString(R.string.msg_ring_for), AlarmPrefs.RING_CHOICES, AlarmPrefs.ringMinutes(this)) {
                AlarmPrefs.setRingMinutes(this, it); refreshSettings()
            }
        }
        settingRow(R.id.set_test, R.drawable.ic_bell_small, R.color.ios_red, getString(R.string.msg_test_alarm)) { testAlarm() }
        settingRow(R.id.set_weekly_off, R.drawable.ic_calendar, R.color.ios_orange, getString(R.string.msg_weekly_off)) { chooseWeeklyOff() }
        settingRow(R.id.set_holidays, R.drawable.ic_flag, R.color.ios_red, getString(R.string.msg_holidays)) {
            startActivity(Intent(this, HolidaysActivity::class.java))
        }
        settingRow(R.id.set_report, R.drawable.ic_check_circle, R.color.ios_green, getString(R.string.msg_monthly_report)) {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        settingRow(R.id.set_appearance, R.drawable.ic_palette, R.color.brand, getString(R.string.appearance_title)) {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        settingRow(R.id.set_backup, R.drawable.ic_tray, R.color.ios_indigo, getString(R.string.backup_title)) {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        settingRow(R.id.set_update, R.drawable.ic_repeat, R.color.ios_gray, getString(R.string.msg_check_for_updates)) { updates.checkForUpdate(manual = true) }
        findViewById<View>(R.id.set_clear).setOnClickListener { clearCompleted() }
        findViewById<android.widget.TextView>(R.id.set_version).text = "Office To-Do ${Updater.currentVersionName}"
    }

    private fun refreshSettings() {
        val problems = Reliability.problemCount(this)
        settingValue(R.id.set_reliability, if (problems == 0) "All set" else "$problems to fix")
        findViewById<View>(R.id.set_reliability).findViewById<android.widget.ImageView>(R.id.row_icon)
            .background.mutate().setTint(ContextCompat.getColor(this, if (problems == 0) R.color.ios_green else R.color.ios_red))
        settingValue(R.id.set_test, "in 1 min")
        settingValue(R.id.set_sound, if (AlarmPrefs.vibrateOnly(this)) "Off" else soundName())
        settingValue(R.id.set_vibrate, if (AlarmPrefs.vibrateOnly(this)) "On" else "Off")
        settingValue(R.id.set_snooze, "${AlarmPrefs.label(AlarmPrefs.snooze1(this))} · ${AlarmPrefs.label(AlarmPrefs.snooze2(this))}")
        settingValue(R.id.set_nag, "${AlarmPrefs.nagMinutes(this)} min")
        val mm = AlarmPrefs.morningMinutes(this)
        settingValue(R.id.set_morning, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(
            java.util.Date(java.time.LocalDate.now().atTime(mm / 60, mm % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())))
        settingValue(R.id.set_ring, "${AlarmPrefs.ringMinutes(this)} min")
        settingValue(R.id.set_holidays, WorkDays.holidays.size.toString())

        settingValue(R.id.set_appearance, Appearance.mode(this).label)
        settingValue(R.id.set_weekly_off, when (WorkDays.offDays) {
            WorkDays.OffDays.SAT_SUN -> "Sat & Sun"
            WorkDays.OffDays.SUN -> "Sunday"
            WorkDays.OffDays.SUN_2ND_4TH_SAT -> "Sun + 2nd/4th Sat"
        })
        settingValue(R.id.set_backup, if (Backup.autoUri(this) != null) "On" else "Off")
        settingValue(R.id.set_update, Updater.currentVersionName)
    }

    private val soundPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = r.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AlarmPrefs.setSound(this, uri)
            refreshSettings()
        }
    }

    private fun pickSound() {
        soundPicker.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmPrefs.sound(this) ?: Notifications.alarmSound())
        )
    }

    private fun soundName(): String {
        val uri = AlarmPrefs.sound(this) ?: return "Default"
        return runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull() ?: "Custom"
    }

    private fun pickMinutes(title: String, choices: List<Int>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(choices.map { AlarmPrefs.label(it) }.toTypedArray(), choices.indexOf(current)) { d, i ->
                onPick(choices[i]); d.dismiss()
            }
            .show()
    }

    /** Time used by "Snooze until tomorrow" on the alarm screen. */
    private fun pickMorning() {
        val m = AlarmPrefs.morningMinutes(this)
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(
                if (android.text.format.DateFormat.is24HourFormat(this)) com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                else com.google.android.material.timepicker.TimeFormat.CLOCK_12H
            )
            .setHour(m / 60).setMinute(m % 60)
            .setTitleText(getString(R.string.set_morning))
            .build()
        picker.addOnPositiveButtonClickListener {
            AlarmPrefs.setMorningMinutes(this, picker.hour * 60 + picker.minute); refreshSettings()
        }
        picker.show(supportFragmentManager, "morning")
    }

    /** Pick the two quick snooze buttons shown on the alarm screen. */
    private fun chooseSnooze() {
        val choices = AlarmPrefs.SNOOZE_CHOICES
        pickMinutes(getString(R.string.msg_first_snooze_button), choices, AlarmPrefs.snooze1(this)) { first ->
            pickMinutes(getString(R.string.msg_second_snooze_button), choices.filter { it != first }, AlarmPrefs.snooze2(this)) { second ->
                AlarmPrefs.setSnooze(this, minOf(first, second), maxOf(first, second))
                refreshSettings()
            }
        }
    }

    private fun chooseWeeklyOff() {
        val options = WorkDays.OffDays.entries
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_weekly_off))
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), WorkDays.offDays.ordinal) { d, which ->
                WorkDays.set(this, options[which])
                TaskActions.recomputeWorkdays(this) // move upcoming tasks to their new working days
                d.dismiss()
                refresh()
            }
            .show()
    }

    private fun testAlarm() {
        val at = System.currentTimeMillis() + 60_000
        val t = TaskDb.get(this).save(
            Task(title = getString(R.string.msg_test_alarm), notes = "If you can hear this, alarms work. Tap Done.", startAt = at, dueAt = at)
        )
        AlarmScheduler.schedule(this, t)
        toast(getString(R.string.msg_test_alarm_in_1_minute_lock_the_phone_))
    }

    override fun onResume() {
        super.onResume()
        // Self-healing: every time the app is opened, all alarms are re-armed.
        AlarmScheduler.rescheduleAll(this)
        TaskWidget.refresh(this)
        ticker.removeCallbacks(tick)
        tick.run()
        if (!askedPermission && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        updates.onResume()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val tasks = TaskDb.get(this).all()
        adapter.submitList(TaskAdapter.buildRows(tasks, tab, Reliability.problemCount(this), query))
        if (!onSettings) setHeader(null, TaskAdapter.summary(tasks, tab, query))
        // Red badge on the Today tab = number of overdue tasks.
        val now = System.currentTimeMillis()
        val overdue = tasks.count { !it.done && it.dueAt < now }
        bottomNav.setBadge(R.id.tab_today, overdue)
        if (onSettings) refreshSettings()
    }

    // ---- Done / Delete / Undo ----

    private fun haptic() {
        list.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun toggleDone(task: Task) {
        haptic()
        val before = TaskDb.get(this).get(task.id) ?: return
        // Ignore a stale tap: the task already changed since this row was shown (double tap).
        if (before.done != task.done || before.dueAt != task.dueAt) { refresh(); return }
        if (before.done) {
            TaskActions.undoDone(this, task.id)
            refresh()
            return
        }
        TaskActions.done(this, task.id, task.dueAt)
        refresh()
        val msg = if (before.repeat == Repeat.NONE) "Completed: ${before.title}"
        else "Completed. Next: ${TaskAdapter.friendlyTime(TaskDb.get(this).get(task.id)?.dueAt ?: before.dueAt)}"
        showUndo(msg, before, undoesDone = true)
    }

    private fun deleteWithUndo(task: Task) {
        haptic()
        TaskActions.delete(this, task.id)
        refresh()
        showUndo("Deleted: ${task.title}", task)
    }

    private fun showUndo(msg: String, snapshot: Task, undoesDone: Boolean = false) {
        Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.bottom_nav)
            .setAction("Undo") {
                if (undoesDone) TaskDb.get(this).unlogLast(snapshot.id)
                AlarmScheduler.schedule(this, TaskDb.get(this).restore(snapshot))
                refresh()
            }
            .show()
    }

    /** Swipe right = Done (green), swipe left = Delete (red), like iOS swipe actions. */
    private fun attachSwipe() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val doneIcon = ContextCompat.getDrawable(this, R.drawable.ic_check_circle)!!.mutate()
        val deleteIcon = ContextCompat.getDrawable(this, R.drawable.ic_delete)!!.mutate()
        doneIcon.setTint(ContextCompat.getColor(this, R.color.white))
        deleteIcon.setTint(ContextCompat.getColor(this, R.color.white))

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (adapter.taskAt(vh.bindingAdapterPosition) == null) 0 else super.getSwipeDirs(rv, vh)

            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.4f

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                val t = adapter.taskAt(pos) ?: return
                adapter.notifyItemChanged(pos) // slide the row back before the list updates
                if (direction == ItemTouchHelper.LEFT) deleteWithUndo(t)
                else toggleDone(t)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
            ) {
                val t = adapter.taskAt(vh.bindingAdapterPosition)
                if (t != null && dX != 0f) {
                    val v = vh.itemView
                    val d = resources.displayMetrics.density
                    val left = dX > 0
                    paint.color = ContextCompat.getColor(this@MainActivity, if (left) R.color.ios_green else R.color.ios_red)
                    val r = 12 * d
                    val rect = if (left) RectF(v.left.toFloat(), v.top.toFloat(), v.left + dX + r, v.bottom.toFloat())
                    else RectF(v.right + dX - r, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
                    c.drawRoundRect(rect, r, r, paint)
                    val icon = if (left) doneIcon else deleteIcon
                    val size = (26 * d).toInt()
                    val margin = (22 * d).toInt()
                    val top = v.top + (v.height - size) / 2
                    val x = if (left) v.left + margin else v.right - margin - size
                    icon.setBounds(x, top, x + size, top + size)
                    if (kotlin.math.abs(dX) > margin + size) icon.draw(c)
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }).attachToRecyclerView(list)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_add)?.isVisible = !onSettings // "+" and search on task tabs only
        menu.findItem(R.id.menu_search)?.isVisible = !onSettings
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_search) { toggleSearch(); return true }
        if (item.itemId == R.id.menu_add) {
            startActivity(Intent(this, EditTaskActivity::class.java)); return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toast(msg: String) = updates.toast(msg)

    /** Fade + slide a page in when its tab is opened. */
    private fun animateIn(v: View) {
        v.alpha = 0f
        v.translationY = 24 * resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** Title and one-line summary in the top bar (null = keep the current text). */
    private fun setHeader(title: String?, subtitle: String?) {
        title?.let { findViewById<android.widget.TextView>(R.id.header_title).text = it }
        subtitle?.let { findViewById<android.widget.TextView>(R.id.header_subtitle).text = it }
    }

    /** 🔍 shows a search bar under the title; tapping it again closes and clears it. */
    private fun toggleSearch() {
        searchOpen = !searchOpen
        search.visibility = if (searchOpen) View.VISIBLE else View.GONE
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (searchOpen) {
            search.requestFocus()
            imm.showSoftInput(search, 0)
        } else {
            imm.hideSoftInputFromWindow(search.windowToken, 0)
            search.setText("")
        }
    }

    private fun clearCompleted() {
        val done = TaskDb.get(this).all().filter { it.done }
        if (done.isEmpty()) { toast(getString(R.string.msg_no_completed_tasks)); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_clear_completed))
            .setMessage("Remove ${done.size} completed task(s) from the list.")
            .setPositiveButton(getString(R.string.msg_clear)) { _, _ -> done.forEach { TaskActions.delete(this, it.id) }; refresh() }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show()
    }
}
