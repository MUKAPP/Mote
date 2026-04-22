package com.mukapp.mote.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil

class TypingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val dotRadiusPx = 3.5f * density
    private val dotSpacingPx = 7f * density
    private val horizontalPaddingPx = 2f * density
    private val verticalPaddingPx = 3f * density
    private val minDotAlpha = 0.26f
    private val maxDotAlpha = 0.92f
    private val minDotScale = 0.72f
    private val maxDotScale = 1.08f
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@TypingIndicatorView,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xFF49454F.toInt()
        )
        style = Paint.Style.FILL
    }

    private var phase = 0f
    private var shouldAnimate = false
    private var animator: ValueAnimator? = null

    fun setAnimating(animating: Boolean) {
        if (shouldAnimate == animating) {
            if (animating && isAttachedToWindow && animator?.isRunning != true) {
                startAnimator()
            }
            return
        }
        shouldAnimate = animating
        if (animating) {
            if (isAttachedToWindow) {
                startAnimator()
            }
        } else {
            stopAnimator(resetPhase = true)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (shouldAnimate) {
            startAnimator()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimator(resetPhase = false)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = ceil(
            horizontalPaddingPx * 2 + dotRadiusPx * 2 * 3 + dotSpacingPx * 2
        ).toInt()
        val desiredHeight = ceil(verticalPaddingPx * 2 + dotRadiusPx * 2 * maxDotScale).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentWidth = dotRadiusPx * 2 * 3 + dotSpacingPx * 2
        val startX = (width - contentWidth) / 2f + dotRadiusPx
        val centerY = height / 2f

        repeat(3) { index ->
            val intensity = computeDotIntensity(index)
            dotPaint.alpha = ((minDotAlpha + (maxDotAlpha - minDotAlpha) * intensity) * 255).toInt()
            val radius = dotRadiusPx * (minDotScale + (maxDotScale - minDotScale) * intensity)
            val centerX = startX + index * (dotRadiusPx * 2 + dotSpacingPx)
            canvas.drawCircle(centerX, centerY, radius, dotPaint)
        }
    }

    private fun startAnimator() {
        val existing = animator
        if (existing != null) {
            if (!existing.isRunning) {
                existing.start()
            }
            return
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { valueAnimator ->
                phase = valueAnimator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator(resetPhase: Boolean) {
        animator?.cancel()
        animator = null
        if (resetPhase) {
            phase = 0f
            invalidate()
        }
    }

    private fun computeDotIntensity(index: Int): Float {
        val shifted = wrapPhase(phase - index * 0.18f)
        return when {
            shifted < 0.25f -> shifted / 0.25f
            shifted < 0.5f -> (0.5f - shifted) / 0.25f
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    private fun wrapPhase(value: Float): Float {
        return when {
            value < 0f -> value + 1f
            value >= 1f -> value - 1f
            else -> value
        }
    }
}
