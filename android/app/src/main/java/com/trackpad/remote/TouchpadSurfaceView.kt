package com.trackpad.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

class TouchpadSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_grid_color)
        style = Paint.Style.FILL
    }

    private var radiusPx = 0f
    private var spacingPx = 0f

    init {
        setWillNotDraw(false)
        val density = context.resources.displayMetrics.density
        radiusPx = 1.8f * density
        spacingPx = 28f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0 || spacingPx <= 0) return

        // Compute offsets to center the dot grid
        val cols = (w / spacingPx).toInt()
        val rows = (h / spacingPx).toInt()
        val startX = (w - (cols - 1) * spacingPx) / 2f
        val startY = (h - (rows - 1) * spacingPx) / 2f

        var r = 0
        while (r < rows) {
            val y = startY + r * spacingPx
            var c = 0
            while (c < cols) {
                val x = startX + c * spacingPx
                canvas.drawCircle(x, y, radiusPx, dotPaint)
                c++
            }
            r++
        }
    }
}
