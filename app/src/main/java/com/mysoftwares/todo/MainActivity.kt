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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: TaskAdapter
    private lateinit var banner: TextView
    private lateinit var empty: TextView
    private var askedPermission = false

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
        if (item.itemId == R.id.menu_setup) {
            startActivity(Intent(this, SetupActivity::class.java)); return true
        }
        return super.onOptionsItemSelected(item)
    }
}
