package com.mysoftwares.todo

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/** User-selectable look: theme mode, accent colour, list style and text size. */
object Appearance {
    private const val PREFS = "appearance"

    enum class Mode(val label: String, val night: Int) {
        SYSTEM("System", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES),
    }

    enum class Accent(val label: String, val colorRes: Int, val overlay: Int) {
        BLUE("Blue", R.color.accent_blue, R.style.ThemeOverlay_Accent_Blue),
        GREEN("Green", R.color.accent_green, R.style.ThemeOverlay_Accent_Green),
        PURPLE("Purple", R.color.accent_purple, R.style.ThemeOverlay_Accent_Purple),
        ORANGE("Orange", R.color.accent_orange, R.style.ThemeOverlay_Accent_Orange),
        TEAL("Teal", R.color.accent_teal, R.style.ThemeOverlay_Accent_Teal),
        PINK("Pink", R.color.accent_pink, R.style.ThemeOverlay_Accent_Pink),
        /** Material You: colours taken from the wallpaper (Android 12+). */
        WALLPAPER("Wallpaper", R.color.accent_blue, R.style.ThemeOverlay_Accent_Blue),
    }

    enum class ListStyle(val label: String) { CARDS("Cards"), COMPACT("Compact") }

    /** Changes every time a setting changes, so open screens know to redraw. */
    @Volatile var stamp = 0
        private set

    private fun prefs(c: Context) = try {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (_: Exception) { null } // not available before first unlock

    fun mode(c: Context) = prefs(c)?.getString("mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.SYSTEM
    fun accent(c: Context) = prefs(c)?.getString("accent", null)?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: Accent.BLUE
    fun listStyle(c: Context) = prefs(c)?.getString("list", null)?.let { runCatching { ListStyle.valueOf(it) }.getOrNull() } ?: ListStyle.CARDS
    fun largeText(c: Context) = prefs(c)?.getBoolean("large_text", false) ?: false

    fun setMode(c: Context, m: Mode) { save(c) { putString("mode", m.name) }; AppCompatDelegate.setDefaultNightMode(m.night) }
    fun setAccent(c: Context, a: Accent) = save(c) { putString("accent", a.name) }
    fun setListStyle(c: Context, s: ListStyle) = save(c) { putString("list", s.name) }
    fun setLargeText(c: Context, on: Boolean) = save(c) { putBoolean("large_text", on) }

    private fun save(c: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(c)?.edit()?.apply(block)?.apply()
        stamp++
    }

    val wallpaperSupported get() = Build.VERSION.SDK_INT >= 31 && DynamicColors.isDynamicColorAvailable()

    /** Call once at app start. */
    fun init(c: Context) = AppCompatDelegate.setDefaultNightMode(mode(c).night)

    /** Call in each activity before super.onCreate(). */
    fun applyTo(a: Activity) {
        val accent = accent(a)
        if (accent == Accent.WALLPAPER && wallpaperSupported) DynamicColors.applyToActivityIfAvailable(a)
        else a.theme.applyStyle(accent.overlay, true)
        if (largeText(a)) a.theme.applyStyle(R.style.ThemeOverlay_TextLarge, true)
    }
}

/** All screens extend this so the chosen look is applied and refreshed after changes. */
open class BaseActivity : AppCompatActivity() {
    private var appliedStamp = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.applyTo(this)
        appliedStamp = Appearance.stamp
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (appliedStamp != Appearance.stamp) recreate()
    }
}
