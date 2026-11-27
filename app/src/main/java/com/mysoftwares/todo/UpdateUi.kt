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
        if (manual) toast("Checking for updates…")
        io.execute {
            val rel = runCatching { Updater.fetchLatest() }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    rel == null -> if (manual) toast("Could not check for updates. Please check your internet connection.")
                    rel.versionCode > Updater.currentVersionCode -> showUpdateDialog(rel)
                    manual -> toast("You have the latest version (${Updater.currentVersionName}).")
                }
            }
        }
    }

    /** The new version uses the new private signing key: a one-time reinstall is needed. */
    private fun showReinstallHelp(): Unit = a.run<AppCompatActivity, Unit> {
        AlertDialog.Builder(this)
            .setTitle("One-time reinstall needed")
            .setMessage(
                "This update is signed with a new, more secure key, so Android can't install it over this version.\n\n" +
                    "1. Settings → Backup & restore → Back up now\n" +
                    "2. Uninstall Office To-Do\n" +
                    "3. Install the new version from the releases page\n" +
                    "4. Settings → Backup & restore → Restore from file\n\n" +
                    "After this, updates install normally again."
            )
            .setPositiveButton("Back up now") { _, _ -> startActivity(Intent(this, BackupActivity::class.java)) }
            .setNeutralButton("Releases page") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Updater.RELEASES_PAGE)))
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showUpdateDialog(rel: Updater.Release): Unit = a.run<AppCompatActivity, Unit> {
        val notes = rel.notes.trim().take(600)
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(
                "Version ${rel.versionName} is ready (you have ${Updater.currentVersionName}).\n" +
                    "Your tasks and alarms are kept." + if (notes.isNotEmpty()) "\n\nWhat's new:\n$notes" else ""
            )
            .setPositiveButton("Update now") { _, _ -> downloadAndInstall(rel) }
            .setNegativeButton("Later", null)
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
            .setTitle("Downloading update…")
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
                        toast("Allow 'Install unknown apps' for Office To-Do, then press Back to continue.")
                    }
                }.onFailure { toast("Update failed: ${it.message}") }
            }
        }
    }
}
