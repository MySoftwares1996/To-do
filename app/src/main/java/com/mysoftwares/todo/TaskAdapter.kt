package com.mysoftwares.todo

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class TaskAdapter(
    private val onClick: (Task) -> Unit,
    private val onCheck: (Task, Boolean) -> Unit,
) : RecyclerView.Adapter<TaskAdapter.VH>() {
    private var items: List<Task> = emptyList()

    fun submit(list: List<Task>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        val ctx = h.itemView.context
        val overdue = !t.done && t.dueAt < System.currentTimeMillis()

        h.title.text = t.title
        h.title.paintFlags = if (t.done) h.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else h.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        h.itemView.alpha = if (t.done) 0.5f else 1f

        val parts = mutableListOf(Notifications.formatTime(t.dueAt))
        if (t.repeat != Repeat.NONE) parts += t.repeat.label
        if (t.snoozeUntil > 0 && !t.done) parts += "snoozed to " + Notifications.formatTime(t.snoozeUntil)
        h.sub.text = (if (overdue) "OVERDUE · " else "") + parts.joinToString(" · ")
        h.sub.setTextColor(ContextCompat.getColor(ctx, if (overdue) R.color.danger else R.color.text_secondary))

        h.badge.visibility = if (t.important && !t.done) View.VISIBLE else View.GONE

        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = t.done
        h.check.setOnCheckedChangeListener { _, checked -> onCheck(t, checked) }
        h.itemView.setOnClickListener { onClick(t) }
    }
}
