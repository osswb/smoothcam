package com.smoothcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View

/** Darkens everything except the "hole" (the part of the viewfinder that will be captured). */
class ScrimView(context: Context) : View(context) {
    private val hole = Rect()
    private val dim = Color.argb(150, 0, 0, 0)

    init {
        isClickable = false
        isFocusable = false
    }

    fun setHole(left: Int, top: Int, right: Int, bottom: Int) {
        if (hole.left == left && hole.top == top && hole.right == right && hole.bottom == bottom) return
        hole.set(left, top, right, bottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        if (!hole.isEmpty) canvas.clipOutRect(hole)
        canvas.drawColor(dim)
        canvas.restore()
    }
}
