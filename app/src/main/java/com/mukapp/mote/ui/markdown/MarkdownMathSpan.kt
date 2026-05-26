package com.mukapp.mote.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/** 将 RaTeX 渲染出的透明位图作为行内公式绘制，并按公式高度调整文本行高。 */
class MarkdownMathSpan(
    private val bitmap: Bitmap,
    private val heightPx: Float,
    private val depthPx: Float
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let {
            it.ascent = -heightPx.toInt()
            it.descent = depthPx.toInt()
            it.top = it.ascent
            it.bottom = it.descent
        }
        return bitmap.width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        canvas.save()
        canvas.translate(x, y - heightPx)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.restore()
    }
}
