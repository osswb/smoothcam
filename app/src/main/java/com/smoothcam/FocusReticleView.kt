package com.smoothcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** A brief focus square drawn where the user tapped, like the iPhone camera. */
class FocusReticleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(255, 196, 0)
        strokeWidth = 1.5f * density
    }
    private var cx = 0f
    private var cy = 0f
    private var shown = false

    init {
        isClickable = false
        isFocusable = false
    }

    /** Show the reticle at a point (view coordinates) and fade it out. */
    fun showAt(x: Float, y: Float) {
        cx = x; cy = y; shown = true
        alpha = 1f
        visibility = VISIBLE
        scaleX = 1.3f; scaleY = 1.3f
        pivotX = x; pivotY = y
        animate().cancel()
        animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction {
                shown = false
                visibility = GONE
            }.start()
        }.start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        val r = 34f * density
        canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint)
        val t = 8f * density
        canvas.drawLine(cx - r, cy, cx - r + t, cy, paint)
        canvas.drawLine(cx + r, cy, cx + r - t, cy, paint)
        canvas.drawLine(cx, cy - r, cx, cy - r + t, paint)
        canvas.drawLine(cx, cy + r, cx, cy + r - t, paint)
    }
}
