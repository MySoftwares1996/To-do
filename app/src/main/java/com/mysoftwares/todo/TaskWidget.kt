package com.mysoftwares.todo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/** Home screen widget: next pending tasks with a ✔ button to mark each done. */
class TaskWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, manager, id) // resized: show more or fewer rows
    }

    companion object {
        /** Redraw all widgets. Called whenever tasks change. */
        fun refresh(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, TaskWidget::class.java))
                ids.forEach { update(context, manager, it) }
            } catch (e: Exception) {
                Log.w("TaskWidget", "refresh failed", e)
            }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val heightDp = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
            val maxRows = ((heightDp - 70) / 46).coerceIn(1, 6)
            manager.updateAppWidget(id, build(context, maxRows))
        }

        private fun build(context: Context, maxRows: Int): RemoteViews {
            val now = System.currentTimeMillis()
            // Overdue first, then higher priority, then the nearest time.
            val pending = TaskDb.get(context).all().filter { !it.done }
                .sortedWith(compareByDescending<Task> { it.dueAt < now }.thenByDescending { it.priority.ordinal }.thenBy { it.dueAt })
            val overdue = pending.count { it.dueAt < now }

            val views = RemoteViews(context.packageName, R.layout.widget_tasks)
            views.setTextViewText(
                R.id.widget_count,
                when {
                    pending.isEmpty() -> "All done"
                    overdue > 0 -> "${pending.size} pending · $overdue overdue"
                    else -> "${pending.size} pending"
                }
            )
            views.setOnClickPendingIntent(R.id.widget_header, activity(context, 1, Intent(context, MainActivity::class.java)))
            views.setOnClickPendingIntent(R.id.widget_add, activity(context, 2, Intent(context, EditTaskActivity::class.java)))

            views.removeAllViews(R.id.widget_list)
            views.setViewVisibility(R.id.widget_empty, if (pending.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_list, if (pending.isEmpty()) View.GONE else View.VISIBLE)

            for (t in pending.take(maxRows)) {
                val row = RemoteViews(context.packageName, R.layout.widget_row)
                row.setTextViewText(R.id.row_title, if (t.priority.marks.isEmpty()) t.title else "${t.priority.marks} ${t.title}")
                row.setTextViewText(R.id.row_time, (if (t.dueAt < now) "Overdue · " else "") + TaskAdapter.friendlyTime(t.dueAt))
                val color = when {
                    t.dueAt < now -> R.color.danger
                    t.important -> R.color.warn
                    else -> R.color.brand
                }
                row.setTextColor(R.id.row_dot, ContextCompat.getColor(context, color))
                if (t.dueAt < now) row.setTextColor(R.id.row_time, ContextCompat.getColor(context, R.color.danger))
                row.setOnClickPendingIntent(
                    R.id.row,
                    activity(
                        context, 10_000 + t.id.toInt(),
                        Intent(context, EditTaskActivity::class.java).putExtra(AlarmScheduler.EXTRA_TASK_ID, t.id)
                    )
                )
                row.setOnClickPendingIntent(R.id.row_done, ActionReceiver.intent(context, t.id, ActionReceiver.ACTION_DONE, t.dueAt))
                views.addView(R.id.widget_list, row)
            }
            return views
        }

        private fun activity(context: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context, code,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
