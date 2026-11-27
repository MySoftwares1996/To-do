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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.concurrent.Executors

class MainActivity : BaseActivity() {
    override val wrapWithToolbar = false

    private lateinit var adapter: TaskAdapter
    private lateinit var list: RecyclerView
    private lateinit var collapsing: CollapsingToolbarLayout
    private var filter = Filter.ALL
    private var askedPermission = false
    private val io = Executors.newSingleThreadExecutor()
    /** Downloaded update waiting for the user to allow "Install unknown apps". */
    private var pendingApk: File? = null
    private val grouped by lazy { Appearance.listStyle(this) == Appearance.ListStyle.COMPACT }

    /** Refresh once a minute so "in 5 minutes" / overdue colours stay current. */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ticker.postDelayed(this, 60_000) }
    }

    private val backToAll = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFilter(Filter.ALL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val bar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(bar)
        supportActionBar?.setDisplayShowTitleEnabled(false) // the large collapsing title is used instead
        collapsing = findViewById(R.id.collapsing)
        list = findViewById(R.id.list)
        filter = savedInstanceState?.getString("filter")?.let { runCatching { Filter.valueOf(it) }.getOrNull() } ?: Filter.ALL
        onBackPressedDispatcher.addCallback(this, backToAll)

        findViewById<View>(R.id.add).setOnClickListener { startActivity(Intent(this, EditTaskActivity::class.java)) }

        adapter = TaskAdapter(
            onClick = { startActivity(Intent(this, EditTaskActivity::class.java).putExtra(AlarmScheduler.EXTRA_TASK_ID, it.id)) },
            onCheck = { toggleDone(it) },
            onFilter = { setFilter(it) },
            onBanner = { startActivity(Intent(this, SetupActivity::class.java)) },
            grouped = grouped,
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator?.changeDuration = 150
        attachSwipe()
        showTitle()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("filter", filter.name)
    }

    private fun setFilter(f: Filter) {
        filter = f
        showTitle()
        refresh()
    }

    private fun showTitle() {
        collapsing.title = getString(R.string.app_name)
        backToAll.isEnabled = filter != Filter.ALL // Back returns to "All" first
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
        pendingApk?.let {
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
                pendingApk = null
                Updater.install(this, it)
            }
        }
        if (Updater.autoCheckDue(this)) checkForUpdate(manual = false)
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
        adapter.submitList(TaskAdapter.buildRows(tasks, filter, Reliability.problemCount(this)))
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
        if (before.done) {
            TaskActions.undoDone(this, task.id)
            refresh()
            return
        }
        TaskActions.done(this, task.id)
        refresh()
        val msg = if (before.repeat == Repeat.NONE) "Completed: ${before.title}"
        else "Completed. Next: ${TaskAdapter.friendlyTime(TaskDb.get(this).get(task.id)?.dueAt ?: before.dueAt)}"
        showUndo(msg, before)
    }

    private fun deleteWithUndo(task: Task) {
        haptic()
        TaskActions.delete(this, task.id)
        refresh()
        showUndo("Deleted: ${task.title}", task)
    }

    private fun showUndo(msg: String, snapshot: Task) {
        Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.bottom_bar)
            .setAction("Undo") {
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

    // ---- Over-the-air updates ----

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun checkForUpdate(manual: Boolean) {
        if (manual) toast("Checking for updates…")
        io.execute {
            val rel = runCatching { Updater.fetchLatest() }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    rel == null -> if (manual) toast("Could not check for updates. Please check your internet connection.")
                    rel.versionCode > Updater.currentVersionCode -> showUpdateDialog(rel)
                    manual -> toast("You have the latest version (${Updater.currentVersionName}).")
                }
            }
        }
    }

    private fun showUpdateDialog(rel: Updater.Release) {
        val notes = rel.notes.trim().take(600)
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(
                "Version ${rel.versionName} is ready (you have ${Updater.currentVersionName}).\n" +
                    "Your tasks and alarms are kept." + if (notes.isNotEmpty()) "\n\nWhat's new:\n$notes" else ""
            )
            .setPositiveButton("Update now") { _, _ -> downloadAndInstall(rel) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            isIndeterminate = false
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Downloading update…")
            .setView(bar)
            .setCancelable(false)
            .show()
        io.execute {
            val result = runCatching {
                Updater.download(this, rel) { p -> runOnUiThread { bar.setProgressCompat(p, true) } }
            }
            runOnUiThread {
                if (!isDestroyed) dialog.dismiss()
                result.onSuccess { apk ->
                    if (!Updater.install(this, apk)) {
                        pendingApk = apk
                        toast("Allow 'Install unknown apps' for Office To-Do, then press Back to continue.")
                    }
                }.onFailure { toast("Update failed: ${it.message}") }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_setup -> { startActivity(Intent(this, SetupActivity::class.java)); return true }
            R.id.menu_update -> { checkForUpdate(manual = true); return true }
            R.id.menu_appearance -> { startActivity(Intent(this, AppearanceActivity::class.java)); return true }
            R.id.menu_backup -> { startActivity(Intent(this, BackupActivity::class.java)); return true }
            R.id.menu_clear -> { clearCompleted(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun clearCompleted() {
        val done = TaskDb.get(this).all().filter { it.done }
        if (done.isEmpty()) { toast("No completed tasks."); return }
        AlertDialog.Builder(this)
            .setTitle("Clear completed?")
            .setMessage("Remove ${done.size} completed task(s) from the list.")
            .setPositiveButton("Clear") { _, _ -> done.forEach { TaskActions.delete(this, it.id) }; refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
