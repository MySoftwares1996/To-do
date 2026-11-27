package com.mysoftwares.todo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Backup and restore of tasks as a JSON file the user picks (phone storage, Google Drive…).
 * After the first backup the same file is kept up to date automatically after every change.
 */
object Backup {
    private const val TAG = "Backup"
    private const val PREFS = "backup"
    private const val KEY_URI = "auto_uri"
    private const val KEY_LAST = "last_backup"
    private const val KEY_ERROR = "last_error"

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null

    // ---- JSON ----

    fun toJson(tasks: List<Task>): String {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("notes", t.notes)
                put("startAt", t.startAt)
                put("dueAt", t.dueAt)
                put("repeat", t.repeat.name)
                put("important", t.important)
                put("advanceMinutes", t.advances.firstOrNull() ?: 0) // older app versions
                put("advances", JSONArray(t.advances))
                put("remindType", t.remindType.name)
                put("category", t.category)
                put("done", t.done)
                put("snoozeUntil", t.snoozeUntil)
                put("rangFor", t.rangFor)
                put("advanceShownFor", t.advanceShownFor)
                put("workday", t.workday.name)
            })
        }
        return JSONObject().apply {
            put("app", "OfficeToDo")
            put("format", 1)
            put("exportedAt", System.currentTimeMillis())
            put("tasks", arr)
        }.toString(2)
    }

    /** Throws IllegalArgumentException if the file is not an Office To-Do backup. */
    fun fromJson(text: String): List<Task> {
        val root = try { JSONObject(text) } catch (e: Exception) {
            throw IllegalArgumentException("This file is not an Office To-Do backup.")
        }
        if (root.optString("app") != "OfficeToDo") throw IllegalArgumentException("This file is not an Office To-Do backup.")
        val arr = root.optJSONArray("tasks") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Task(
                id = o.optLong("id"),
                title = o.getString("title"),
                notes = o.optString("notes"),
                startAt = o.optLong("startAt", o.getLong("dueAt")),
                dueAt = o.getLong("dueAt"),
                repeat = runCatching { Repeat.valueOf(o.optString("repeat")) }.getOrDefault(Repeat.NONE),
                important = o.optBoolean("important", true),
                advances = o.optJSONArray("advances")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
                    ?: listOf(o.optInt("advanceMinutes")).filter { it > 0 },
                remindType = runCatching { RemindType.valueOf(o.optString("remindType")) }.getOrDefault(RemindType.ALARM),
                category = o.optString("category"),
                done = o.optBoolean("done"),
                snoozeUntil = o.optLong("snoozeUntil"),
                rangFor = o.optLong("rangFor"),
                advanceShownFor = o.optLong("advanceShownFor"),
                workday = runCatching { WorkdayRule.valueOf(o.optString("workday")) }.getOrDefault(WorkdayRule.SAME_DAY),
            )
        }
    }

    // ---- Files ----

    fun writeTo(context: Context, uri: Uri) {
        val json = toJson(TaskDb.get(context).all())
        val out = try { context.contentResolver.openOutputStream(uri, "wt") } catch (_: Exception) { null }
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("Cannot open the backup file")
        out.use { it.write(json.toByteArray()) }
    }

    fun readFrom(context: Context, uri: Uri): List<Task> {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: throw IllegalStateException("Cannot open the file")
        return fromJson(text)
    }

    /**
     * Restores tasks and re-arms their alarms.
     * replace = true: delete current tasks first. false: add only tasks not already present.
     * Returns the number of tasks restored.
     */
    fun restore(context: Context, tasks: List<Task>, replace: Boolean): Int {
        val db = TaskDb.get(context)
        val current = db.all()
        var count = 0
        if (replace) {
            current.forEach { AlarmScheduler.cancel(context, it.id); Notifications.cancel(context, it.id); db.delete(it.id) }
            tasks.forEach { db.save(it.copy(id = 0)); count++ }
        } else {
            val existing = current.map { it.title.trim().lowercase() to it.startAt }.toSet()
            tasks.filter { (it.title.trim().lowercase() to it.startAt) !in existing }
                .forEach { db.save(it.copy(id = 0)); count++ }
        }
        AlarmScheduler.rescheduleAll(context)
        return count
    }

    // ---- Automatic backup ----

    /** Remember this file and keep it updated after every change. */
    fun enableAuto(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not keep access to backup file", e)
        }
        prefs(context)?.edit()?.putString(KEY_URI, uri.toString())?.remove(KEY_ERROR)?.apply()
    }

    fun disableAuto(context: Context) {
        autoUri(context)?.let {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
        }
        prefs(context)?.edit()?.remove(KEY_URI)?.apply()
    }

    fun autoUri(context: Context): Uri? = prefs(context)?.getString(KEY_URI, null)?.let(Uri::parse)
    fun lastBackupAt(context: Context): Long = prefs(context)?.getLong(KEY_LAST, 0) ?: 0
    fun lastError(context: Context): String? = prefs(context)?.getString(KEY_ERROR, null)

    fun markDone(context: Context) {
        prefs(context)?.edit()?.putLong(KEY_LAST, System.currentTimeMillis())?.remove(KEY_ERROR)?.apply()
    }

    /** Called on every task change; writes the backup 3 seconds later (batched). */
    @Synchronized
    fun scheduleAuto(context: Context) {
        val app = context.applicationContext
        if (autoUri(app) == null) return
        pending?.cancel(false)
        pending = worker.schedule({ runAuto(app) }, 3, TimeUnit.SECONDS)
    }

    private fun runAuto(context: Context) {
        val uri = autoUri(context) ?: return
        try {
            writeTo(context, uri)
            markDone(context)
        } catch (e: Exception) {
            Log.w(TAG, "Automatic backup failed", e)
            prefs(context)?.edit()?.putString(KEY_ERROR, "Last automatic backup failed. Tap “Back up now” to choose the file again.")?.apply()
        }
    }

    /** Normal (credential-protected) prefs are unavailable before the first unlock after reboot. */
    private fun prefs(context: Context) = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (_: Exception) { null }
}
