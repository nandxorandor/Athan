package com.ahmedkhalaf.athan

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The ring around the next-prayer countdown: how much of the stretch since the
 * last prayer has gone. A dotted track for the whole circle, a solid arc for
 * the part already passed, and a dot where it ends - so the countdown reads
 * at a glance as "nearly there" or "hours yet" before a digit is read.
 *
 * Clockwise from the top in both languages: it is a clock face, and clocks do
 * not mirror.
 */
class NextPrayerRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 0 just after the previous prayer, 1 at the next. */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val stroke = 5f * density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = context.getColor(R.color.icon_idle)
        pathEffect = DashPathEffect(floatArrayOf(2f * density, 5f * density), 0f)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.accent)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
    }
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) / 2f - stroke * 1.5f
        val cx = width / 2f
        val cy = height / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawOval(bounds, track)
        if (progress <= 0f) return
        val sweep = 360f * progress
        canvas.drawArc(bounds, -90f, sweep, false, arc)

        val end = Math.toRadians((sweep - 90f).toDouble())
        canvas.drawCircle(
            cx + radius * cos(end).toFloat(),
            cy + radius * sin(end).toFloat(),
            stroke * 1.1f,
            dot,
        )
    }
}
