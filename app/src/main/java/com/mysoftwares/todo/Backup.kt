package com.mysoftwares.todo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Backup and restore of tasks as JSON files.
 * Automatic backup: the user picks a folder; every backup is a NEW file
 * (ToDo_backup_26-11-28_113955.json) and only the newest [keepCount] files are kept.
 * (Older app versions kept one file up to date; that still works until a folder is chosen.)
 */
object Backup {
    private const val TAG = "Backup"
    private const val PREFS = "backup"
    private const val KEY_URI = "auto_uri"
    private const val KEY_LAST = "last_backup"
    private const val KEY_ERROR = "last_error"
    private const val KEY_TREE = "auto_tree"
    private const val KEY_KEEP = "keep_count"
    private const val KEY_LAST_NAME = "last_name"
    private const val PREFIX = "ToDo_backup_"

    /** Choices for "Keep last … backups". */
    val KEEP_OPTIONS = intArrayOf(5, 10, 20, 30, 50)

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
                put("priority", t.priority.name)
                put("endOffset", t.endOffset)
                put("autoRepeat", t.autoRepeat)
                put("checklist", Checklist.toJson(t.checklist))
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
                priority = runCatching { Priority.valueOf(o.optString("priority")) }.getOrDefault(Priority.NONE),
                endOffset = o.optInt("endOffset"),
                autoRepeat = o.optBoolean("autoRepeat"),
                checklist = Checklist.fromJson(o.optString("checklist")),
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

    fun readFrom(context: Context, uri: Uri): List<Task> = readWithDate(context, uri).first

    /** Tasks in the file and when the backup was made (0 if unknown). */
    fun readWithDate(context: Context, uri: Uri): Pair<List<Task>, Long> {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: throw IllegalStateException("Cannot open the file")
        val at = runCatching { JSONObject(text).optLong("exportedAt", 0) }.getOrDefault(0)
        return fromJson(text) to at
    }

    /** "ToDo_backup_26-11-28_113955.json": date and time of the backup in the file name. */
    fun fileNameNow(): String =
        "ToDo_backup_" + java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yy-MM-dd_HHmmss")) + ".json"

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

    // ---- Backup folder (a new file every time) ----

    fun autoTree(context: Context): Uri? = prefs(context)?.getString(KEY_TREE, null)?.let(Uri::parse)
    fun keepCount(context: Context): Int = prefs(context)?.getInt(KEY_KEEP, 10) ?: 10
    fun setKeepCount(context: Context, n: Int) { prefs(context)?.edit()?.putInt(KEY_KEEP, n)?.apply() }
    fun lastFileName(context: Context): String? = prefs(context)?.getString(KEY_LAST_NAME, null)

    /** Use this folder for automatic backups (replaces the old single-file setting). */
    fun enableFolder(context: Context, tree: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not keep access to backup folder", e)
        }
        disableAuto(context)
        prefs(context)?.edit()?.putString(KEY_TREE, tree.toString())?.remove(KEY_ERROR)?.apply()
    }

    private fun folderDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Name of the chosen folder, e.g. "ToDo backups". */
    fun folderName(context: Context, tree: Uri): String = try {
        context.contentResolver.query(folderDoc(tree), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null } ?: tree.lastPathSegment.orEmpty().substringAfterLast(':').ifEmpty { "Folder" }

    /** Our backup files in the folder: (documentId, name), newest first. */
    fun listBackups(context: Context, tree: Uri): List<Pair<String, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val out = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(".json")) out += c.getString(0) to name
            }
        }
        // The date and time in the name sort correctly as text.
        return out.sortedByDescending { it.second }
    }

    /** Writes a NEW backup file in the folder, then deletes the oldest ones beyond keepCount. Returns its name. */
    fun backupToFolder(context: Context, tree: Uri): String {
        val name = fileNameNow()
        val doc = DocumentsContract.createDocument(context.contentResolver, folderDoc(tree), "application/json", name)
            ?: throw IllegalStateException("Cannot create a file in the backup folder")
        writeTo(context, doc)
        prefs(context)?.edit()?.putString(KEY_LAST_NAME, name)?.apply()
        prune(context, tree)
        return name
    }

    /** Deletes old backup files so only the newest keepCount remain. Returns how many were deleted. */
    fun prune(context: Context, tree: Uri): Int {
        var deleted = 0
        listBackups(context, tree).drop(keepCount(context)).forEach { (id, _) ->
            try {
                if (DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))) deleted++
            } catch (e: Exception) { Log.w(TAG, "Could not delete old backup", e) }
        }
        return deleted
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

    fun disableFolder(context: Context) {
        autoTree(context)?.let {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
        }
        prefs(context)?.edit()?.remove(KEY_TREE)?.apply()
    }

    /** Automatic backup is on (folder, or the old single file). */
    fun isOn(context: Context) = autoTree(context) != null || autoUri(context) != null

    fun autoUri(context: Context): Uri? = prefs(context)?.getString(KEY_URI, null)?.let(Uri::parse)
    fun lastBackupAt(context: Context): Long = prefs(context)?.getLong(KEY_LAST, 0) ?: 0
    fun lastError(context: Context): String? = prefs(context)?.getString(KEY_ERROR, null)

    fun markDone(context: Context) {
        prefs(context)?.edit()?.putLong(KEY_LAST, System.currentTimeMillis())?.remove(KEY_ERROR)?.apply()
    }

    /**
     * Called on every task change. The backup is written 1 minute after the last change,
     * so a burst of edits makes one new file instead of many.
     */
    @Synchronized
    fun scheduleAuto(context: Context) {
        val app = context.applicationContext
        if (!isOn(app)) return
        pending?.cancel(false)
        pending = worker.schedule({ runAuto(app) }, if (autoTree(app) != null) 60 else 3, TimeUnit.SECONDS)
    }

    private fun runAuto(context: Context) {
        try {
            val tree = autoTree(context)
            if (tree != null) backupToFolder(context, tree)
            else writeTo(context, autoUri(context) ?: return)
            markDone(context)
        } catch (e: Exception) {
            Log.w(TAG, "Automatic backup failed", e)
            prefs(context)?.edit()?.putString(KEY_ERROR, "Last automatic backup failed. Tap “Back up now” to choose the folder again.")?.apply()
        }
    }

    /** Normal (credential-protected) prefs are unavailable before the first unlock after reboot. */
    private fun prefs(context: Context) = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (_: Exception) { null }
}
