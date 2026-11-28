package com.mysoftwares.todo

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        setTitle(R.string.backup_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<MaterialButton>(R.id.backup_now).setOnClickListener {
            val auto = Backup.autoUri(this)
            if (auto != null && Backup.lastError(this) == null) backupTo(auto)
            else createFile.launch("OfficeToDo-backup.json")
        }
        findViewById<MaterialButton>(R.id.backup_now).setOnLongClickListener {
            createFile.launch("OfficeToDo-backup.json"); true // choose a different file
        }
        findViewById<MaterialButton>(R.id.auto_off).setOnClickListener {
            Backup.disableAuto(this); render()
        }
        findViewById<MaterialButton>(R.id.restore).setOnClickListener {
            openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val auto = Backup.autoUri(this)
        val last = Backup.lastBackupAt(this)
        val error = Backup.lastError(this)
        val ok = auto != null && error == null

        findViewById<MaterialCardView>(R.id.status_card)
            .setCardBackgroundColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.warn))
        findViewById<TextView>(R.id.status_title).text =
            if (ok) "✔  Automatic backup is on" else "No automatic backup"
        findViewById<TextView>(R.id.status_text).text = when {
            error != null -> error
            auto != null -> "File: ${fileName(auto)}\nLast backup: " +
                (if (last > 0) TaskAdapter.friendlyTime(last) else "not yet")
            else -> "Tap “Back up now” and choose where to save the file."
        }
        findViewById<View>(R.id.auto_off).visibility = if (auto != null) View.VISIBLE else View.GONE
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
