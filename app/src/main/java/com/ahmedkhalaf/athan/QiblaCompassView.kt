package com.ahmedkhalaf.athan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * A compass rose whose dial turns so that N holds true north, with the Kaaba
 * fixed on the dial at the qibla bearing and a needle pointing at it. Turn the
 * phone until the needle meets the index mark at the top.
 *
 * Drawn on a Canvas rather than assembled from vector drawables: a dial needs
 * text (degree numbers, cardinal letters) and VectorDrawable has no text
 * element, so the labels would otherwise have to be hand-traced as paths.
 */
class QiblaCompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Device heading, degrees clockwise from true north. Turns the dial. */
    var heading: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Qibla bearing, degrees clockwise from true north. Fixed on the dial. */
    var qiblaBearing: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Highlights the index when the needle is on target. */
    var aligned: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val face = paint(R.color.surface, Paint.Style.FILL)
    private val rim = paint(R.color.text_dim, Paint.Style.STROKE, dp(1.5f)).apply { alpha = 90 }
    private val rimAligned = paint(R.color.accent, Paint.Style.STROKE, dp(2.5f))
    private val tickMajor = paint(R.color.text, Paint.Style.STROKE, dp(2f)).apply { alpha = 210 }
    private val tickMinor = paint(R.color.text_dim, Paint.Style.STROKE, dp(1f)).apply { alpha = 140 }

    private val degreeText = textPaint(R.color.text_dim, dp(9f))
    private val cardinalText = textPaint(R.color.text, dp(17f), bold = true)
    private val interText = textPaint(R.color.text_dim, dp(11f))
    private val northText = textPaint(R.color.needle_head, dp(19f), bold = true)

    private val needleHead = paint(R.color.needle_head, Paint.Style.FILL)
    private val needleTail = paint(R.color.needle_tail, Paint.Style.FILL)
    private val hub = paint(R.color.bg, Paint.Style.FILL)
    private val hubRing = paint(R.color.text_dim, Paint.Style.STROKE, dp(2f))

    private val kaabaBody = paint(R.color.kaaba_body, Paint.Style.FILL)
    private val kaabaBand = paint(R.color.kaaba_band, Paint.Style.FILL)
    private val kaabaEdge = paint(R.color.text, Paint.Style.STROKE, dp(1f)).apply { alpha = 120 }

    private val index = paint(R.color.text_dim, Paint.Style.FILL)
    private val indexAligned = paint(R.color.accent, Paint.Style.FILL)

    private val headPath = Path()
    private val tailPath = Path()
    private val indexPath = Path()
    private val kaabaRect = RectF()

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private fun paint(colorRes: Int, style: Paint.Style, stroke: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, colorRes)
            this.style = style
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }

    private fun textPaint(colorRes: Int, size: Float, bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, colorRes)
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - dp(6f)

        // Built once per size rather than per frame: onDraw runs at sensor rate.
        val tip = cy - radius * 0.66f
        val waist = dp(11f)
        headPath.reset()
        headPath.moveTo(cx, tip)
        headPath.lineTo(cx - waist, cy)
        headPath.lineTo(cx, cy + dp(6f))
        headPath.lineTo(cx + waist, cy)
        headPath.close()

        val tail = cy + radius * 0.52f
        tailPath.reset()
        tailPath.moveTo(cx, tail)
        tailPath.lineTo(cx - waist * 0.8f, cy)
        tailPath.lineTo(cx, cy - dp(6f))
        tailPath.lineTo(cx + waist * 0.8f, cy)
        tailPath.close()

        val notch = dp(9f)
        indexPath.reset()
        indexPath.moveTo(cx, cy - radius + dp(3f))
        indexPath.lineTo(cx - notch, cy - radius - dp(9f))
        indexPath.lineTo(cx + notch, cy - radius - dp(9f))
        indexPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        canvas.drawCircle(cx, cy, radius, face)
        canvas.drawCircle(cx, cy, radius, if (aligned) rimAligned else rim)

        canvas.save()
        // Negative: turning the phone right must swing the dial left so that N
        // keeps pointing at true north.
        canvas.rotate(-heading, cx, cy)
        drawDial(canvas)
        drawKaaba(canvas)
        drawNeedle(canvas)
        canvas.restore()

        canvas.drawCircle(cx, cy, dp(7f), hub)
        canvas.drawCircle(cx, cy, dp(7f), hubRing)
        canvas.drawPath(indexPath, if (aligned) indexAligned else index)
    }

    private fun drawDial(canvas: Canvas) {
        val outer = cy - radius + dp(7f)

        for (degrees in 0 until 360 step 2) {
            val length = when {
                degrees % 30 == 0 -> dp(13f)
                degrees % 10 == 0 -> dp(8f)
                else -> dp(4f)
            }
            canvas.save()
            canvas.rotate(degrees.toFloat(), cx, cy)
            canvas.drawLine(
                cx, outer, cx, outer + length,
                if (degrees % 30 == 0) tickMajor else tickMinor
            )
            canvas.restore()
        }

        for (degrees in 0 until 360 step 30) {
            canvas.save()
            canvas.rotate(degrees.toFloat(), cx, cy)
            canvas.drawText(degrees.toString(), cx, outer + dp(13f) + dp(11f), degreeText)
            canvas.restore()
        }

        CARDINALS.forEach { (degrees, label) ->
            val paint = when {
                label == "N" -> northText
                label.length == 1 -> cardinalText
                else -> interText
            }
            canvas.save()
            canvas.rotate(degrees.toFloat(), cx, cy)
            canvas.drawText(label, cx, cy - radius * 0.60f, paint)
            canvas.restore()
        }
    }

    private fun drawNeedle(canvas: Canvas) {
        canvas.save()
        canvas.rotate(qiblaBearing, cx, cy)
        canvas.drawPath(tailPath, needleTail)
        canvas.drawPath(headPath, needleHead)
        canvas.restore()
    }

    /** Sits on the rim at the qibla bearing, so it always marks Mecca. */
    private fun drawKaaba(canvas: Canvas) {
        canvas.save()
        canvas.rotate(qiblaBearing, cx, cy)
        val size = dp(13f)
        val centerY = cy - radius + dp(30f)
        kaabaRect.set(cx - size, centerY - size, cx + size, centerY + size)
        canvas.drawRoundRect(kaabaRect, dp(3f), dp(3f), kaabaBody)
        canvas.drawRect(
            cx - size, centerY - size * 0.38f, cx + size, centerY - size * 0.06f, kaabaBand
        )
        canvas.drawRoundRect(kaabaRect, dp(3f), dp(3f), kaabaEdge)
        canvas.restore()
    }

    private companion object {
        val CARDINALS = listOf(
            0 to "N", 45 to "NE", 90 to "E", 135 to "SE",
            180 to "S", 225 to "SW", 270 to "W", 315 to "NW",
        )
    }
}
