package com.mysoftwares.todo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class BackupActivity : BaseActivity() {
    private val io = AppExecutors.io

    /** Folder for automatic backups: a new file each time, old ones deleted. */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            Backup.enableFolder(this, tree)
            backupNow(tree)
        } else render()
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFrom(uri)
    }

    /** "Save a copy…": writes an extra backup file without changing the automatic one. */
    private val copyFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) io.execute {
            val r = runCatching { Backup.writeTo(this, uri) }
            runOnUiThread {
                Toast.makeText(this, if (r.isSuccess) getString(R.string.backup_copy_saved) else getString(R.string.backup_failed, r.exceptionOrNull()?.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private lateinit var list: IosList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setTitle(R.string.backup_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = IosList(this, findViewById(R.id.content))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.clear()
        val auto = Backup.autoUri(this)
        val tree = Backup.autoTree(this)
        val last = Backup.lastBackupAt(this)
        val error = Backup.lastError(this)
        val count = TaskDb.get(this).all().size

        // Status
        list.label(getString(R.string.backup_status))
        val status = list.group()
        status.switchRow(getString(R.string.backup_auto), getString(R.string.backup_auto_hint), tree != null || auto != null) { on ->
            if (on) pickFolder.launch(null) // choose a folder; a new file is made each time
            else { Backup.disableFolder(this); Backup.disableAuto(this); render() }
        }
        if (tree != null) {
            status.row(getString(R.string.backup_folder), Backup.folderName(this, tree)) { pickFolder.launch(tree) }
            val keep = Backup.keepCount(this)
            status.row(getString(R.string.backup_keep), getString(R.string.backup_keep_value, keep)) { chooseKeep(tree) }
            Backup.lastFileName(this)?.let { status.row(getString(R.string.backup_latest_file), it) }
            status.row(
                getString(R.string.backup_last),
                if (last > 0) TaskAdapter.friendlyTime(last) else getString(R.string.backup_not_yet),
                titleColor = null,
            )
        } else if (auto != null) {
            // Older setting: one file that is replaced each time. Offer the folder instead.
            status.row(getString(R.string.backup_file), fileName(auto))
            status.row(
                getString(R.string.backup_last),
                if (last > 0) TaskAdapter.friendlyTime(last) else getString(R.string.backup_not_yet),
                titleColor = null,
            )
        }
        if (error != null) list.footer("⚠ $error")
        else if (tree == null && auto != null) list.footer(getString(R.string.backup_old_single_file))
        else if (tree != null) list.footer(getString(R.string.backup_folder_footer, Backup.keepCount(this)))

        // Back up
        list.label(getString(R.string.ui_back_up))
        val backup = list.group()
        val accent = com.google.android.material.color.MaterialColors.getColor(findViewById<android.view.View>(R.id.content), androidx.appcompat.R.attr.colorPrimary)
        backup.row(getString(R.string.ui_back_up_now), getString(R.string.backup_tasks_count, count), titleColor = accent) {
            if (tree != null && error == null) backupNow(tree) else pickFolder.launch(tree)
        }
        backup.row(getString(R.string.backup_save_copy), titleColor = accent) { copyFile.launch(Backup.fileNameNow()) }
        backup.row(getString(R.string.backup_share), titleColor = accent) { share() }

        // Restore
        list.label(getString(R.string.ui_restore))
        list.group().row(getString(R.string.backup_restore_from), titleColor = accent) {
            openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        }
        list.footer(getString(R.string.backup_footer))
    }

    /** "Share backup…": send the backup file with WhatsApp, Gmail, Drive… */
    private fun share() {
        io.execute {
            val r = runCatching {
                val dir = java.io.File(cacheDir, "reports").apply { mkdirs() }
                val file = java.io.File(dir, Backup.fileNameNow())
                file.writeText(Backup.toJson(TaskDb.get(this).all()))
                androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }
            runOnUiThread {
                r.onSuccess { uri ->
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.backup_share)
                    ))
                }.onFailure { Toast.makeText(this, getString(R.string.backup_failed, it.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun backupNow(tree: Uri) {
        io.execute {
            val result = runCatching { Backup.backupToFolder(this, tree) }
            runOnUiThread {
                result.onSuccess { name ->
                    Backup.markDone(this)
                    Toast.makeText(this, getString(R.string.backup_saved_as, name), Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, getString(R.string.backup_failed, it.message), Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    private fun chooseKeep(tree: Uri) {
        val opts = Backup.KEEP_OPTIONS
        val current = opts.indexOf(Backup.keepCount(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_keep))
            .setSingleChoiceItems(opts.map { getString(R.string.backup_keep_value, it) }.toTypedArray(), current) { d, i ->
                d.dismiss()
                Backup.setKeepCount(this, opts[i])
                io.execute {
                    val n = runCatching { Backup.prune(this, tree) }.getOrDefault(0)
                    runOnUiThread {
                        if (n > 0) Toast.makeText(this, getString(R.string.backup_deleted_old, n), Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
            .show()
    }

    private fun restoreFrom(uri: Uri) {
        io.execute {
            val result = runCatching { Backup.readWithDate(this, uri) }
            runOnUiThread {
                result.onSuccess { (tasks, at) -> confirmRestore(tasks, at) }
                    .onFailure { Toast.makeText(this, it.message ?: getString(R.string.msg_could_not_read_the_file), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun confirmRestore(tasks: List<Task>, backupAt: Long = 0) {
        if (tasks.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_this_backup_has_no_tasks), Toast.LENGTH_LONG).show(); return
        }
        val current = TaskDb.get(this).all().size
        // "Backup from 28 Nov 2026, 11:28 pm" so an old file is not restored by mistake.
        val from = if (backupAt > 0) getString(
            R.string.backup_made_at,
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(backupAt))
        ) + "\n\n" else ""
        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore ${tasks.size} task(s)?")
            .setNegativeButton(getString(R.string.ui_cancel), null)
        if (current == 0) {
            dialog.setMessage(from + getString(R.string.msg_all_alarms_will_be_set_again))
                .setPositiveButton(getString(R.string.ui_restore)) { _, _ -> doRestore(tasks, replace = true) }
        } else {
            dialog.setMessage(
                from + "You already have $current task(s).\n\n• Add: keep current tasks and add the missing ones.\n" +
                    "• Replace: delete current tasks and use the backup."
            )
                .setPositiveButton(getString(R.string.ui_add)) { _, _ -> doRestore(tasks, replace = false) }
                .setNeutralButton(getString(R.string.msg_replace)) { _, _ -> doRestore(tasks, replace = true) }
        }
        dialog.show()
    }

    private fun doRestore(tasks: List<Task>, replace: Boolean) {
        io.execute {
            val result = runCatching { Backup.restore(this, tasks, replace) }
            runOnUiThread {
                result.onSuccess { n ->
                    Toast.makeText(this, "Restored $n task(s). Alarms are set.", Toast.LENGTH_LONG).show()
                }.onFailure { Toast.makeText(this, "Restore failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun fileName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "backup file"
    } catch (_: Exception) {
        uri.lastPathSegment ?: "backup file"
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
