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

        adapter = TaskAdapter(
            onClick = { startActivity(Intent(this, EditTaskActivity::class.java).putExtra(AlarmScheduler.EXTRA_TASK_ID, it.id)) },
            onCheck = { toggleDone(it) },
            onLongPress = { showTaskMenu(it) },
            onHistory = { h, longPress -> if (longPress) showHistoryMenu(h) else undoHistory(h) },
            onBanner = { open ->
                Reliability.markSeen(this) // don't show it again after updates
                if (open) startActivity(Intent(this, SetupActivity::class.java)) else refresh()
            },
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

    // ---- Settings tab (short list; details are one tap deeper) ----

    private fun refreshSettings() {
        val list = IosList(this, findViewById(R.id.settings_content))
        list.clear()
        val accent = com.google.android.material.color.MaterialColors.getColor(list.root, androidx.appcompat.R.attr.colorPrimary)
        val problems = Reliability.problemCount(this)

        // Alarm check stays on top so problems are seen at once
        list.spacer()
        list.group().row(
            getString(R.string.set_alarm_check), if (problems == 0) getString(R.string.set_all_set) else getString(R.string.set_to_fix, problems),
            dot = ContextCompat.getColor(this, if (problems == 0) R.color.ios_green else R.color.ios_red),
        ) { startActivity(Intent(this, SetupActivity::class.java)) }

        list.spacer()
        val main = list.group()
        main.row(getString(R.string.set_alarms_snooze), "›", dot = ContextCompat.getColor(this, R.color.ios_red)) {
            startActivity(Intent(this, AlarmSettingsActivity::class.java))
        }
        main.row(getString(R.string.set_workdays), "›", dot = ContextCompat.getColor(this, R.color.ios_orange)) {
            startActivity(Intent(this, WorkDaysActivity::class.java))
        }
        main.row(getString(R.string.appearance_title), "›", dot = accent) {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        list.spacer()
        val data = list.group()
        data.row(getString(R.string.backup_title), if (Backup.autoUri(this) != null) "On ›" else "Off ›",
            dot = ContextCompat.getColor(this, R.color.ios_indigo)) { startActivity(Intent(this, BackupActivity::class.java)) }
        data.row(getString(R.string.msg_monthly_report), "›", dot = ContextCompat.getColor(this, R.color.ios_green)) {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        data.row(getString(R.string.msg_check_for_updates), Updater.currentVersionName + " ›",
            dot = ContextCompat.getColor(this, R.color.ios_gray)) { updates.checkForUpdate(manual = true) }

        list.spacer()
        list.group().row(getString(R.string.set_test_alarm_button), titleColor = accent, centered = true) { testAlarm() }
        list.spacer()
        list.group().row(getString(R.string.msg_clear_completed), titleColor = ContextCompat.getColor(this, R.color.ios_red), centered = true) {
            clearCompleted()
        }
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
        // Ask for the notification permission once (at install), not after every update.
        val asked = getSharedPreferences("main", MODE_PRIVATE)
        if (!askedPermission && !asked.getBoolean("asked_notifications", false) && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedPermission = true
            asked.edit().putBoolean("asked_notifications", true).apply()
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
        // Completed dates of repeating tasks (last 60 days, since the last "Clear completed").
        val history = TaskDb.get(this).recentHistory(
            maxOf(AlarmPrefs.doneClearedAt(this), System.currentTimeMillis() - 60L * 24 * 3600_000)
        )
        adapter.submitList(TaskAdapter.buildRows(tasks, tab, Reliability.newProblemCount(this), query, history))
        if (!onSettings) setHeader(null, TaskAdapter.summary(tasks, tab, query, history))
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

    /** Undo one completed date of a repeating task (only the newest one). */
    private fun undoHistory(h: TaskDb.Done) {
        haptic()
        if (!TaskActions.undoHistory(this, h)) toast(getString(R.string.done_undo_only_latest))
        refresh()
    }

    private fun showHistoryMenu(h: TaskDb.Done) {
        haptic()
        val items = arrayOf(getString(R.string.menu_mark_not_done), getString(R.string.done_open_task))
        AlertDialog.Builder(this)
            .setTitle(h.title)
            .setItems(items) { _, i ->
                if (i == 0) undoHistory(h)
                else startActivity(Intent(this, EditTaskActivity::class.java).putExtra(AlarmScheduler.EXTRA_TASK_ID, h.taskId))
            }
            .show()
    }

    /** Long-press menu on a task. */
    private fun showTaskMenu(task: Task) {
        haptic()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += (if (task.done) getString(R.string.menu_mark_not_done) else getString(R.string.menu_mark_done)) to { toggleDone(task) }
        if (!task.done) actions += getString(R.string.menu_snooze_hour) to {
            TaskActions.snooze(this, task.id, 60); refresh()
        }
        actions += getString(R.string.menu_duplicate) to {
            // Same settings, new task; the user can change the date afterwards.
            // A copy of a past task must not ring straight away: repeating tasks move to their next
            // date; one-time tasks keep their date but are marked as already rung (edit to change it).
            val now = System.currentTimeMillis()
            val nextDue = if (task.dueAt > now) task.dueAt
            else Recurrence.nextAfter(task.startAt, task.repeat, now, task.workday, task.endOffset) ?: task.dueAt
            val copy = TaskDb.get(this).save(
                task.copy(
                    id = 0, title = getString(R.string.menu_copy_suffix, task.title), done = false,
                    dueAt = nextDue, snoozeUntil = 0, advanceShownFor = 0, autoRepeat = false,
                    rangFor = if (nextDue <= now) nextDue else 0,
                )
            )
            AlarmScheduler.schedule(this, copy)
            refresh()
            toast(getString(R.string.menu_duplicated))
        }
        actions += getString(R.string.menu_delete) to { deleteWithUndo(task) }
        AlertDialog.Builder(this)
            .setTitle(task.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
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
                if (adapter.taskAt(vh.bindingAdapterPosition) == null || adapter.isHistory(vh.bindingAdapterPosition)) 0
                else super.getSwipeDirs(rv, vh)

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
        val tasks = TaskDb.get(this).all()
        val done = tasks.filter { it.done }
        val shown = TaskAdapter.doneItems(tasks, TaskDb.get(this).recentHistory(
            maxOf(AlarmPrefs.doneClearedAt(this), System.currentTimeMillis() - 60L * 24 * 3600_000)
        )).size
        if (shown == 0) { toast(getString(R.string.msg_no_completed_tasks)); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_clear_completed))
            .setMessage(getString(R.string.done_clear_msg, shown))
            .setPositiveButton(getString(R.string.msg_clear)) { _, _ ->
                // Repeating tasks stay (they are on their next date); their past dates leave the list
                // but stay in the monthly report.
                done.forEach { TaskActions.delete(this, it.id) }
                AlarmPrefs.setDoneClearedAt(this, System.currentTimeMillis())
                refresh()
            }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show()
    }
}
