package com.mukapp.mote.ui.markdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.URLSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.text.buildSpannedString
import com.mukapp.mote.util.dp
import com.mukapp.mote.util.sp
import kotlin.math.ceil
import kotlin.math.max

class MarkdownTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class CellContent(
        val text: CharSequence,
        val layout: StaticLayout,
        val width: Float,
        val height: Float
    )

    private data class LinkRenderInfo(
        val text: Spanned,
        val layout: StaticLayout,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private val cellPaddingH = 12f.dp
    private val cellPaddingV = 9f.dp
    private val gridLineWidth = 1f.dp
    private val cornerRadius = 12f.dp
    private val fontSize = 13f.sp
    private val headerFontSize = 13f.sp
    private val outerStrokeInset = ceil(gridLineWidth / 2f).toInt()

    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = headerFontSize
    }

    private val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = fontSize
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = gridLineWidth
        style = Paint.Style.STROKE
    }

    private val spannedBuilder = SpannedBuilder(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var headers: List<String> = emptyList()
    private var rows: List<List<String>> = emptyList()
    private var alignments: List<MdBlock.Alignment> = emptyList()
    private var linkDefs: Map<String, Pair<String, String>> = emptyMap()
    private var isStreaming: Boolean = false

    private var headerCells: List<CellContent> = emptyList()
    private var rowCells: List<List<CellContent>> = emptyList()
    private var columnWidths: FloatArray = FloatArray(0)
    private var headerHeight: Float = 0f
    private var rowHeights: FloatArray = FloatArray(0)
    private var cachedTotalWidth: Float = 0f
    private var cachedTotalHeight: Float = 0f
    private val linkRenderInfos = mutableListOf<LinkRenderInfo>()
    private var pressedLinkSpan: URLSpan? = null
    private var pressedX: Float = 0f
    private var pressedY: Float = 0f

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
        alignments: List<MdBlock.Alignment>,
        linkDefs: Map<String, Pair<String, String>> = emptyMap(),
        isStreaming: Boolean = false
    ) {
        this.headers = headers
        this.rows = rows
        this.alignments = alignments
        this.linkDefs = linkDefs
        this.isStreaming = isStreaming
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
            MeasureSpec.AT_MOST -> desiredWidth.coerceAtMost(widthSize)
            else -> desiredWidth
        }

        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(
            measuredWidth.coerceAtLeast(suggestedMinimumWidth),
            measuredHeight.coerceAtLeast(suggestedMinimumHeight)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (headers.isEmpty()) return

        recalculateMetrics()
        linkRenderInfos.clear()
        val totalW = cachedTotalWidth
        val totalH = cachedTotalHeight
        val contentWidth = width - paddingLeft - paddingRight
        val startX = paddingLeft.toFloat() + max(0f, (contentWidth - totalW) / 2f)
        val startY = paddingTop.toFloat()
        val tableRect = RectF(startX, startY, startX + totalW, startY + totalH)

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(tableRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        bgPaint.color = headerBgColor
        canvas.drawRect(RectF(startX, startY, startX + totalW, startY + headerHeight), bgPaint)

        var rowTop = startY + headerHeight
        for (rowIndex in rowHeights.indices) {
            if (rowIndex % 2 == 1) {
                bgPaint.color = altRowBgColor
                canvas.drawRect(RectF(startX, rowTop, startX + totalW, rowTop + rowHeights[rowIndex]), bgPaint)
            }
            rowTop += rowHeights[rowIndex]
        }

        canvas.restore()

        drawHeaderCells(canvas, startX, startY)
        drawBodyCells(canvas, startX, startY + headerHeight)

        canvas.drawLine(startX, startY + headerHeight, startX + totalW, startY + headerHeight, linePaint)
        rowTop = startY + headerHeight
        for (rowIndex in 1 until rowHeights.size) {
            rowTop += rowHeights[rowIndex - 1]
            canvas.drawLine(startX, rowTop, startX + totalW, rowTop, linePaint)
        }

        var currentX = startX
        for (columnIndex in 1 until columnWidths.size) {
            currentX += columnWidths[columnIndex - 1]
            canvas.drawLine(currentX, startY, currentX, startY + totalH, linePaint)
        }

        canvas.drawRoundRect(tableRect, cornerRadius, cornerRadius, linePaint)
    }

    private fun drawHeaderCells(canvas: Canvas, startX: Float, startY: Float) {
        var currentX = startX
        for (columnIndex in headerCells.indices) {
            val cell = headerCells[columnIndex]
            drawCellLayout(canvas, cell, currentX, startY, columnWidths[columnIndex], headerHeight, columnIndex)
            currentX += columnWidths[columnIndex]
        }
    }

    private fun drawBodyCells(canvas: Canvas, startX: Float, startY: Float) {
        var rowTop = startY
        for (rowIndex in rowCells.indices) {
            var currentX = startX
            val row = rowCells[rowIndex]
            val rowHeight = rowHeights[rowIndex]
            for (columnIndex in headers.indices) {
                val cell = row.getOrNull(columnIndex) ?: emptyCell(cellPaint)
                drawCellLayout(canvas, cell, currentX, rowTop, columnWidths[columnIndex], rowHeight, columnIndex)
                currentX += columnWidths[columnIndex]
            }
            rowTop += rowHeight
        }
    }

    private fun drawCellLayout(
        canvas: Canvas,
        cell: CellContent,
        cellLeft: Float,
        cellTop: Float,
        cellWidth: Float,
        rowHeight: Float,
        columnIndex: Int
        ) {
        val contentLeft = cellLeft + cellPaddingH
        val contentTop = cellTop + cellPaddingV
        val availableWidth = (cellWidth - cellPaddingH * 2).coerceAtLeast(1f)
        val translateX = when (alignments.getOrNull(columnIndex) ?: MdBlock.Alignment.LEFT) {
            MdBlock.Alignment.LEFT -> contentLeft
            MdBlock.Alignment.RIGHT -> contentLeft + availableWidth - cell.layout.width
            MdBlock.Alignment.CENTER -> contentLeft + (availableWidth - cell.layout.width) / 2f
        }
        val translateY = contentTop + max(0f, (rowHeight - cellPaddingV * 2 - cell.height) / 2f)

        maybeRegisterClickableText(cell, translateX, translateY)

        canvas.save()
        canvas.translate(translateX, translateY)
        cell.layout.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val span = findUrlSpanAt(event.x, event.y)
                if (span != null) {
                    pressedLinkSpan = span
                    pressedX = event.x
                    pressedY = event.y
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedLinkSpan != null) {
                    val movedTooFar = kotlin.math.abs(event.x - pressedX) > touchSlop ||
                        kotlin.math.abs(event.y - pressedY) > touchSlop
                    if (movedTooFar) {
                        pressedLinkSpan = null
                    }
                    return pressedLinkSpan != null
                }
            }

            MotionEvent.ACTION_UP -> {
                val span = pressedLinkSpan
                pressedLinkSpan = null
                if (span != null) {
                    val sameSpan = findUrlSpanAt(event.x, event.y) == span
                    val movedTooFar = kotlin.math.abs(event.x - pressedX) > touchSlop ||
                        kotlin.math.abs(event.y - pressedY) > touchSlop
                    if (sameSpan && !movedTooFar) {
                        performClick()
                        span.onClick(this)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedLinkSpan = null
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun recalculateMetrics() {
        if (headers.isEmpty()) {
            headerCells = emptyList()
            rowCells = emptyList()
            columnWidths = FloatArray(0)
            rowHeights = FloatArray(0)
            cachedTotalWidth = 0f
            cachedTotalHeight = 0f
            headerHeight = 0f
            return
        }

        headerCells = headers.map { createCellContent(it, headerPaint) }
        rowCells = rows.map { row ->
            headers.indices.map { columnIndex ->
                createCellContent(row.getOrNull(columnIndex).orEmpty(), cellPaint)
            }
        }

        columnWidths = calculateColumnWidths()
        headerHeight = calculateRowHeight(headerCells)
        rowHeights = FloatArray(rowCells.size) { index -> calculateRowHeight(rowCells[index]) }
        cachedTotalWidth = columnWidths.sum()
        cachedTotalHeight = headerHeight + rowHeights.sum()
    }

    private fun calculateColumnWidths(): FloatArray {
        val widths = FloatArray(headers.size)
        for (index in headers.indices) {
            widths[index] = headerCells[index].width + cellPaddingH * 2
        }
        for (row in rowCells) {
            for (index in headers.indices) {
                val width = row.getOrNull(index)?.width ?: 0f
                val desiredWidth = width + cellPaddingH * 2
                if (desiredWidth > widths[index]) widths[index] = desiredWidth
            }
        }
        return widths
    }

    private fun calculateRowHeight(cells: List<CellContent>): Float {
        val contentHeight = cells.maxOfOrNull { it.height } ?: lineHeight(cellPaint)
        return contentHeight + cellPaddingV * 2
    }

    private fun createCellContent(text: String, paint: TextPaint): CellContent {
        val spanned = spannedBuilder.buildInlineText(text, isStreaming, linkDefs)
        return createCellContent(spanned, paint)
    }

    private fun createCellContent(text: CharSequence, paint: TextPaint): CellContent {
        val layout = createStaticLayout(text, paint)
        return CellContent(
            text = text,
            layout = layout,
            width = layout.width.toFloat(),
            height = layout.height.toFloat()
        )
    }

    private fun createStaticLayout(text: CharSequence, paint: TextPaint): StaticLayout {
        val desiredWidth = ceil(max(1f, Layout.getDesiredWidth(text, paint))).toInt().coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, desiredWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.08f)
            .build()
    }

    private fun emptyCell(paint: TextPaint): CellContent {
        return createCellContent(buildSpannedString { append("") }, paint)
    }

    private fun maybeRegisterClickableText(cell: CellContent, left: Float, top: Float) {
        val spanned = cell.text as? Spanned ?: return
        if (spanned.getSpans(0, spanned.length, URLSpan::class.java).isEmpty()) return
        linkRenderInfos.add(
            LinkRenderInfo(
                text = spanned,
                layout = cell.layout,
                left = left,
                top = top,
                right = left + cell.layout.width,
                bottom = top + cell.layout.height
            )
        )
    }

    private fun findUrlSpanAt(x: Float, y: Float): URLSpan? {
        val info = linkRenderInfos.lastOrNull { x in it.left..it.right && y in it.top..it.bottom } ?: return null
        val relativeX = x - info.left
        val relativeY = y - info.top
        val line = info.layout.getLineForVertical(relativeY.toInt().coerceAtLeast(0))
        val offset = info.layout.getOffsetForHorizontal(line, relativeX)
            .coerceIn(0, (info.text.length - 1).coerceAtLeast(0))
        return info.text.getSpans(offset, offset, URLSpan::class.java).firstOrNull()
    }

    private fun lineHeight(paint: TextPaint): Float {
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }
}
