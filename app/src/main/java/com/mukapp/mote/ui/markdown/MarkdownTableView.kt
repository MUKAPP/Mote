package com.mukapp.mote.ui.markdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.mukapp.mote.util.dp
import kotlin.math.ceil

class MarkdownTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cellPaddingH = 10f.dp
    private val cellPaddingV = 8f.dp
    private val gridLineWidth = 1f.dp
    private val cornerRadius = 8f.dp
    private val fontSize = 13f.dp
    private val headerFontSize = 13f.dp

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = headerFontSize
    }

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = fontSize
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = gridLineWidth
        style = Paint.Style.STROKE
    }
    private val outerStrokeInset = ceil(gridLineWidth / 2f).toInt()

    private var headers: List<String> = emptyList()
    private var rows: List<List<String>> = emptyList()
    private var alignments: List<MdBlock.Alignment> = emptyList()

    private var columnWidths: FloatArray = FloatArray(0)
    private var cachedTotalWidth: Float = 0f
    private var cachedTotalHeight: Float = 0f

    private val headerBgColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt()),
            0x88
        )
    }

    private val altRowBgColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt()),
            0x22
        )
    }

    private val gridLineColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0.toInt()),
            0x66
        )
    }

    private val headerTextColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
    }

    private val cellTextColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
    }

    init {
        headerPaint.color = headerTextColor
        cellPaint.color = cellTextColor
        linePaint.color = gridLineColor
        setPadding(outerStrokeInset, outerStrokeInset, outerStrokeInset, outerStrokeInset)
    }

    fun setTableData(
        headers: List<String>,
        rows: List<List<String>>,
        alignments: List<MdBlock.Alignment>
    ) {
        this.headers = headers
        this.rows = rows
        this.alignments = alignments
        recalculateMetrics()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        recalculateMetrics()

        val desiredWidth = ceil(cachedTotalWidth + paddingLeft + paddingRight).toInt()
        val desiredHeight = ceil(cachedTotalHeight + paddingTop + paddingBottom).toInt()

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            else -> desiredWidth
        }

        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(measuredWidth.coerceAtLeast(suggestedMinimumWidth), measuredHeight.coerceAtLeast(suggestedMinimumHeight))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (headers.isEmpty()) return

        recalculateMetrics()
        val totalW = cachedTotalWidth
        val totalH = cachedTotalHeight
        val headerHeight = rowHeight(headerPaint)
        val rowHeight = rowHeight(cellPaint)
        val contentWidth = width - paddingLeft - paddingRight
        val startX = paddingLeft.toFloat() + maxOf(0f, (contentWidth - totalW) / 2f)
        val startY = paddingTop.toFloat()
        val tableRect = RectF(startX, startY, startX + totalW, startY + totalH)

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(tableRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        bgPaint.color = headerBgColor
        canvas.drawRect(RectF(startX, startY, startX + totalW, startY + headerHeight), bgPaint)

        for (rowIndex in rows.indices) {
            if (rowIndex % 2 == 1) {
                val rowTop = startY + headerHeight + rowHeight * rowIndex
                bgPaint.color = altRowBgColor
                canvas.drawRect(RectF(startX, rowTop, startX + totalW, rowTop + rowHeight), bgPaint)
            }
        }

        canvas.restore()

        val headerFm = headerPaint.fontMetrics
        val headerBaseline = startY + cellPaddingV - headerFm.ascent
        var currentX = startX
        for (columnIndex in headers.indices) {
            val textX = alignedTextX(headers[columnIndex], headerPaint, currentX, columnWidths[columnIndex], columnIndex)
            canvas.drawText(headers[columnIndex], textX, headerBaseline, headerPaint)
            currentX += columnWidths[columnIndex]
        }

        val cellFm = cellPaint.fontMetrics
        for (rowIndex in rows.indices) {
            val rowTop = startY + headerHeight + rowHeight * rowIndex
            val baseline = rowTop + cellPaddingV - cellFm.ascent
            currentX = startX
            for (columnIndex in headers.indices) {
                val cellText = rows[rowIndex].getOrNull(columnIndex).orEmpty()
                val textX = alignedTextX(cellText, cellPaint, currentX, columnWidths[columnIndex], columnIndex)
                canvas.drawText(cellText, textX, baseline, cellPaint)
                currentX += columnWidths[columnIndex]
            }
        }

        canvas.drawLine(startX, startY + headerHeight, startX + totalW, startY + headerHeight, linePaint)
        for (rowIndex in 1 until rows.size) {
            val lineY = startY + headerHeight + rowHeight * rowIndex
            canvas.drawLine(startX, lineY, startX + totalW, lineY, linePaint)
        }

        currentX = startX
        for (columnIndex in 1 until headers.size) {
            currentX += columnWidths[columnIndex - 1]
            canvas.drawLine(currentX, startY, currentX, startY + totalH, linePaint)
        }

        canvas.drawRoundRect(tableRect, cornerRadius, cornerRadius, linePaint)
    }

    private fun recalculateMetrics() {
        if (headers.isEmpty()) {
            columnWidths = FloatArray(0)
            cachedTotalWidth = 0f
            cachedTotalHeight = 0f
            return
        }
        columnWidths = calculateColumnWidths()
        cachedTotalWidth = columnWidths.sum()
        cachedTotalHeight = rowHeight(headerPaint) + rowHeight(cellPaint) * rows.size
    }

    private fun calculateColumnWidths(): FloatArray {
        val widths = FloatArray(headers.size)
        for (index in headers.indices) {
            widths[index] = headerPaint.measureText(headers[index]) + cellPaddingH * 2
        }
        for (row in rows) {
            for (index in headers.indices) {
                val text = row.getOrNull(index).orEmpty()
                val width = cellPaint.measureText(text) + cellPaddingH * 2
                if (width > widths[index]) widths[index] = width
            }
        }
        return widths
    }

    private fun rowHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) + cellPaddingV * 2
    }

    private fun alignedTextX(
        text: String,
        paint: Paint,
        cellLeft: Float,
        cellWidth: Float,
        colIndex: Int
    ): Float {
        val alignment = alignments.getOrNull(colIndex) ?: MdBlock.Alignment.LEFT
        val textWidth = paint.measureText(text)
        return when (alignment) {
            MdBlock.Alignment.LEFT -> cellLeft + cellPaddingH
            MdBlock.Alignment.RIGHT -> cellLeft + cellWidth - cellPaddingH - textWidth
            MdBlock.Alignment.CENTER -> cellLeft + (cellWidth - textWidth) / 2f
        }
    }
}
