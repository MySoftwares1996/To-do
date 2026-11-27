package com.mysoftwares.todo

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

/** Builds iOS-style grouped lists from code: section labels, white rounded groups, rows, footers. */
class IosList(private val ctx: Context, private val root: LinearLayout) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    fun clear() = root.removeAllViews()

    fun label(text: String) {
        root.addView(TextView(ctx).apply {
            this.text = text.uppercase()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(ctx, R.color.ios_secondary))
            setPadding(dp(16), dp(20), dp(16), dp(6))
        })
    }

    fun footer(text: String) {
        root.addView(TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(ctx, R.color.ios_secondary))
            setPadding(dp(16), dp(6), dp(16), 0)
        })
    }

    /** A white rounded group; add rows with [Group.row]. */
    fun group(): Group {
        val card = MaterialCardView(ctx, null, 0).apply {
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.ios_cell))
            radius = 12 * d
            cardElevation = 0f
            strokeWidth = 0
        }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)
        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = if (root.childCount > 0 && root.getChildAt(root.childCount - 1) is MaterialCardView) dp(16) else 0 })
        return Group(box)
    }

    inner class Group(private val box: LinearLayout) {
        /** A row: optional colour dot, title, value on the right. Returns the row view. */
        fun row(
            title: String, value: String = "", dot: Int? = null, titleColor: Int? = null,
            centered: Boolean = false, onClick: (() -> Unit)? = null,
        ): View {
            if (box.childCount > 0) box.addView(View(ctx).apply {
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.ios_separator))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = if (dot != null) dp(44) else dp(16)
            })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                if (onClick != null) {
                    val tv = TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { onClick() }
                }
            }
            if (dot != null) row.addView(View(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_circle)!!.mutate().also { it.setTint(dot) }
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(14) })
            row.addView(TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(titleColor ?: ContextCompat.getColor(ctx, R.color.ios_label))
            }, LinearLayout.LayoutParams(if (centered) LinearLayout.LayoutParams.WRAP_CONTENT else 0, LinearLayout.LayoutParams.WRAP_CONTENT, if (centered) 0f else 1f))
            if (value.isNotEmpty()) row.addView(TextView(ctx).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(ContextCompat.getColor(ctx, R.color.ios_secondary))
            })
            box.addView(row)
            return row
        }
    }
}
