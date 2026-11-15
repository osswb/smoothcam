package com.smoothcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * iPhone-style zoom dial: a half-circle scale that turns under your finger.
 * The scale is logarithmic (every doubling of zoom is the same angle), with long ticks and labels
 * at the lens presets and a light haptic tick when you pass one. It hides itself after a moment.
 */
class ZoomDialView(context: Context) : View(context) {

    var minZoom = 1f
    var maxZoom = 1f
    var presets: List<Float> = emptyList()
    var zoom = 1f
        set(v) {
            field = v
            invalidate()
        }
    var onZoomChanged: ((Float) -> Unit)? = null
    var onShown: (() -> Unit)? = null
    var onHidden: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val degPerStop = 34f // angle for each doubling of zoom
    private val yellow = Color.rgb(255, 196, 0)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(165, 0, 0, 0) }
    private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 1.2f * density
    }
    private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = yellow
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = yellow
        strokeWidth = 2.5f * density
    }

    private var lastX = 0f
    private val hideRunnable = Runnable {
        animate().alpha(0f).setDuration(200).withEndAction {
            visibility = GONE
            onHidden?.invoke()
        }.start()
    }

    /** Show the dial (or keep it shown); it hides 1.5 s after the last interaction. */
    fun show() {
        removeCallbacks(hideRunnable)
        animate().cancel()
        if (visibility != VISIBLE) {
            alpha = 0f
            visibility = VISIBLE
            onShown?.invoke()
        }
        animate().alpha(1f).setDuration(120).start()
        postDelayed(hideRunnable, 1500)
    }

    fun beginDrag(rawX: Float) {
        lastX = rawX
        show()
    }

    fun dragTo(rawX: Float) {
        dragBy(rawX - lastX)
        lastX = rawX
    }

    /** Finger moving left turns the dial towards higher zoom, like on iPhone. */
    private fun dragBy(dx: Float) {
        if (width == 0) return
        val radius = width * 0.95f
        val deg = Math.toDegrees((dx / radius).toDouble()).toFloat()
        val stops = -deg / degPerStop
        val newZoom = (zoom * 2.0.pow(stops.toDouble()).toFloat()).coerceIn(minZoom, maxZoom)
        for (p in presets) {
            if ((zoom - p) * (newZoom - p) < 0f) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                break
            }
        }
        zoom = newZoom
        onZoomChanged?.invoke(newZoom)
        show()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginDrag(e.rawX)
            MotionEvent.ACTION_MOVE -> dragTo(e.rawX)
            MotionEvent.ACTION_UP -> {
                show()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> show()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = w * 0.95f
        val cx = w / 2f
        val top = 34f * density // room for the value above the arc
        val cy = top + radius

        // Dark band with a curved top edge (a huge circle, clipped by the view).
        c.drawCircle(cx, cy, radius, bgPaint)

        val lz = log2(zoom)
        val minL = log2(minZoom)
        val maxL = log2(maxZoom)

        // Minor ticks every tenth of a stop.
        val minorLen = 7f * density
        var i = ceil(minL * 10f).toInt()
        val iEnd = floor(maxL * 10f).toInt()
        while (i <= iEnd) {
            val angle = (i / 10f - lz) * degPerStop
            if (abs(angle) <= 80f) {
                c.save()
                c.rotate(angle, cx, cy)
                c.drawLine(cx, cy - radius + 2f * density, cx, cy - radius + 2f * density + minorLen, minorPaint)
                c.restore()
            }
            i++
        }

        // Lens presets: long ticks with labels.
        val majorLen = 14f * density
        for (p in presets + listOf(minZoom, maxZoom).filter { z -> presets.none { abs(it - z) < 0.01f } }) {
            val angle = (log2(p) - lz) * degPerStop
            if (abs(angle) > 80f) continue
            c.save()
            c.rotate(angle, cx, cy)
            c.drawLine(cx, cy - radius + 2f * density, cx, cy - radius + 2f * density + majorLen, majorPaint)
            c.drawText(label(p), cx, cy - radius + majorLen + 18f * density, labelPaint)
            c.restore()
        }

        // Fixed indicator at the top and the current value above it.
        val tri = Path().apply {
            moveTo(cx - 6f * density, top - 4f * density)
            lineTo(cx + 6f * density, top - 4f * density)
            lineTo(cx, top + 4f * density)
            close()
        }
        c.drawPath(tri, indicatorPaint)
        c.drawLine(cx, top, cx, top + majorLen + 4f * density, indicatorPaint)
        c.drawText(label(zoom), cx, top - 10f * density, valuePaint)
        if (h <= 0f) return
    }

    companion object {
        fun label(z: Float): String = when {
            z < 1f -> String.format(Locale.US, "%.1f", z).removePrefix("0")
            abs(z - z.roundToInt()) < 0.05f -> "${z.roundToInt()}×"
            else -> String.format(Locale.US, "%.1f×", z)
        }
    }
}

/**
 * A row (the lens buttons) that turns a sideways drag into a dial gesture, like iPhone: tap a
 * button to jump to that lens, or drag across the row to bring up the dial and turn it.
 */
class DragRowLayout(context: Context) : LinearLayout(context) {
    var onDragStart: ((Float) -> Unit)? = null
    var onDragMove: ((Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && abs(e.rawX - downX) > slop) {
                dragging = true
                onDragStart?.invoke(e.rawX)
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // A touch that starts between buttons comes straight here, without interception.
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.rawX - downX) > slop) {
                    dragging = true
                    onDragStart?.invoke(e.rawX)
                }
                if (dragging) onDragMove?.invoke(e.rawX)
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
