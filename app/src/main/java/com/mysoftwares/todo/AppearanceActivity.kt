package com.mysoftwares.todo

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** Settings → Appearance: theme, colour, task list style and text size, in grouped rows. */
class AppearanceActivity : BaseActivity() {
    private lateinit var list: IosList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setTitle(R.string.appearance_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = IosList(this, findViewById(R.id.content))
        render()
    }

    private fun render() {
        list.clear()

        // Theme
        list.label(getString(R.string.ui_theme))
        val theme = list.group()
        listOf(
            Appearance.Mode.SYSTEM to getString(R.string.appearance_system),
            Appearance.Mode.LIGHT to getString(R.string.ui_light),
            Appearance.Mode.DARK to getString(R.string.ui_dark),
        ).forEach { (mode, label) ->
            theme.row(label, if (Appearance.mode(this) == mode) "✓" else "") {
                if (mode != Appearance.mode(this)) Appearance.setMode(this, mode) // the system redraws the screen
            }
        }

        // Colour
        list.label(getString(R.string.ui_colour))
        list.group().custom(swatchGrid())

        // Task list style
        list.label(getString(R.string.ui_task_list_style))
        val style = list.group()
        listOf(
            Appearance.ListStyle.COMPACT to getString(R.string.ui_grouped),
            Appearance.ListStyle.CARDS to getString(R.string.ui_cards),
        ).forEach { (s, label) ->
            style.row(label, if (Appearance.listStyle(this) == s) "✓" else "") {
                Appearance.setListStyle(this, s); render()
            }
        }
        list.footer(getString(R.string.ui_grouped_one_white_list_per_section_like_))

        // Text
        list.label(getString(R.string.appearance_text))
        list.group().switchRow(
            getString(R.string.ui_large_text), getString(R.string.ui_bigger_task_titles_and_times), Appearance.largeText(this)
        ) { on -> Appearance.setLargeText(this, on); recreate() }
    }

    /** Colour circles in rows of 4 with their names; the selected one has a ✓ and a ring. */
    private fun swatchGrid(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val current = Appearance.accent(this)
        val accents = Appearance.Accent.entries.filter { it != Appearance.Accent.WALLPAPER || Appearance.wallpaperSupported }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        accents.chunked(4).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { accent ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, 0, dp(8))
                    contentDescription = accent.label
                    setOnClickListener {
                        if (accent != Appearance.accent(this@AppearanceActivity)) {
                            Appearance.setAccent(this@AppearanceActivity, accent); recreate()
                        }
                    }
                }
                val circle = MaterialCardView(this).apply {
                    radius = dp(22).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(
                        if (accent == Appearance.Accent.WALLPAPER) MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
                        else ContextCompat.getColor(context, accent.colorRes)
                    )
                    strokeWidth = if (accent == current) dp(3) else 0
                    strokeColor = ContextCompat.getColor(context, R.color.ios_label)
                    addView(TextView(context).apply {
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        setTextColor(ContextCompat.getColor(context, R.color.white))
                        text = when {
                            accent == current -> "✓"
                            accent == Appearance.Accent.WALLPAPER -> "🎨"
                            else -> ""
                        }
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                cell.addView(circle, LinearLayout.LayoutParams(dp(44), dp(44)))
                cell.addView(TextView(this).apply {
                    text = accent.label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(context, if (accent == current) R.color.ios_label else R.color.ios_secondary))
                    maxLines = 1
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            // keep columns aligned when the last row is short
            repeat(4 - rowItems.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row)
        }
        return grid
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
