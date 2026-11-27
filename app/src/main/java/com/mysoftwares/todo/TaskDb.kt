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
class TaskDb private constructor(context: Context) :
    SQLiteOpenHelper(context.createDeviceProtectedStorageContext(), "tasks.db", null, 1) {

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
                advance_shown_for INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
        val v = ContentValues().apply {
            put("title", t.title)
            put("notes", t.notes)
            put("start_at", t.startAt)
            put("due_at", t.dueAt)
            put("repeat", t.repeat.name)
            put("important", if (t.important) 1 else 0)
            put("advance_min", t.advanceMinutes)
            put("done", if (t.done) 1 else 0)
            put("snooze_until", t.snoozeUntil)
            put("rang_for", t.rangFor)
            put("advance_shown_for", t.advanceShownFor)
        }
        return if (t.id == 0L) {
            t.copy(id = writableDatabase.insertOrThrow("tasks", null, v))
        } else {
            writableDatabase.update("tasks", v, "id=?", arrayOf(t.id.toString()))
            t
        }
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id=?", arrayOf(id.toString()))
    }

    private fun Cursor.toTask() = Task(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        notes = getString(getColumnIndexOrThrow("notes")),
        startAt = getLong(getColumnIndexOrThrow("start_at")),
        dueAt = getLong(getColumnIndexOrThrow("due_at")),
        repeat = runCatching { Repeat.valueOf(getString(getColumnIndexOrThrow("repeat"))) }.getOrDefault(Repeat.NONE),
        important = getInt(getColumnIndexOrThrow("important")) == 1,
        advanceMinutes = getInt(getColumnIndexOrThrow("advance_min")),
        done = getInt(getColumnIndexOrThrow("done")) == 1,
        snoozeUntil = getLong(getColumnIndexOrThrow("snooze_until")),
        rangFor = getLong(getColumnIndexOrThrow("rang_for")),
        advanceShownFor = getLong(getColumnIndexOrThrow("advance_shown_for")),
    )
}
