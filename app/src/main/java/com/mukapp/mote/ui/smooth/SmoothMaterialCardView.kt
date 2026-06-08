package com.mukapp.mote.ui.smooth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import com.google.android.material.card.MaterialCardView

open class SmoothMaterialCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val clipPath = Path()
    private val clipRect = RectF()
    private var clipDirty = true

    init {
        applySmoothCorners()
    }

    fun applySmoothCorners() {
        shapeAppearanceModel = SmoothCorners.toSmoothShapeAppearanceModel(shapeAppearanceModel)
        clipDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipDirty = true
    }

    override fun draw(canvas: Canvas) {
        if (radius <= 0f || width <= 0 || height <= 0) {
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
        SmoothCorners.addSmoothRoundRect(clipPath, clipRect, radius)
        clipDirty = false
    }
}
