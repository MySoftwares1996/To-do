package com.mysoftwares.todo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * Bottom tab bar: the selected tab is a coloured pill with icon + label side by side,
 * the others show only their icon. Changing tab animates the pill and bounces the icon.
 */
class PillTabBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    data class Item(val id: Int, val icon: Int, val label: String)

    private inner class Tab(val item: Item) {
        val root = FrameLayout(context)
        val pill = LinearLayout(context)
        val icon = ImageView(context)
        val label = TextView(context)
        val badge = TextView(context)
    }

    private val tabs = mutableListOf<Tab>()
    var selectedId: Int = 0
        private set
    var onSelect: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val accent get() = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val gray get() = ContextCompat.getColor(context, R.color.ios_gray)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(10))
    }

    fun setItems(items: List<Item>) {
        removeAllViews(); tabs.clear()
        items.forEach { item ->
            val t = Tab(item)
            t.pill.apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
            }
            t.icon.setImageResource(item.icon)
            t.pill.addView(t.icon, LayoutParams(dp(24), dp(24)))
            // overdue bubble sits on the icon's top-right corner
            t.pill.addView(t.badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(16)).apply {
                marginStart = -dp(9); gravity = Gravity.TOP; topMargin = -dp(6)
            })
            t.label.apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
            }
            t.pill.addView(t.label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            t.root.addView(t.pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            t.badge.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(ContextCompat.getColor(context, R.color.white))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = dp(16)
                setPadding(dp(4), 0, dp(4), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.ios_red))
                }
                visibility = View.GONE
            }
            t.root.contentDescription = item.label
            t.root.setOnClickListener { select(item.id, animate = true, notify = true) }
            addView(t.root, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            tabs += t
        }
        tabs.firstOrNull()?.let { select(it.item.id, animate = false, notify = false) }
    }

    /** Selects a tab. [notify] calls [onSelect] (used for taps). */
    fun select(id: Int, animate: Boolean = true, notify: Boolean = true) {
        val changed = id != selectedId
        selectedId = id
        if (animate) TransitionManager.beginDelayedTransition(this, AutoTransition().setDuration(260))
        tabs.forEach { t ->
            val on = t.item.id == id
            t.label.visibility = if (on) View.VISIBLE else View.GONE
            t.label.setTextColor(accent)
            t.icon.imageTintList = ColorStateList.valueOf(if (on) accent else gray)
            t.pill.background = if (on) GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(MaterialColors.getColor(this@PillTabBar, com.google.android.material.R.attr.colorPrimaryContainer))
            } else null
            if (on && animate && changed) {
                // small bounce on the selected icon
                t.icon.scaleX = 0.7f; t.icon.scaleY = 0.7f
                t.icon.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
            }
        }
        if (notify) onSelect?.invoke(id)
    }

    /** Red count bubble on a tab's icon; 0 hides it. */
    fun setBadge(id: Int, count: Int) {
        tabs.firstOrNull { it.item.id == id }?.badge?.apply {
            text = if (count > 99) "99+" else count.toString()
            visibility = if (count > 0) View.VISIBLE else View.GONE
        }
    }
}
