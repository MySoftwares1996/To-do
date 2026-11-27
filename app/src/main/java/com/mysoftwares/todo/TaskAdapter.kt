package com.mysoftwares.todo

import android.content.res.ColorStateList
import android.graphics.Paint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Which tasks the list shows; chosen with the tiles at the top. */
enum class Filter(val label: String) {
    ALL("All"), TODAY("Today"), IMPORTANT("Important"), OVERDUE("Overdue")
}

/** A row in the list. */
sealed class Row {
    data class Banner(val problems: Int) : Row()
    data class Filters(val pending: Int, val overdue: Int, val filter: Filter) : Row()
    data class Header(val key: String, val title: String, val count: Int, val colorRes: Int?) : Row()
    data class Item(val task: Task, val first: Boolean, val last: Boolean) : Row()
    data class Empty(val text: String) : Row()
}

class TaskAdapter(
    private val onClick: (Task) -> Unit,
    private val onCheck: (Task) -> Unit,
    private val onFilter: (Filter) -> Unit,
    private val onBanner: () -> Unit,
    /** true = iOS inset grouped list; false = separate rounded cards. */
    private val grouped: Boolean = true,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val T_BANNER = 0
        private const val T_FILTERS = 1
        private const val T_HEADER = 2
        private const val T_TASK = 3
        private const val T_EMPTY = 4

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> a.key == b.key
                a is Row.Item && b is Row.Item -> a.task.id == b.task.id
                else -> a::class == b::class && a !is Row.Item && a !is Row.Header
            }
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }

        private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

        fun matches(t: Task, f: Filter, now: Long): Boolean = when (f) {
            Filter.ALL -> true
            Filter.TODAY -> !t.done && (t.dueAt < now || day(t.dueAt) == LocalDate.now())
            Filter.IMPORTANT -> !t.done && t.important
            Filter.OVERDUE -> !t.done && t.dueAt < now
        }

        fun buildRows(tasks: List<Task>, filter: Filter, problems: Int): List<Row> {
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val rows = mutableListOf<Row>()
            if (problems > 0) rows += Row.Banner(problems)
            rows += Row.Filters(
                pending = tasks.count { !it.done },
                overdue = tasks.count { matches(it, Filter.OVERDUE, now) },
                filter = filter,
            )

            data class Sec(val key: String, val title: String, val color: Int?, val items: MutableList<Task> = mutableListOf())
            val overdue = Sec("overdue", "Overdue", R.color.ios_red)
            val sToday = Sec("today", "Today", null)
            val sTomorrow = Sec("tomorrow", "Tomorrow", null)
            val sWeek = Sec("week", "This Week", null)
            val sLater = Sec("later", "Later", null)
            val sDone = Sec("done", "Completed", R.color.ios_secondary)
            val sections = listOf(overdue, sToday, sTomorrow, sWeek, sLater, sDone)

            for (t in tasks.filter { matches(it, filter, now) }) {
                val d = day(t.dueAt)
                when {
                    t.done -> sDone
                    t.dueAt < now -> overdue
                    d == today -> sToday
                    d == today.plusDays(1) -> sTomorrow
                    d.isBefore(today.plusDays(7)) -> sWeek
                    else -> sLater
                }.items.add(t)
            }
            var any = false
            for (s in sections) {
                if (s.items.isEmpty()) continue
                any = true
                val list = if (s === sDone) s.items.takeLast(30).reversed() else s.items.sortedBy { it.dueAt }
                rows += Row.Header(s.key, s.title, list.size, s.color)
                list.forEachIndexed { i, t -> rows += Row.Item(t, i == 0, i == list.lastIndex) }
            }
            if (!any) rows += Row.Empty(
                when (filter) {
                    Filter.ALL -> "No Tasks"
                    Filter.TODAY -> "Nothing due today"
                    Filter.IMPORTANT -> "No important tasks"
                    Filter.OVERDUE -> "Nothing overdue 🎉"
                }
            )
            return rows
        }

        /** "Today, 10:00 AM" / "Tomorrow, 9:30 AM" / "1 Dec 2026, 10:00 AM" */
        fun friendlyTime(ms: Long): String {
            val d = day(ms)
            val today = LocalDate.now()
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

    fun taskAt(position: Int): Task? =
        if (position in 0 until itemCount) (getItem(position) as? Row.Item)?.task else null

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Banner -> T_BANNER
        is Row.Filters -> T_FILTERS
        is Row.Header -> T_HEADER
        is Row.Item -> T_TASK
        is Row.Empty -> T_EMPTY
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            T_BANNER -> R.layout.item_banner
            T_FILTERS -> R.layout.item_filters
            T_HEADER -> R.layout.item_header
            T_EMPTY -> R.layout.item_empty
            else -> R.layout.item_task
        }
        return VH(inf.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val row = getItem(position)) {
            is Row.Banner -> {
                (v as TextView).text = v.context.getString(R.string.banner_problems, row.problems)
                v.setOnClickListener { onBanner() }
            }
            is Row.Filters -> bindFilters(v, row)
            is Row.Header -> {
                val t = v.findViewById<TextView>(R.id.header)
                t.text = row.title
                t.setTextColor(ContextCompat.getColor(v.context, row.colorRes ?: R.color.ios_label))
                v.findViewById<TextView>(R.id.header_count).text = row.count.toString()
            }
            is Row.Empty -> v.findViewById<TextView>(R.id.empty_title).text = row.text
            is Row.Item -> bindTask(v, row)
        }
    }

    private fun bindFilters(v: View, row: Row.Filters) {
        val ctx = v.context
        v.findViewById<TextView>(R.id.summary).text = when {
            row.pending == 0 -> "All done 🎉"
            row.overdue > 0 -> "${row.pending} pending · ${row.overdue} overdue"
            else -> "${row.pending} pending"
        }
        val box = v.findViewById<LinearLayout>(R.id.chips)
        box.removeAllViews()
        val accent = MaterialColors.getColor(v, androidx.appcompat.R.attr.colorPrimary)
        val cell = ContextCompat.getColor(ctx, R.color.ios_cell)
        val label = ContextCompat.getColor(ctx, R.color.ios_label)
        val gap = (8 * ctx.resources.displayMetrics.density).toInt()
        Filter.entries.forEach { f ->
            val selected = f == row.filter
            box.addView(Chip(ctx).apply {
                text = f.label
                isCheckable = false
                chipBackgroundColor = ColorStateList.valueOf(if (selected) accent else cell)
                setTextColor(if (selected) ContextCompat.getColor(ctx, R.color.white) else label)
                chipStrokeWidth = 0f
                setOnClickListener { onFilter(f) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
            })
        }
    }

    private fun bindTask(v: View, row: Row.Item) {
        val t = row.task
        val ctx = v.context
        val now = System.currentTimeMillis()
        val overdue = !t.done && t.dueAt < now

        // Rounded corners only at the top/bottom of each group (iOS inset grouped list).
        val bg = when {
            !grouped || (row.first && row.last) -> R.drawable.bg_cell_single
            row.first -> R.drawable.bg_cell_top
            row.last -> R.drawable.bg_cell_bottom
            else -> R.drawable.bg_cell_middle
        }
        v.setBackgroundResource(bg)
        (v.layoutParams as? RecyclerView.LayoutParams)?.let {
            val m = if (grouped) 0 else (5 * ctx.resources.displayMetrics.density).toInt()
            it.topMargin = m; it.bottomMargin = m
            v.layoutParams = it
        }
        v.findViewById<View>(R.id.divider).visibility = if (grouped && !row.last) View.VISIBLE else View.GONE

        val title = v.findViewById<TextView>(R.id.title)
        title.text = t.title
        title.paintFlags = if (t.done) title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        title.setTextColor(ContextCompat.getColor(ctx, if (t.done) R.color.ios_secondary else R.color.ios_label))

        val sub = v.findViewById<TextView>(R.id.sub)
        val relative = DateUtils.getRelativeTimeSpanString(t.dueAt, now, DateUtils.MINUTE_IN_MILLIS)
        sub.text = if (t.done) friendlyTime(t.dueAt) else "${friendlyTime(t.dueAt)} · $relative"
        sub.setTextColor(ContextCompat.getColor(ctx, if (overdue) R.color.ios_red else R.color.ios_secondary))

        val extras = mutableListOf<String>()
        if (t.repeat != Repeat.NONE) extras += "↻ " + t.repeat.label
        if (t.snoozeUntil > 0 && !t.done) extras += "Snoozed to " + friendlyTime(t.snoozeUntil)
        if (t.notes.isNotBlank()) extras += t.notes.lineSequence().first()
        v.findViewById<TextView>(R.id.repeat).apply {
            text = extras.joinToString("  ·  ")
            visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<View>(R.id.badge).visibility = if (t.important && !t.done) View.VISIBLE else View.GONE

        val check = v.findViewById<ImageView>(R.id.check)
        check.setImageResource(if (t.done) R.drawable.ic_circle_checked else R.drawable.ic_circle_empty)
        check.setOnClickListener {
            // Fill the circle first, then let the row move – feels like iOS.
            check.setImageResource(if (t.done) R.drawable.ic_circle_empty else R.drawable.ic_circle_checked)
            check.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).withEndAction {
                check.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            check.postDelayed({ onCheck(t) }, 350)
        }
        v.setOnClickListener { onClick(t) }
    }
}
