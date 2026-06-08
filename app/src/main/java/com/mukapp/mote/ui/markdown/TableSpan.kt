package com.mukapp.mote.ui.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.style.ReplacementSpan
import androidx.annotation.ColorInt
import com.mukapp.mote.ui.smooth.SmoothCorners
import com.mukapp.mote.util.dp

/**
 * 自定义 ReplacementSpan，使用 Canvas 绘制带网格线的表格。
 *
 * 整个表格占据一个占位字符的位置，通过 [getSize] 返回所需宽度，
 * 通过 [draw] 绘制表头、数据行、网格线等。
 */
class TableSpan(
    private val headers: List<String>,
    private val rows: List<List<String>>,
    private val alignments: List<MdBlock.Alignment>,
    @param:ColorInt private val headerBgColor: Int,
    @param:ColorInt private val altRowBgColor: Int,
    @param:ColorInt private val gridLineColor: Int,
    @param:ColorInt private val headerTextColor: Int,
    @param:ColorInt private val cellTextColor: Int,
    private val availableWidth: Int
) : ReplacementSpan() {

    private val cellPaddingH = 10f.dp
    private val cellPaddingV = 8f.dp
    private val gridLineWidth = 1f.dp
    private val cornerRadius = 8f.dp
    private val fontSize = 13f.dp
    private val headerFontSize = 13f.dp

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = headerFontSize
        color = headerTextColor
    }

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = fontSize
        color = cellTextColor
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridLineColor
        strokeWidth = gridLineWidth
        style = Paint.Style.STROKE
    }

    private val colCount = headers.size
    private val rowCount = rows.size

    /** 计算每列的最佳宽度（基于内容） */
    private fun calculateColumnWidths(): FloatArray {
        val widths = FloatArray(colCount)
        for (j in 0 until colCount) {
            widths[j] = headerPaint.measureText(headers[j]) + cellPaddingH * 2
        }
        for (row in rows) {
            for (j in 0 until minOf(colCount, row.size)) {
                val w = cellPaint.measureText(row[j]) + cellPaddingH * 2
                if (w > widths[j]) widths[j] = w
            }
        }

        // 如果总宽度小于可用宽度，按比例扩展
        val totalWidth = widths.sum()
        val usableWidth = availableWidth.toFloat()
        if (totalWidth < usableWidth && usableWidth > 0) {
            val scale = usableWidth / totalWidth
            for (j in widths.indices) widths[j] *= scale
        }

        return widths
    }

    private fun rowHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) + cellPaddingV * 2
    }

    private fun totalHeight(): Float {
        val hHeight = rowHeight(headerPaint)
        val rHeight = rowHeight(cellPaint)
        return hHeight + rHeight * rowCount
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val height = totalHeight().toInt()
        if (fm != null) {
            fm.ascent = -height
            fm.top = -height
            fm.descent = 0
            fm.bottom = 0
        }
        return availableWidth
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
        val colWidths = calculateColumnWidths()
        val totalW = colWidths.sum()
        val hHeight = rowHeight(headerPaint)
        val rHeight = rowHeight(cellPaint)
        val totalH = totalHeight()

        // 绘制起始位置（水平居中）
        val startX = x + maxOf(0f, (availableWidth - totalW) / 2f)
        val startY = (y - totalH).toFloat()

        // 绘制圆角背景裁剪区域
        val tableRect = RectF(startX, startY, startX + totalW, startY + totalH)

        // 我们使用裁剪来确保内部的矩形背景不会超出圆角边框
        canvas.save()
        val clipPath = android.graphics.Path().apply {
            SmoothCorners.addSmoothRoundRect(this, tableRect, cornerRadius)
        }
        canvas.clipPath(clipPath)

        // 绘制表头背景
        bgPaint.color = headerBgColor
        canvas.drawRect(
            RectF(startX, startY, startX + totalW, startY + hHeight),
            bgPaint
        )

        // 绘制数据行背景（交替颜色）
        for (i in 0 until rowCount) {
            val rowY = startY + hHeight + rHeight * i
            if (i % 2 == 1) {
                bgPaint.color = altRowBgColor
                canvas.drawRect(RectF(startX, rowY, startX + totalW, rowY + rHeight), bgPaint)
            }
        }
        
        canvas.restore()

        // 绘制表头文字
        val headerFm = headerPaint.fontMetrics
        val headerBaseline = startY + cellPaddingV - headerFm.ascent
        var cx = startX
        for (j in 0 until colCount) {
            val cellText = headers[j]
            val textX = alignedTextX(cellText, headerPaint, cx, colWidths[j], j)
            canvas.drawText(cellText, textX, headerBaseline, headerPaint)
            cx += colWidths[j]
        }

        // 绘制数据行文字
        val cellFm = cellPaint.fontMetrics
        for (i in 0 until rowCount) {
            val rowY = startY + hHeight + rHeight * i
            val baseline = rowY + cellPaddingV - cellFm.ascent
            cx = startX
            for (j in 0 until colCount) {
                val cellText = if (j < rows[i].size) rows[i][j] else ""
                val textX = alignedTextX(cellText, cellPaint, cx, colWidths[j], j)
                canvas.drawText(cellText, textX, baseline, cellPaint)
                cx += colWidths[j]
            }
        }

        // 绘制网格线 — 水平线
        // 表头底部分隔线
        canvas.drawLine(startX, startY + hHeight, startX + totalW, startY + hHeight, linePaint)

        // 数据行之间的分隔线
        for (i in 1 until rowCount) {
            val lineY = startY + hHeight + rHeight * i
            canvas.drawLine(startX, lineY, startX + totalW, lineY, linePaint)
        }

        // 绘制网格线 — 竖直线（列间）
        cx = startX
        for (j in 1 until colCount) {
            cx += colWidths[j - 1]
            canvas.drawLine(cx, startY, cx, startY + totalH, linePaint)
        }

        // 绘制外边框（圆角）
        canvas.drawPath(clipPath, linePaint)
    }

    /** 根据对齐方式计算文字绘制的 X 坐标 */
    private fun alignedTextX(
        text: String,
        paint: Paint,
        cellLeft: Float,
        cellWidth: Float,
        colIndex: Int
    ): Float {
        val align = if (colIndex < alignments.size) alignments[colIndex] else MdBlock.Alignment.LEFT
        val textWidth = paint.measureText(text)
        return when (align) {
            MdBlock.Alignment.LEFT -> cellLeft + cellPaddingH
            MdBlock.Alignment.RIGHT -> cellLeft + cellWidth - cellPaddingH - textWidth
            MdBlock.Alignment.CENTER -> cellLeft + (cellWidth - textWidth) / 2f
        }
    }
}
