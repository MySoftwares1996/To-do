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

    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupTo(uri)
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

    private fun fileNameForToday() = "OfficeToDo-backup-${java.time.LocalDate.now()}.json"

    private fun render() {
        list.clear()
        val auto = Backup.autoUri(this)
        val last = Backup.lastBackupAt(this)
        val error = Backup.lastError(this)
        val count = TaskDb.get(this).all().size

        // Status
        list.label(getString(R.string.backup_status))
        val status = list.group()
        status.switchRow(getString(R.string.backup_auto), getString(R.string.backup_auto_hint), auto != null) { on ->
            if (on) createFile.launch("OfficeToDo-backup.json") // choose where; then kept up to date
            else { Backup.disableAuto(this); render() }
        }
        if (auto != null) {
            status.row(getString(R.string.backup_file), fileName(auto))
            status.row(
                getString(R.string.backup_last),
                if (last > 0) TaskAdapter.friendlyTime(last) else getString(R.string.backup_not_yet),
                titleColor = null,
            )
        }
        if (error != null) list.footer("⚠ $error")

        // Back up
        list.label(getString(R.string.ui_back_up))
        val backup = list.group()
        val accent = com.google.android.material.color.MaterialColors.getColor(findViewById<android.view.View>(R.id.content), androidx.appcompat.R.attr.colorPrimary)
        backup.row(getString(R.string.ui_back_up_now), getString(R.string.backup_tasks_count, count), titleColor = accent) {
            if (auto != null && error == null) backupTo(auto) else createFile.launch("OfficeToDo-backup.json")
        }
        backup.row(getString(R.string.backup_save_copy), titleColor = accent) { copyFile.launch(fileNameForToday()) }
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
                val file = java.io.File(dir, fileNameForToday())
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

    private fun backupTo(uri: Uri) {
        io.execute {
            val result = runCatching { Backup.writeTo(this, uri) }
            runOnUiThread {
                result.onSuccess {
                    Backup.enableAuto(this, uri)
                    Backup.markDone(this)
                    val n = TaskDb.get(this).all().size
                    Toast.makeText(this, "Backed up $n task(s)", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "Backup failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    private fun restoreFrom(uri: Uri) {
        io.execute {
            val result = runCatching { Backup.readFrom(this, uri) }
            runOnUiThread {
                result.onSuccess { tasks -> confirmRestore(tasks) }
                    .onFailure { Toast.makeText(this, it.message ?: getString(R.string.msg_could_not_read_the_file), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun confirmRestore(tasks: List<Task>) {
        if (tasks.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_this_backup_has_no_tasks), Toast.LENGTH_LONG).show(); return
        }
        val current = TaskDb.get(this).all().size
        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore ${tasks.size} task(s)?")
            .setNegativeButton(getString(R.string.ui_cancel), null)
        if (current == 0) {
            dialog.setMessage(getString(R.string.msg_all_alarms_will_be_set_again))
                .setPositiveButton(getString(R.string.ui_restore)) { _, _ -> doRestore(tasks, replace = true) }
        } else {
            dialog.setMessage(
                "You already have $current task(s).\n\n• Add: keep current tasks and add the missing ones.\n" +
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
