package com.mysoftwares.todo

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Tasks are stored in *device-protected* storage so alarms can be re-armed and can
 * ring even right after a reboot, before the phone is unlocked.
 */
class TaskDb private constructor(private val appContext: Context) :
    SQLiteOpenHelper(appContext.createDeviceProtectedStorageContext(), "tasks.db", null, 3) {

    /** Keep the home screen widget in sync with every change. */
    private fun changed() {
        TaskWidget.refresh(appContext)
        Backup.scheduleAuto(appContext)
    }

    companion object {
        @Volatile private var instance: TaskDb? = null
        fun get(context: Context): TaskDb =
            instance ?: synchronized(this) {
                instance ?: TaskDb(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                start_at INTEGER NOT NULL,
                due_at INTEGER NOT NULL,
                repeat TEXT NOT NULL,
                important INTEGER NOT NULL,
                advance_min INTEGER NOT NULL,
                done INTEGER NOT NULL,
                snooze_until INTEGER NOT NULL,
                rang_for INTEGER NOT NULL,
                advance_shown_for INTEGER NOT NULL,
                workday TEXT NOT NULL DEFAULT 'SAME_DAY',
                advance_list TEXT NOT NULL DEFAULT '',
                remind_type TEXT NOT NULL DEFAULT 'ALARM',
                category TEXT NOT NULL DEFAULT ''
            )"""
        )
        createHistory(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE tasks ADD COLUMN workday TEXT NOT NULL DEFAULT 'SAME_DAY'")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN advance_list TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tasks ADD COLUMN remind_type TEXT NOT NULL DEFAULT 'ALARM'")
            db.execSQL("ALTER TABLE tasks ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE tasks SET advance_list = advance_min WHERE advance_min > 0")
            createHistory(db)
        }
    }

    /** One row per completed occurrence – used by the monthly report. */
    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                category TEXT NOT NULL,
                due_at INTEGER NOT NULL,
                done_at INTEGER NOT NULL
            )"""
        )
    }

    data class Done(val title: String, val category: String, val dueAt: Long, val doneAt: Long)

    @Synchronized
    fun logDone(t: Task, doneAt: Long) {
        writableDatabase.insert("history", null, ContentValues().apply {
            put("task_id", t.id); put("title", t.title); put("category", t.category)
            put("due_at", t.dueAt); put("done_at", doneAt)
        })
    }

    /** Removes the latest completion of a task (used by Undo). */
    @Synchronized
    fun unlogLast(taskId: Long) {
        writableDatabase.execSQL(
            "DELETE FROM history WHERE id = (SELECT MAX(id) FROM history WHERE task_id = ?)", arrayOf<Any>(taskId)
        )
    }

    /** Completions with done_at in [from, to). */
    @Synchronized
    fun history(from: Long, to: Long): List<Done> =
        readableDatabase.query(
            "history", null, "done_at >= ? AND done_at < ?", arrayOf(from.toString(), to.toString()),
            null, null, "done_at ASC"
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Done(
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getString(c.getColumnIndexOrThrow("category")),
                        c.getLong(c.getColumnIndexOrThrow("due_at")),
                        c.getLong(c.getColumnIndexOrThrow("done_at")),
                    )
                )
            }
        }

    @Synchronized
    fun all(): List<Task> =
        readableDatabase.query("tasks", null, null, null, null, null, "done ASC, due_at ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toTask()) }
        }

    @Synchronized
    fun get(id: Long): Task? =
        readableDatabase.query("tasks", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toTask() else null
        }

    /** Inserts (id == 0) or updates. Returns the saved task with its id. */
    @Synchronized
    fun save(t: Task): Task {
        val v = values(t)
        val saved = if (t.id == 0L) {
            t.copy(id = writableDatabase.insertOrThrow("tasks", null, v))
        } else {
            writableDatabase.update("tasks", v, "id=?", arrayOf(t.id.toString()))
            t
        }
        changed()
        return saved
    }

    /** Puts back an exact earlier copy of a task (used by Undo, also after delete). */
    @Synchronized
    fun restore(t: Task): Task {
        val v = values(t).apply { put("id", t.id) }
        writableDatabase.insertWithOnConflict("tasks", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
        return t
    }

    private fun values(t: Task) = ContentValues().apply {
            put("title", t.title)
            put("notes", t.notes)
            put("start_at", t.startAt)
            put("due_at", t.dueAt)
            put("repeat", t.repeat.name)
            put("important", if (t.important) 1 else 0)
            put("advance_min", t.advances.firstOrNull() ?: 0)
            put("advance_list", t.advances.joinToString(","))
            put("remind_type", t.remindType.name)
            put("category", t.category)
            put("done", if (t.done) 1 else 0)
            put("snooze_until", t.snoozeUntil)
            put("rang_for", t.rangFor)
            put("advance_shown_for", t.advanceShownFor)
            put("workday", t.workday.name)
        }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id=?", arrayOf(id.toString()))
        changed()
    }

    private fun Cursor.toTask() = Task(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        notes = getString(getColumnIndexOrThrow("notes")),
        startAt = getLong(getColumnIndexOrThrow("start_at")),
        dueAt = getLong(getColumnIndexOrThrow("due_at")),
        repeat = runCatching { Repeat.valueOf(getString(getColumnIndexOrThrow("repeat"))) }.getOrDefault(Repeat.NONE),
        important = getInt(getColumnIndexOrThrow("important")) == 1,
        advances = parseAdvances(getString(getColumnIndexOrThrow("advance_list")), getInt(getColumnIndexOrThrow("advance_min"))),
        remindType = runCatching { RemindType.valueOf(getString(getColumnIndexOrThrow("remind_type"))) }.getOrDefault(RemindType.ALARM),
        category = getString(getColumnIndexOrThrow("category")),
        done = getInt(getColumnIndexOrThrow("done")) == 1,
        snoozeUntil = getLong(getColumnIndexOrThrow("snooze_until")),
        rangFor = getLong(getColumnIndexOrThrow("rang_for")),
        advanceShownFor = getLong(getColumnIndexOrThrow("advance_shown_for")),
        workday = runCatching { WorkdayRule.valueOf(getString(getColumnIndexOrThrow("workday"))) }.getOrDefault(WorkdayRule.SAME_DAY),
    )

    private fun parseAdvances(list: String, single: Int): List<Int> {
        val parsed = list.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct().sorted()
        return parsed.ifEmpty { if (single > 0) listOf(single) else emptyList() }
    }
}
