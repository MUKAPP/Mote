package com.mukapp.mote.ui.smooth

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

class SmoothCornerDrawable(
    @param:ColorInt private var fillColor: Int = Color.TRANSPARENT,
    private var cornerRadius: Float = 0f,
    @param:ColorInt private var strokeColor: Int = Color.TRANSPARENT,
    private var strokeWidth: Float = 0f
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fillPath = Path()
    private val strokePath = Path()
    private val fillRect = RectF()
    private val strokeRect = RectF()
    private var pathsDirty = true
    private var drawableAlpha = 255

    init {
        updatePaints()
    }

    override fun draw(canvas: Canvas) {
        updatePathsIfNeeded()
        if (fillPaint.alpha > 0) {
            canvas.drawPath(fillPath, fillPaint)
        }
        if (strokeWidth > 0f && strokePaint.alpha > 0) {
            canvas.drawPath(strokePath, strokePaint)
        }
    }

    fun setFillColor(@ColorInt color: Int) {
        if (fillColor == color) return
        fillColor = color
        updatePaints()
        invalidateSelf()
    }

    fun setCornerRadius(radius: Float) {
        val nextRadius = radius.coerceAtLeast(0f)
        if (cornerRadius == nextRadius) return
        cornerRadius = nextRadius
        pathsDirty = true
        invalidateSelf()
    }

    fun setStroke(width: Float, @ColorInt color: Int) {
        val nextWidth = width.coerceAtLeast(0f)
        if (strokeWidth == nextWidth && strokeColor == color) return
        strokeWidth = nextWidth
        strokeColor = color
        pathsDirty = true
        updatePaints()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        pathsDirty = true
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        updatePaints()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOutline(outline: Outline) {
        updatePathsIfNeeded()
        @Suppress("DEPRECATION")
        outline.setConvexPath(fillPath)
        outline.alpha = Color.alpha(fillColor) / 255f
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun updatePaints() {
        fillPaint.color = fillColor
        fillPaint.alpha = Color.alpha(fillColor) * drawableAlpha / 255
        strokePaint.color = strokeColor
        strokePaint.alpha = Color.alpha(strokeColor) * drawableAlpha / 255
        strokePaint.strokeWidth = strokeWidth
    }

    private fun updatePathsIfNeeded() {
        if (!pathsDirty) return
        fillPath.reset()
        strokePath.reset()

        fillRect.set(bounds)
        SmoothCorners.addSmoothRoundRect(fillPath, fillRect, cornerRadius)

        val strokeInset = strokeWidth / 2f
        strokeRect.set(
            bounds.left + strokeInset,
            bounds.top + strokeInset,
            bounds.right - strokeInset,
            bounds.bottom - strokeInset
        )
        SmoothCorners.addSmoothRoundRect(
            strokePath,
            strokeRect,
            (cornerRadius - strokeInset).coerceAtLeast(0f)
        )
        pathsDirty = false
    }
}
