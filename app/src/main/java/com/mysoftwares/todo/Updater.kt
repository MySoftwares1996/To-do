package com.mysoftwares.todo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Over-the-air updates from this app's GitHub Releases.
 * CI publishes every build as a release tagged v1.0.<versionCode> with OfficeToDo.apk attached.
 * All builds are signed with the same key, so the update installs over the old version and keeps tasks.
 */
object Updater {
    /** Set at build time from the GitHub repository that built the app (see app/build.gradle.kts). */
    private val LATEST = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
    val RELEASES_PAGE = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"
    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val AUTO_CHECK_INTERVAL = 6 * 60 * 60 * 1000L

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        val sha256: String?,
    )

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** True if an automatic check is due (at most every 6 hours). Records the check time. */
    fun autoCheckDue(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST_CHECK, 0) < AUTO_CHECK_INTERVAL) return false
        p.edit().putLong(KEY_LAST_CHECK, now).apply()
        return true
    }

    /** Blocking network call – run off the main thread. Returns the latest release, or null. */
    fun fetchLatest(): Release? {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OfficeToDo-Android")
        }
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(
                        versionCode = code,
                        versionName = tag.removePrefix("v"),
                        apkUrl = a.getString("browser_download_url"),
                        notes = json.optString("body").lines()
                            .filterNot { it.startsWith("Co-Authored-By:") || it.startsWith("Claude-Session:") }
                            .joinToString("\n").trim(),
                        sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking download with progress (0–100). Verifies the SHA-256 checksum when GitHub provides one. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "OfficeToDo-${release.versionName}.apk")
        val digest = MessageDigest.getInstance("SHA-256")

        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) { // follow GitHub's redirect to its download server
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OfficeToDo-Android")
            }
            val rc = conn.responseCode
            if (rc in 300..399 && redirects < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue
            }
            if (rc != 200) throw IllegalStateException("Download failed (HTTP $rc)")
            break
        }
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        release.sha256?.let { expected ->
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                out.delete()
                throw IllegalStateException("Downloaded file is corrupted. Please try again.")
            }
        }
        return out
    }

    /**
     * True if the downloaded APK is signed with the same key as this app (or if that cannot be checked).
     * A different key means Android will refuse to install it over the current app.
     */
    @Suppress("DEPRECATION")
    fun sameSigner(context: Context, apk: File): Boolean = try {
        val pm = context.packageManager
        fun certs(info: android.content.pm.PackageInfo?): Set<String>? {
            if (info == null) return null
            val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return sigs?.map { it.toCharsString() }?.toSet()
        }
        val flag = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        else android.content.pm.PackageManager.GET_SIGNATURES
        val mine = certs(pm.getPackageInfo(context.packageName, flag))
        val theirs = certs(pm.getPackageArchiveInfo(apk.path, flag))
        mine == null || theirs == null || mine == theirs
    } catch (_: Exception) { true }

    /** Opens the system installer. Returns false if the user first has to allow installs from this app. */
    fun install(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
