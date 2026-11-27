package com.mysoftwares.todo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Checklist of everything that must be ON for alarms to ring reliably, with one-tap fixes. */
class SetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<Button>(R.id.test).setOnClickListener {
            val at = System.currentTimeMillis() + 60_000
            val t = TaskDb.get(this).save(Task(title = "Test alarm", notes = "If you can hear this, alarms work. Tap Done.", startAt = at, dueAt = at))
            AlarmScheduler.schedule(this, t)
            Toast.makeText(this, "Test alarm in 1 minute. Lock the phone now and wait.", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.app_info).setOnClickListener { Reliability.appDetails(this) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val box = findViewById<LinearLayout>(R.id.checks)
        box.removeAllViews()
        Reliability.checks(this).forEach { c ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_check, box, false)
            row.findViewById<TextView>(R.id.status).apply {
                text = if (c.ok) "✔" else "✖"
                setTextColor(ContextCompat.getColor(context, if (c.ok) R.color.ok else R.color.danger))
            }
            row.findViewById<TextView>(R.id.title).text = c.title
            row.findViewById<TextView>(R.id.explain).apply {
                text = c.explain
                visibility = if (c.ok) View.GONE else View.VISIBLE
            }
            row.findViewById<Button>(R.id.fix).apply {
                text = c.fixLabel
                visibility = if (c.ok) View.GONE else View.VISIBLE
                setOnClickListener { c.fix(this@SetupActivity); renderLater() }
            }
            box.addView(row)
        }
        val brand = Reliability.brandAdvice()
        findViewById<TextView>(R.id.brand).apply {
            text = brand ?: ""
            visibility = if (brand == null) View.GONE else View.VISIBLE
        }
    }

    // Volume fix happens in place, so refresh the list shortly after.
    private fun renderLater() { window.decorView.postDelayed({ if (!isFinishing) render() }, 400) }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
