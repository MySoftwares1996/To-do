package com.mysoftwares.todo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/** Circular score (0–100) that fills up with an animation; green, orange or red by score. */
class ScoreRing(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8 * d
        color = ContextCompat.getColor(context, R.color.ios_fill)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8 * d; strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 24 * d; isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.ios_label)
    }
    private var shown = 0f
    private var score = 0

    fun setScore(value: Int) {
        score = value.coerceIn(0, 100)
        arc.color = ContextCompat.getColor(context, when {
            score >= 90 -> R.color.ios_green
            score >= 60 -> R.color.ios_orange
            else -> R.color.ios_red
        })
        ValueAnimator.ofFloat(0f, score.toFloat()).apply {
            duration = 700; interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 6 * d
        val r = RectF(pad, pad, width - pad, height - pad)
        canvas.drawArc(r, 0f, 360f, false, track)
        canvas.drawArc(r, -90f, 360f * shown / 100f, false, arc)
        val y = height / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(shown.toInt().toString(), width / 2f, y, text)
    }
}
