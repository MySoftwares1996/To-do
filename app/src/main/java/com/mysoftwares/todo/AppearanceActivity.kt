package com.mysoftwares.todo

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

/** Choose theme (light/dark), colour, list style and text size. Changes apply instantly. */
class AppearanceActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)
        setTitle(R.string.appearance_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Theme mode
        val modeGroup = findViewById<MaterialButtonToggleGroup>(R.id.mode_group)
        val modeIds = mapOf(
            Appearance.Mode.SYSTEM to R.id.mode_system,
            Appearance.Mode.LIGHT to R.id.mode_light,
            Appearance.Mode.DARK to R.id.mode_dark,
        )
        modeGroup.check(modeIds.getValue(Appearance.mode(this)))
        modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val m = modeIds.entries.first { it.value == id }.key
            if (m != Appearance.mode(this)) Appearance.setMode(this, m) // the system redraws screens
        }

        // List style
        val listGroup = findViewById<MaterialButtonToggleGroup>(R.id.list_group)
        listGroup.check(if (Appearance.listStyle(this) == Appearance.ListStyle.COMPACT) R.id.list_compact else R.id.list_cards)
        listGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            Appearance.setListStyle(this, if (id == R.id.list_compact) Appearance.ListStyle.COMPACT else Appearance.ListStyle.CARDS)
        }

        // Large text
        findViewById<MaterialSwitch>(R.id.large_text).apply {
            isChecked = Appearance.largeText(this@AppearanceActivity)
            setOnCheckedChangeListener { _, on ->
                Appearance.setLargeText(this@AppearanceActivity, on)
                recreate()
            }
        }

        buildSwatches()
    }

    private fun buildSwatches() {
        val box = findViewById<LinearLayout>(R.id.swatches)
        val current = Appearance.accent(this)
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()
        val gap = (10 * density).toInt()

        Appearance.Accent.entries
            .filter { it != Appearance.Accent.WALLPAPER || Appearance.wallpaperSupported }
            .forEach { accent ->
                val card = MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                    radius = size / 2f
                    cardElevation = 0f
                    contentDescription = accent.label
                    if (accent == Appearance.Accent.WALLPAPER) {
                        setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant))
                    } else {
                        setCardBackgroundColor(ContextCompat.getColor(context, accent.colorRes))
                    }
                    strokeWidth = if (accent == current) (3 * density).toInt() else 0
                    strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
                    addView(TextView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                        gravity = Gravity.CENTER
                        textSize = 20f
                        text = when {
                            accent == current -> "✓"
                            accent == Appearance.Accent.WALLPAPER -> "🎨"
                            else -> ""
                        }
                        setTextColor(ContextCompat.getColor(context, R.color.white))
                        if (accent == Appearance.Accent.WALLPAPER && accent == current) {
                            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                        }
                    })
                    setOnClickListener {
                        if (accent != Appearance.accent(this@AppearanceActivity)) {
                            Appearance.setAccent(this@AppearanceActivity, accent)
                            recreate()
                        }
                    }
                }
                box.addView(card)
            }
        findViewById<TextView>(R.id.accent_name).text =
            if (current == Appearance.Accent.WALLPAPER) "Wallpaper colours (Material You)" else current.label
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
