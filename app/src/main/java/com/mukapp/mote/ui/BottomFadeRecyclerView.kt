package com.mukapp.mote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 自定义 RecyclerView，只在底部物理边缘处显示虚化渐变效果。
 * 使用 PorterDuff.Mode.DST_OUT 手动绘制遮罩，实现无视 padding 的贴边虚化。
 *
 * 优化：通过滚动状态监听缓存 shouldFade 标志，避免每帧调用 canScrollVertically；
 * 不需要渐变时完全跳过 saveLayer，减少 GPU 离屏缓冲区分配。
 */
class BottomFadeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private var fadeHeight: Int = (80 * resources.displayMetrics.density).toInt()

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
                Color.TRANSPARENT, Color.BLACK,
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

        // 仅在需要底部渐变时使用离屏缓冲 + DST_OUT 擦除
        val saveCount = canvas.saveLayer(
            0f, 0f, width.toFloat(), height.toFloat(),
            null
        )
        super.draw(canvas)
        canvas.drawRect(
            0f, (height - fadeHeight).toFloat(),
            width.toFloat(), height.toFloat(),
            fadePaint
        )
        canvas.restoreToCount(saveCount)
    }
}
