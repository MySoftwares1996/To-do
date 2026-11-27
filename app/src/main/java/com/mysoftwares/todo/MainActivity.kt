package com.mysoftwares.todo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.concurrent.Executors
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: TaskAdapter
    private lateinit var banner: TextView
    private lateinit var empty: TextView
    private var askedPermission = false
    private val io = Executors.newSingleThreadExecutor()
    /** Downloaded update waiting for the user to allow "Install unknown apps". */
    private var pendingApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        banner = findViewById(R.id.banner)
        empty = findViewById(R.id.empty)
        banner.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        adapter = TaskAdapter(
            onClick = { startActivity(Intent(this, EditTaskActivity::class.java).putExtra(AlarmScheduler.EXTRA_TASK_ID, it.id)) },
            onCheck = { task, checked ->
                if (checked) {
                    TaskActions.done(this, task.id)
                    val msg = if (task.repeat == Repeat.NONE) "Done: ${task.title}" else "Done – next one scheduled"
                    Snackbar.make(findViewById(R.id.list), msg, Snackbar.LENGTH_SHORT).show()
                } else TaskActions.undoDone(this, task.id)
                refresh()
            }
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.add).setOnClickListener {
            startActivity(Intent(this, EditTaskActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Self-healing: every time the app is opened, all alarms are re-armed.
        AlarmScheduler.rescheduleAll(this)
        refresh()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val tasks = TaskDb.get(this).all()
        adapter.submit(tasks)
        empty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        val problems = Reliability.problemCount(this)
        banner.visibility = if (problems > 0) View.VISIBLE else View.GONE
        banner.text = getString(R.string.banner_problems, problems)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_setup -> { startActivity(Intent(this, SetupActivity::class.java)); return true }
            R.id.menu_update -> { checkForUpdate(manual = true); return true }
        }
        return super.onOptionsItemSelected(item)
    }
}
