package com.mukapp.mote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * 自定义 RecyclerView，只在底部物理边缘处显示虚化渐变效果。
 * 使用与页面背景同色的渐变遮罩，避免滚动时每帧分配 GPU 离屏缓冲。
 *
 * 优化：通过滚动状态监听缓存 shouldFade 标志，避免每帧调用 canScrollVertically；
 * 不需要渐变时完全跳过额外绘制，减少滚动阶段 GPU 压力。
 */
class BottomFadeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fadeHeight: Int = (80 * resources.displayMetrics.density).toInt()
    private val fadeColor: Int by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    }

    /** 缓存是否需要底部渐变，通过滚动监听更新，避免每帧查询 */
    private var shouldFade: Boolean = false

    init {
        isVerticalFadingEdgeEnabled = false
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateFadeState()
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) {
            fadePaint.shader = LinearGradient(
                0f, h.toFloat() - fadeHeight, 0f, h.toFloat(),
                ColorUtils.setAlphaComponent(fadeColor, 0),
                fadeColor,
                Shader.TileMode.CLAMP
            )
        }
        updateFadeState()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateFadeState()
    }

    private fun updateFadeState() {
        val newShouldFade = canScrollVertically(1)
        if (newShouldFade != shouldFade) {
            shouldFade = newShouldFade
            invalidate()
        }
    }

    override fun draw(canvas: Canvas) {
        if (!shouldFade) {
            super.draw(canvas)
            return
        }

        super.draw(canvas)
        canvas.drawRect(
            0f, (height - fadeHeight).toFloat(),
            width.toFloat(), height.toFloat(),
            fadePaint
        )
    }
}
