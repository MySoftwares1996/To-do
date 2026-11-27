package com.mysoftwares.todo

import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** Settings → Monthly report: completed tasks for a month, shared as a CSV file (opens in Excel / Sheets). */
class ReportActivity : BaseActivity() {
    private lateinit var list: IosList
    private var month: YearMonth = YearMonth.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        title = "Monthly report"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = IosList(this, findViewById(R.id.content))
        savedInstanceState?.getString("month")?.let { month = YearMonth.parse(it) }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("month", month.toString())
    }

    private fun range(m: YearMonth): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun render() {
        list.clear()
        list.label("Month")
        val g = list.group()
        val fmt = DateTimeFormatter.ofPattern("MMMM yyyy")
        (0L until 12L).map { YearMonth.now().minusMonths(it) }.forEach { m ->
            g.row(fmt.format(m), if (m == month) "✓" else "") { month = m; render() }
        }
        val (from, to) = range(month)
        val done = TaskDb.get(this).history(from, to)
        val late = done.count { it.doneAt > it.dueAt + 60 * 60_000L } // more than an hour after due
        list.footer("${done.size} tasks completed in ${fmt.format(month)}" + if (late > 0) " · $late completed late." else ".")
        list.group().row(
            "Share Excel (CSV) file", titleColor = ContextCompat.getColor(this, R.color.brand), centered = true
        ) { share(done) }
    }

    private fun share(done: List<TaskDb.Done>) {
        val dt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val csv = buildString {
            append("Task,Due,Completed,On time\r\n")
            done.forEach {
                append(listOf(
                    q(it.title), q(dt.format(Date(it.dueAt))), q(dt.format(Date(it.doneAt))),
                    if (it.doneAt <= it.dueAt + 60 * 60_000L) "Yes" else "No",
                ).joinToString(",")).append("\r\n")
            }
        }
        val dir = File(cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "OfficeToDo-${DateTimeFormatter.ofPattern("MMM-yyyy").format(month)}.csv")
        file.writeText("﻿" + csv) // BOM so Excel shows ₹ and other symbols correctly
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Office To-Do report ${DateTimeFormatter.ofPattern("MMMM yyyy").format(month)}")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share report"
        ))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
