package com.mysoftwares.todo

import android.graphics.Paint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** The task tabs at the bottom of the main screen (Settings is a separate page). */
enum class Tab(private val titleRes: Int) {
    TODAY(R.string.ui_today), UPCOMING(R.string.ui_upcoming), DONE(R.string.ui_done_2);

    val title: String get() = Res.s(titleRes)
}

/** A row in the list. */
sealed class Row {
    data class Banner(val problems: Int) : Row()
    data class Summary(val text: String) : Row()
    data class Header(val key: String, val title: String, val count: Int, val colorRes: Int?) : Row()
    /** [history] is set for a completed date of a repeating task; [task] then shows that date. */
    data class Item(val task: Task, val first: Boolean, val last: Boolean, val history: TaskDb.Done? = null, val nextDue: Long = 0) : Row()
    data class Empty(val text: String) : Row()
}

class TaskAdapter(
    private val onClick: (Task) -> Unit,
    private val onCheck: (Task) -> Unit,
    /** Long-press: Mark done / Snooze / Duplicate / Delete menu. */
    private val onLongPress: (Task) -> Unit,
    /** Banner tapped (open = true) or closed with ✕ (open = false). */
    private val onBanner: (open: Boolean) -> Unit,
    /** Check circle / long-press on a completed date of a repeating task. */
    private val onHistory: (TaskDb.Done, longPress: Boolean) -> Unit = { _, _ -> },
    /** true = iOS inset grouped list; false = separate rounded cards. */
    private val grouped: Boolean = true,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val T_BANNER = 0
        private const val T_SUMMARY = 1
        private const val T_HEADER = 2
        private const val T_TASK = 3
        private const val T_EMPTY = 4

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> a.key == b.key
                a is Row.Item && b is Row.Item -> a.task.id == b.task.id && a.history?.id == b.history?.id
                else -> a::class == b::class && a !is Row.Item && a !is Row.Header
            }
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }

        private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()


        /**
         * Done tab entries: finished one-time tasks plus each completed date of a repeating
         * task (from the history), newest first.
         */
        fun doneItems(tasks: List<Task>, history: List<TaskDb.Done>): List<Row.Item> {
            val byId = tasks.associateBy { it.id }
            val oneTime = tasks.filter { it.done }.map { Row.Item(it, false, false) to it.dueAt }
            val repeating = history.mapNotNull { h ->
                val live = byId[h.taskId] ?: return@mapNotNull null // task deleted
                if (live.done || live.repeat == Repeat.NONE) return@mapNotNull null // shown as a one-time task
                val shown = live.copy(
                    dueAt = h.dueAt, done = true, snoozeUntil = 0,
                    checklist = live.checklist.map { it.copy(done = true) },
                )
                Row.Item(shown, false, false, history = h, nextDue = live.dueAt) to h.doneAt
            }
            return (oneTime + repeating).sortedByDescending { it.second }.take(50).map { it.first }
        }

        fun buildRows(tasks: List<Task>, tab: Tab, problems: Int, query: String = "", history: List<TaskDb.Done> = emptyList()): List<Row> {
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val rows = mutableListOf<Row>()
            val q = query.trim()
            if (q.isNotEmpty()) {
                // Search covers all tasks (pending first, then completed), whatever the tab.
                val hits = tasks.filter {
                    it.title.contains(q, true) || it.notes.contains(q, true)
                }.sortedWith(compareBy<Task> { it.done }.thenByDescending { it.priority.ordinal }.thenBy { it.dueAt })
                if (hits.isNotEmpty()) {
                    rows += Row.Header("results", Res.s(R.string.sec_results), hits.size, null)
                    hits.forEachIndexed { i, t -> rows += Row.Item(t, i == 0, i == hits.lastIndex) }
                } else rows += Row.Empty(Res.f(R.string.empty_search, q))
                return rows
            }
            if (problems > 0 && tab == Tab.TODAY) rows += Row.Banner(problems)

            fun section(key: String, title: String, color: Int?, list: List<Task>) {
                if (list.isEmpty()) return
                rows += Row.Header(key, title, list.size, color)
                list.forEachIndexed { i, t -> rows += Row.Item(t, i == 0, i == list.lastIndex) }
            }
            // Within each section: higher priority first, then by time.
            val pending = tasks.filter { !it.done }
                .sortedWith(compareByDescending<Task> { it.priority.ordinal }.thenBy { it.dueAt })
            when (tab) {
                Tab.TODAY -> {
                    val overdue = pending.filter { it.dueAt < now }
                    val due = pending.filter { it.dueAt >= now && day(it.dueAt) == today }
                    section("overdue", Res.s(R.string.sec_overdue), R.color.ios_red, overdue)
                    section("today", Res.s(R.string.ui_today), null, due)
                    if (overdue.isEmpty() && due.isEmpty()) rows += Row.Empty(Res.s(R.string.empty_today))
                }
                Tab.UPCOMING -> {
                    val future = pending.filter { it.dueAt >= now && day(it.dueAt).isAfter(today) }
                    section("tomorrow", Res.s(R.string.sec_tomorrow), null, future.filter { day(it.dueAt) == today.plusDays(1) })
                    section("week", Res.s(R.string.sec_week), null, future.filter {
                        val d = day(it.dueAt); d.isAfter(today.plusDays(1)) && d.isBefore(today.plusDays(7))
                    })
                    section("later", Res.s(R.string.sec_later), null, future.filter { !day(it.dueAt).isBefore(today.plusDays(7)) })
                    if (future.isEmpty()) rows += Row.Empty(Res.s(R.string.empty_upcoming))
                }
                Tab.DONE -> {
                    val done = doneItems(tasks, history)
                    if (done.isNotEmpty()) {
                        rows += Row.Header("done", Res.s(R.string.sec_completed), done.size, R.color.ios_secondary)
                        done.forEachIndexed { i, it -> rows += it.copy(first = i == 0, last = i == done.lastIndex) }
                    } else rows += Row.Empty(Res.s(R.string.empty_done))
                }
            }
            return rows
        }

        /** One-line summary shown under the title, e.g. "Fri, 27 Nov · 5 to do · 1 overdue". */
        fun summary(tasks: List<Task>, tab: Tab, query: String, history: List<TaskDb.Done> = emptyList()): String {
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val q = query.trim()
            if (q.isNotEmpty()) {
                val n = tasks.count { it.title.contains(q, true) || it.notes.contains(q, true) }
                return if (n == 0) Res.s(R.string.sum_no_results) else Res.f(R.string.sum_found, n)
            }
            val pending = tasks.filter { !it.done }
            val overdue = pending.count { it.dueAt < now }
            return when (tab) {
                Tab.TODAY -> {
                    val due = pending.count { it.dueAt >= now && day(it.dueAt) == today }
                    val date = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM").format(today)
                    Res.f(R.string.sum_today, date, overdue + due) + if (overdue > 0) Res.f(R.string.sum_overdue, overdue) else ""
                }
                Tab.UPCOMING -> Res.f(R.string.sum_scheduled, pending.count { it.dueAt >= now && day(it.dueAt).isAfter(today) })
                Tab.DONE -> {
                    val n = doneItems(tasks, history).size
                    if (n == 0) Res.s(R.string.sum_no_done) else Res.f(R.string.sum_last_done, n)
                }
            }
        }

        /** Right-hand time column: "10:00 AM\nin 2 h", "Yesterday\n6:00 PM", "30 Nov\n6:00 PM". */
        fun timeColumn(t: Task, now: Long): String {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.dueAt))
            val d = day(t.dueAt)
            val today = LocalDate.now()
            if (!t.done && d == today && t.dueAt >= now) {
                val mins = (t.dueAt - now) / 60_000
                val rel = when {
                    mins < 1 -> Res.s(R.string.rel_now)
                    mins < 60 -> Res.f(R.string.rel_min, mins)
                    else -> Res.f(R.string.rel_h, mins / 60)
                }
                return "$time\n$rel"
            }
            val dayText = when (d) {
                today -> Res.s(R.string.ui_today)
                today.plusDays(1) -> Res.s(R.string.sec_tomorrow)
                today.minusDays(1) -> Res.s(R.string.day_yesterday)
                else -> java.time.format.DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yy").format(d)
            }
            return "$dayText\n$time"
        }

        /** "Today, 10:00 AM" / "Tomorrow, 9:30 AM" / "1 Dec 2026, 10:00 AM" */
        fun friendlyTime(ms: Long): String {
            val d = day(ms)
            val today = LocalDate.now()
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
            val dayText = when (d) {
                today -> Res.s(R.string.ui_today)
                today.plusDays(1) -> Res.s(R.string.sec_tomorrow)
                today.minusDays(1) -> Res.s(R.string.day_yesterday)
                else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
            }
            return "$dayText, $time"
        }
    }

    fun taskAt(position: Int): Task? =
        if (position in 0 until itemCount) (getItem(position) as? Row.Item)?.task else null

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Banner -> T_BANNER
        is Row.Summary -> T_SUMMARY
        is Row.Header -> T_HEADER
        is Row.Item -> T_TASK
        is Row.Empty -> T_EMPTY
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            T_BANNER -> R.layout.item_banner
            T_SUMMARY -> R.layout.item_summary
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
                v.findViewById<TextView>(R.id.banner_text).text = v.context.getString(R.string.banner_problems, row.problems)
                v.setOnClickListener { onBanner(true) }
                v.findViewById<View>(R.id.banner_close).setOnClickListener { onBanner(false) }
            }
            is Row.Summary -> (v as TextView).text = row.text
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
        // Cards: each task floats on its own with a gap and a soft shadow. Grouped: one joined list.
        val d = ctx.resources.displayMetrics.density
        (v.layoutParams as? RecyclerView.LayoutParams)?.let {
            val m = if (grouped) 0 else (6 * d).toInt()
            it.topMargin = m; it.bottomMargin = m
            it.leftMargin = if (grouped) 0 else (2 * d).toInt(); it.rightMargin = it.leftMargin
            v.layoutParams = it
        }
        v.elevation = if (grouped) 0f else 3 * d
        v.findViewById<View>(R.id.divider).visibility = if (grouped && !row.last) View.VISIBLE else View.GONE

        val title = v.findViewById<TextView>(R.id.title)
        title.text = t.title
        title.paintFlags = if (t.done) title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        title.setTextColor(ContextCompat.getColor(ctx, if (t.done) R.color.ios_secondary else R.color.ios_label))

        // Time column: bold time, then "in 21 min" / date in the accent colour (red when overdue).
        val (line1, line2) = timeColumn(t, now).split("\n").let { it[0] to it.getOrElse(1) { "" } }
        val accent = MaterialColors.getColor(v, androidx.appcompat.R.attr.colorPrimary)
        val red = ContextCompat.getColor(ctx, R.color.ios_red)
        val gray = ContextCompat.getColor(ctx, R.color.ios_secondary)
        // For today: line1 = time, line2 = "in 2 h". Otherwise line1 = day, line2 = time.
        val todayStyle = !t.done && day(t.dueAt) == LocalDate.now() && t.dueAt >= now
        v.findViewById<TextView>(R.id.sub).apply {
            text = if (todayStyle) line1 else line2
            setTextColor(if (overdue) red else if (t.done) gray else ContextCompat.getColor(ctx, R.color.ios_label))
        }
        v.findViewById<TextView>(R.id.sub2).apply {
            text = if (todayStyle) line2 else line1
            setTextColor(if (overdue) red else if (t.done) gray else accent)
        }

        // Small coloured tags
        val tags = v.findViewById<ViewGroup>(R.id.tags)
        tags.removeAllViews()
        fun tag(text: String, color: Int) {
            tags.addView(TextView(ctx).apply {
                this.text = text
                textSize = 11f
                maxLines = 1
                setTextColor(color)
                val d = ctx.resources.displayMetrics.density
                setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (5 * d).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8 * d
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x26))
                }
            })
        }
        if (!t.done) {
            if (t.important) tag("⚠ Important", ContextCompat.getColor(ctx, R.color.ios_orange))
            if (t.snoozeUntil > 0) {
                val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.snoozeUntil))
                // Important tasks that already rang repeat automatically until Done.
                tag(if (t.autoRepeat) "⟳ $at" else "⏰ $at", accent) // ⟳ = automatic repeat, ⏰ = your snooze
            }
        }
        if (row.history != null && row.nextDue > 0) {
            val next = java.time.format.DateTimeFormatter.ofPattern("d MMM").format(day(row.nextDue))
            tag(ctx.getString(R.string.done_next_tag, next), accent)
        }
        if (t.checklist.isNotEmpty()) {
            val n = t.checklist.count { it.done }
            tag("☑ $n/${t.checklist.size}", if (n == t.checklist.size) ContextCompat.getColor(ctx, R.color.ios_green) else gray)
        }
        if (t.repeat != Repeat.NONE) tag(
            "↻ " + t.repeat.label + if (t.repeat.usesEndOffset() && t.endOffset > 0) " −${t.endOffset}" else "", gray
        )
        if (t.remindType == RemindType.NOTIFICATION) tag("🔔", gray)
        v.findViewById<View>(R.id.tags_scroll).apply {
            visibility = if (tags.childCount == 0) View.GONE else View.VISIBLE
            scrollTo(0, 0) // rows are reused
        }

        // First line of the notes
        v.findViewById<TextView>(R.id.repeat).apply {
            text = t.notes.lineSequence().firstOrNull().orEmpty()
            visibility = if (t.notes.isBlank()) View.GONE else View.VISIBLE
        }

        // Priority marks: !!! red, !! orange, ! grey
        v.findViewById<TextView>(R.id.badge).apply {
            text = t.priority.marks
            visibility = if (t.priority != Priority.NONE && !t.done) View.VISIBLE else View.GONE
            setTextColor(ContextCompat.getColor(ctx, when (t.priority) {
                Priority.HIGH -> R.color.ios_red
                Priority.MEDIUM -> R.color.ios_orange
                else -> R.color.ios_secondary
            }))
        }

        // rows are reused: undo any slide-away animation
        v.animate().cancel(); v.alpha = 1f; v.translationX = 0f

        val check = v.findViewById<ImageView>(R.id.check)
        check.setImageResource(if (t.done) R.drawable.ic_circle_checked else R.drawable.ic_circle_empty)
        check.isEnabled = true
        check.setOnClickListener {
            check.isEnabled = false // one tap only; re-enabled when the row is redrawn
            // Fill the circle first, then let the row move – feels like iOS.
            check.setImageResource(if (t.done) R.drawable.ic_circle_empty else R.drawable.ic_circle_checked)
            check.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).withEndAction {
                check.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            // Live animation: the row slides away and fades, then the list updates.
            v.animate().translationX(48 * ctx.resources.displayMetrics.density).alpha(0.25f)
                .setStartDelay(180).setDuration(260).start()
            check.postDelayed({ row.history?.let { onHistory(it, false) } ?: onCheck(t) }, 460)
        }
        v.setOnClickListener { onClick(t) }
        v.setOnLongClickListener { row.history?.let { onHistory(it, true) } ?: onLongPress(t); true }
    }
}
