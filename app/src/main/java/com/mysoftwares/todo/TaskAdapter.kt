package com.mysoftwares.todo

import android.graphics.Paint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** A row in the list: either a section header or a task. */
sealed class Row {
    data class Header(val title: String, val colorRes: Int?) : Row()
    data class Item(val task: Task) : Row()
}

class TaskAdapter(
    private val onClick: (Task) -> Unit,
    private val onCheck: (Task) -> Unit,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TASK = 1

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> a.title == b.title
                a is Row.Item && b is Row.Item -> a.task.id == b.task.id
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }

        /** Groups tasks into Overdue / Today / Tomorrow / This week / Later / Completed. */
        fun buildRows(tasks: List<Task>): List<Row> {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

            val groups = linkedMapOf<Row.Header, MutableList<Task>>()
            val overdue = Row.Header("Overdue", R.color.danger)
            val hToday = Row.Header("Today", null)
            val hTomorrow = Row.Header("Tomorrow", null)
            val hWeek = Row.Header("This week", null)
            val hLater = Row.Header("Later", null)
            val hDone = Row.Header("Completed", R.color.muted)
            listOf(overdue, hToday, hTomorrow, hWeek, hLater, hDone).forEach { groups[it] = mutableListOf() }

            for (t in tasks) {
                val d = day(t.dueAt)
                val h = when {
                    t.done -> hDone
                    t.dueAt < now -> overdue
                    d == today -> hToday
                    d == today.plusDays(1) -> hTomorrow
                    d.isBefore(today.plusDays(7)) -> hWeek
                    else -> hLater
                }
                groups.getValue(h).add(t)
            }
            val rows = mutableListOf<Row>()
            for ((h, list) in groups) {
                if (list.isEmpty()) continue
                rows += h.copy(title = "${h.title}  ·  ${list.size}")
                val sorted = if (h == hDone) list.takeLast(30).reversed() else list.sortedBy { it.dueAt }
                sorted.forEach { rows += Row.Item(it) }
            }
            return rows
        }

        /** "Today, 10:00 AM" / "Tomorrow, 9:30 AM" / "Mon, 1 Dec, 10:00 AM" */
        fun friendlyTime(ms: Long): String {
            val zone = ZoneId.systemDefault()
            val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val today = LocalDate.now(zone)
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
            val dayText = when (d) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                today.minusDays(1) -> "Yesterday"
                else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
            }
            return "$dayText, $time"
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.header)
    }

    class TaskVH(v: View) : RecyclerView.ViewHolder(v) {
        val stripe: View = v.findViewById(R.id.stripe)
        val check: MaterialCheckBox = v.findViewById(R.id.check)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val repeat: TextView = v.findViewById(R.id.repeat)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is Row.Header) TYPE_HEADER else TYPE_TASK

    fun taskAt(position: Int): Task? = (getItem(position) as? Row.Item)?.task

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderVH(inf.inflate(R.layout.item_header, parent, false))
        else TaskVH(inf.inflate(R.layout.item_task, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> {
                val h = holder as HeaderVH
                h.text.text = row.title
                h.text.setTextColor(
                    row.colorRes?.let { ContextCompat.getColor(h.text.context, it) }
                        ?: MaterialColors.getColor(h.text, androidx.appcompat.R.attr.colorPrimary)
                )
            }
            is Row.Item -> bindTask(holder as TaskVH, row.task)
        }
    }

    private fun bindTask(h: TaskVH, t: Task) {
        val ctx = h.itemView.context
        val now = System.currentTimeMillis()
        val overdue = !t.done && t.dueAt < now

        h.title.text = t.title
        h.title.paintFlags = if (t.done) h.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else h.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        h.itemView.alpha = if (t.done) 0.55f else 1f

        val relative = DateUtils.getRelativeTimeSpanString(t.dueAt, now, DateUtils.MINUTE_IN_MILLIS)
        h.sub.text = if (t.done) friendlyTime(t.dueAt) else "${friendlyTime(t.dueAt)}  ·  $relative"
        h.sub.setTextColor(
            if (overdue) ContextCompat.getColor(ctx, R.color.danger)
            else MaterialColors.getColor(h.sub, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )

        val extras = mutableListOf<String>()
        if (t.repeat != Repeat.NONE) extras += "↻ " + t.repeat.label
        if (t.snoozeUntil > 0 && !t.done) extras += "⏰ Snoozed to " + friendlyTime(t.snoozeUntil)
        h.repeat.text = extras.joinToString("   ")
        h.repeat.visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE

        h.badge.visibility = if (t.important && !t.done) View.VISIBLE else View.GONE
        h.stripe.setBackgroundColor(
            ContextCompat.getColor(
                ctx, when {
                    t.done -> R.color.muted
                    overdue -> R.color.danger
                    t.important -> R.color.warn
                    else -> R.color.brand
                }
            )
        )

        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = t.done
        h.check.setOnCheckedChangeListener { _, _ -> onCheck(t) }
        h.itemView.setOnClickListener { onClick(t) }
    }
}
