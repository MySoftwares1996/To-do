package com.mysoftwares.todo

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/** Over-the-air update flow (check, ask, download, verify, install), moved out of MainActivity. */
class UpdateUi(private val a: AppCompatActivity) {
    private val io = AppExecutors.io
    /** Downloaded update waiting for the user to allow "Install unknown apps". */
    private var pendingApk: File? = null

    /** Call from onResume: finishes a pending install and checks for updates at most every 6 hours. */
    fun onResume() {
        pendingApk?.let {
            if (Build.VERSION.SDK_INT < 26 || a.packageManager.canRequestPackageInstalls()) {
                pendingApk = null
                Updater.install(a, it)
            }
        }
        if (Updater.autoCheckDue(a)) checkForUpdate(manual = false)
    }

    fun toast(msg: String) = Toast.makeText(a, msg, Toast.LENGTH_LONG).show()

    fun checkForUpdate(manual: Boolean): Unit = a.run<AppCompatActivity, Unit> {
        if (manual) toast(a.getString(R.string.msg_checking_for_updates))
        io.execute {
            val rel = runCatching { Updater.fetchLatest() }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    rel == null -> if (manual) toast(a.getString(R.string.msg_could_not_check_for_updates_please_che))
                    rel.versionCode > Updater.currentVersionCode -> showUpdateDialog(rel)
                    manual -> toast("You have the latest version (${Updater.currentVersionName}).")
                }
            }
        }
    }

    /** The new version uses the new private signing key: a one-time reinstall is needed. */
    private fun showReinstallHelp(): Unit = a.run<AppCompatActivity, Unit> {
        AlertDialog.Builder(this)
            .setTitle(a.getString(R.string.msg_one_time_reinstall_needed))
            .setMessage(
                "This update is signed with a new, more secure key, so Android can't install it over this version.\n\n" +
                    "1. Settings → Backup & restore → Back up now\n" +
                    "2. Uninstall Office To-Do\n" +
                    "3. Install the new version from the releases page\n" +
                    "4. Settings → Backup & restore → Restore from file\n\n" +
                    "After this, updates install normally again."
            )
            .setPositiveButton(a.getString(R.string.ui_back_up_now)) { _, _ -> startActivity(Intent(this, BackupActivity::class.java)) }
            .setNeutralButton(a.getString(R.string.msg_releases_page)) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Updater.RELEASES_PAGE)))
                }
            }
            .setNegativeButton(a.getString(R.string.msg_later), null)
            .show()
    }

    private fun showUpdateDialog(rel: Updater.Release): Unit = a.run<AppCompatActivity, Unit> {
        val notes = rel.notes.trim().take(600)
        AlertDialog.Builder(this)
            .setTitle(a.getString(R.string.msg_update_available))
            .setMessage(
                "Version ${rel.versionName} is ready (you have ${Updater.currentVersionName}).\n" +
                    "Your tasks and alarms are kept." + if (notes.isNotEmpty()) "\n\nWhat's new:\n$notes" else ""
            )
            .setPositiveButton(a.getString(R.string.msg_update_now)) { _, _ -> downloadAndInstall(rel) }
            .setNegativeButton(a.getString(R.string.msg_later), null)
            .show()
    }

    private fun downloadAndInstall(rel: Updater.Release): Unit = a.run<AppCompatActivity, Unit> {
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            isIndeterminate = false
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(a.getString(R.string.msg_downloading_update))
            .setView(bar)
            .setCancelable(false)
            .show()
        io.execute {
            val result = runCatching {
                Updater.download(this, rel) { p -> runOnUiThread { bar.setProgressCompat(p, true) } }
            }
            runOnUiThread {
                if (!isDestroyed) dialog.dismiss()
                result.onSuccess { apk ->
                    if (!Updater.sameSigner(this, apk)) { showReinstallHelp(); return@onSuccess }
                    if (!Updater.install(this, apk)) {
                        pendingApk = apk
                        toast(a.getString(R.string.msg_allow_install_unknown_apps_for_office_))
                    }
                }.onFailure { toast("Update failed: ${it.message}") }
            }
        }
    }
}
