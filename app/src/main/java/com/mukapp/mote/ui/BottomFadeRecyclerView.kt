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

    init {
        // 关闭默认的 fading edge
        isVerticalFadingEdgeEnabled = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) {
            // 创建从底部向上褪色的渐变
            // 底部是不透明（会把内容擦除），上部是透明（保留内容）
            fadePaint.shader = LinearGradient(
                0f, h.toFloat() - fadeHeight, 0f, h.toFloat(),
                Color.TRANSPARENT, Color.BLACK,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        // 不需要渐变时跳过 saveLayer，避免每帧分配离屏缓冲区
        if (!canScrollVertically(1)) {
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
