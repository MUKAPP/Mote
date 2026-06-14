package com.mukapp.mote.ui.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import androidx.annotation.ColorInt

/**
 * 自由复制页（单一可选取 TextView）使用的块级装饰 Span，
 * 在保留文本可选取的前提下，尽量模仿 [MarkdownView] 原生视图树的块级外观。
 *
 * 这些 Span 仅由 [SpannedBuilder.build]（旧的整篇渲染路径，当前只服务自由复制页）使用，
 * 不影响聊天页 [MarkdownView] 的渲染。
 */

/** 在指定矩形内构造一个上/下圆角可分别控制的路径。 */
private fun buildRoundedPath(rect: RectF, radiusTop: Float, radiusBottom: Float): Path {
    val radii = floatArrayOf(
        radiusTop, radiusTop,       // top-left
        radiusTop, radiusTop,       // top-right
        radiusBottom, radiusBottom, // bottom-right
        radiusBottom, radiusBottom  // bottom-left
    )
    return Path().apply { addRoundRect(rect, radii, Path.Direction.CW) }
}

/**
 * 引用块：圆角底色 + 左侧竖条，模仿 [MarkdownView] blockquote 外观。
 * 同时作为 [LeadingMarginSpan] 为内容预留左侧缩进。
 */
class QuoteBlockSpan(
    private val spanStart: Int,
    private val spanEnd: Int,
    @param:ColorInt private val backgroundColor: Int,
    @param:ColorInt private val stripeColor: Int,
    private val stripeWidth: Int,
    private val gap: Int,
    private val cornerRadius: Float
) : LeadingMarginSpan, LineBackgroundSpan {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun getLeadingMargin(first: Boolean): Int = stripeWidth + gap

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence?, start: Int, end: Int,
        first: Boolean, layout: Layout?
    ) {
        // 背景与竖条统一在 drawBackground 中绘制，保证占满整行宽度
    }

    override fun drawBackground(
        canvas: Canvas, paint: Paint,
        left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val isFirst = start <= spanStart
        val isLast = end >= spanEnd
        val radiusTop = if (isFirst) cornerRadius else 0f
        val radiusBottom = if (isLast) cornerRadius else 0f

        rect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val bgPath = buildRoundedPath(rect, radiusTop, radiusBottom)
        this.paint.style = Paint.Style.FILL
        this.paint.color = backgroundColor
        canvas.drawPath(bgPath, this.paint)

        canvas.save()
        canvas.clipPath(bgPath)
        this.paint.color = stripeColor
        canvas.drawRect(left.toFloat(), top.toFloat(), (left + stripeWidth).toFloat(), bottom.toFloat(), this.paint)
        canvas.restore()
    }
}

/**
 * 代码块：整行宽度的圆角底色，模仿 [MarkdownView] 代码卡片外观。
 * 作为 [LeadingMarginSpan] 提供左侧内边距。
 */
class CodeBlockBackgroundSpan(
    private val spanStart: Int,
    private val spanEnd: Int,
    @param:ColorInt private val backgroundColor: Int,
    private val horizontalPadding: Int,
    private val cornerRadius: Float
) : LeadingMarginSpan, LineBackgroundSpan {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun getLeadingMargin(first: Boolean): Int = horizontalPadding

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence?, start: Int, end: Int,
        first: Boolean, layout: Layout?
    ) {
    }

    override fun drawBackground(
        canvas: Canvas, paint: Paint,
        left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val isFirst = start <= spanStart
        val isLast = end >= spanEnd
        val radiusTop = if (isFirst) cornerRadius else 0f
        val radiusBottom = if (isLast) cornerRadius else 0f

        rect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        this.paint.style = Paint.Style.FILL
        this.paint.color = backgroundColor
        canvas.drawPath(buildRoundedPath(rect, radiusTop, radiusBottom), this.paint)
    }
}

/**
 * 分割线：在占位行垂直居中处绘制一条整行宽度的细线，模仿 [MarkdownView] 的 horizontal rule。
 */
class HorizontalRuleLineSpan(
    @param:ColorInt private val lineColor: Int,
    private val thickness: Int
) : LineBackgroundSpan {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawBackground(
        canvas: Canvas, paint: Paint,
        left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val centerY = (top + bottom) / 2f
        val half = thickness.coerceAtLeast(1) / 2f
        this.paint.style = Paint.Style.FILL
        this.paint.color = lineColor
        canvas.drawRect(left.toFloat(), centerY - half, right.toFloat(), centerY + half, this.paint)
    }
}

/** 为上下标所在行补充少量高度，避免 TextView 关闭字体内边距时裁切。 */
class ScriptLineHeightSpan : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        spanstartv: Int,
        v: Int,
        fm: Paint.FontMetricsInt
    ) {
        val lineHeight = fm.descent - fm.ascent
        val extra = (lineHeight * 0.16f).toInt().coerceAtLeast(1)
        val topExtra = (extra * 0.65f).toInt().coerceAtLeast(1)
        val bottomExtra = (extra - topExtra).coerceAtLeast(1)
        fm.ascent -= topExtra
        fm.top -= topExtra
        fm.descent += bottomExtra
        fm.bottom += bottomExtra
    }
}
