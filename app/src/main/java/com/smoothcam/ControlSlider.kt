package com.smoothcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A horizontal "ruler" picker, like the manual controls in the iPhone camera: a strip of notches
 * that slides under a fixed centre caret. The current option sits under the caret; dragging moves
 * through the options with a haptic tick at each one. Tapping left or right of centre steps by one.
 */
class ControlSlider(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val gap = 26f * density          // pixels between notches
    private val yellow = Color.rgb(255, 196, 0)

    var title: String = ""
        set(v) { field = v; invalidate() }
    var labels: List<String> = emptyList()
        set(v) {
            field = v
            if (index >= v.size) index = (v.size - 1).coerceAtLeast(0)
            offset = 0f
            invalidate()
        }
    var index: Int = 0
        set(v) {
            val c = v.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
            if (c != field) {
                field = c
                invalidate()
            }
        }
    /** Called with the new index when the user moves the slider. */
    var onIndexChanged: ((Int) -> Unit)? = null

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 1.5f * density
    }
    private val tickNearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = yellow
        strokeWidth = 3f * density
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = yellow
        textSize = 20f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }

    private var offset = 0f // horizontal drag offset within the current notch, -gap/2..gap/2
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; downX = e.x; downY = e.y; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) dragging = true
                if (dragging) {
                    moveBy(e.x - lastX)
                    lastX = e.x
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                    // Tap left or right of centre: step one notch.
                    val dir = if (e.x < width / 2f) -1 else 1
                    setIndexUser(index + dir)
                }
                offset = 0f
                invalidate()
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun moveBy(dx: Float) {
        if (labels.isEmpty()) return
        offset += dx
        while (offset > gap / 2 && index > 0) {
            offset -= gap
            setIndexUser(index - 1)
        }
        while (offset < -gap / 2 && index < labels.size - 1) {
            offset += gap
            setIndexUser(index + 1)
        }
        // Don't let the strip drift past the ends.
        if (index == 0 && offset > 0f) offset = 0f
        if (index == labels.size - 1 && offset < 0f) offset = 0f
        invalidate()
    }

    private fun setIndexUser(i: Int) {
        val c = i.coerceIn(0, labels.size - 1)
        if (c == index) return
        index = c
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onIndexChanged?.invoke(c)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        if (title.isNotEmpty()) canvas.drawText(title, cx, 16f * density, titlePaint)
        if (labels.isEmpty()) return

        val baseY = h - 30f * density
        val tickTop = baseY - 12f * density
        val longTop = baseY - 18f * density
        // The selected notch sits at the centre, shifted by the in-progress drag.
        val centerShift = offset
        val visible = (w / gap).toInt() / 2 + 2
        for (d in -visible..visible) {
            val i = index + d
            if (i < 0 || i >= labels.size) continue
            val x = cx + d * gap + centerShift
            if (x < -gap || x > w + gap) continue
            val near = abs(x - cx) < gap * 0.5f
            canvas.drawLine(x, if (near) longTop else tickTop, x, baseY, if (near) tickNearPaint else tickPaint)
            // Short label under major notches (every few) to orient the user.
            if (d % 2 == 0 && !near) {
                val lp = Paint(titlePaint).apply { textSize = 10f * density; color = Color.argb(150, 255, 255, 255) }
                canvas.drawText(short(labels[i]), x, baseY + 13f * density, lp)
            }
        }
        // Fixed centre caret and the current value above it.
        canvas.drawLine(cx, baseY - 22f * density, cx, baseY + 4f * density, caretPaint)
        canvas.drawText(labels[index], cx, baseY - 30f * density, valuePaint)
    }

    private fun short(s: String): String {
        // Keep tick labels tiny: drop a leading tag like "ISO " / "S ".
        val t = s.substringAfterLast(' ')
        return if (t.length <= 5) t else t.take(5)
    }

    fun setOptions(title: String, labels: List<String>, index: Int) {
        this.title = title
        this.labels = labels
        this.index = index
        offset = 0f
        invalidate()
    }
}
