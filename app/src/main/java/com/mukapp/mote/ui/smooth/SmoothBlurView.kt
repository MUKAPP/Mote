package com.mukapp.mote.ui.smooth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import eightbitlab.com.blurview.BlurView

class SmoothBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BlurView(context, attrs, defStyleAttr) {
    private val clipPath = Path()
    private val clipRect = RectF()
    private var clipDirty = true

    var smoothCornerRadius: Float = 0f
        set(value) {
            val nextValue = value.coerceAtLeast(0f)
            if (field == nextValue) return
            field = nextValue
            clipDirty = true
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipDirty = true
    }

    override fun draw(canvas: Canvas) {
        if (smoothCornerRadius <= 0f || width <= 0 || height <= 0) {
            super.draw(canvas)
            return
        }
        updateClipPathIfNeeded()
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun updateClipPathIfNeeded() {
        if (!clipDirty) return
        clipPath.reset()
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        SmoothCorners.addSmoothRoundRect(clipPath, clipRect, smoothCornerRadius)
        clipDirty = false
    }
}
