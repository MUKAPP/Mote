package com.mukapp.mote.ui.markdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.URLSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.text.buildSpannedString
import com.mukapp.mote.ui.smooth.SmoothCorners
import com.mukapp.mote.util.dp
import com.mukapp.mote.util.sp
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MarkdownTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class CellContent(
        val text: CharSequence,
        val layout: StaticLayout,
        val height: Float
    )

    private data class IntrinsicCellContent(
        val text: CharSequence,
        val minContentWidth: Float,
        val maxContentWidth: Float
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
    private var metricsDirty: Boolean = true
    private var availableViewportWidth: Int = 0
    private var metricsAvailableContentWidth: Int = -1
    private val linkRenderInfos = mutableListOf<LinkRenderInfo>()
    private var pressedLinkSpan: URLSpan? = null
    private var pressedX: Float = 0f
    private var pressedY: Float = 0f

    private val headerBgColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt()),
            0x28
        )
    }

    private val altRowBgColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt()),
            0x14
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
        if (this.headers == headers &&
            this.rows == rows &&
            this.alignments == alignments &&
            this.linkDefs == linkDefs &&
            this.isStreaming == isStreaming
        ) {
            return
        }
        this.headers = headers
        this.rows = rows
        this.alignments = alignments
        this.linkDefs = linkDefs
        this.isStreaming = isStreaming
        metricsDirty = true
        requestLayout()
        invalidate()
    }

    fun setAvailableViewportWidth(width: Int) {
        val normalizedWidth = width.coerceAtLeast(0)
        if (availableViewportWidth == normalizedWidth) return
        availableViewportWidth = normalizedWidth
        metricsDirty = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        ensureMetrics(resolveAvailableContentWidth(widthMeasureSpec))

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

        ensureMetrics(resolveCurrentAvailableContentWidth())
        linkRenderInfos.clear()
        val totalW = cachedTotalWidth
        val totalH = cachedTotalHeight
        val contentWidth = width - paddingLeft - paddingRight
        val startX = paddingLeft.toFloat() + max(0f, (contentWidth - totalW) / 2f)
        val startY = paddingTop.toFloat()
        val tableRect = RectF(startX, startY, startX + totalW, startY + totalH)

        canvas.save()
        val clipPath = Path().apply {
            SmoothCorners.addSmoothRoundRect(this, tableRect, cornerRadius)
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

        canvas.drawPath(clipPath, linePaint)
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
        val translateY = contentTop + max(0f, (rowHeight - cellPaddingV * 2 - cell.height) / 2f)

        maybeRegisterClickableText(cell, contentLeft, translateY)

        canvas.save()
        canvas.translate(contentLeft, translateY)
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

    private fun recalculateMetrics(availableContentWidth: Int) {
        if (headers.isEmpty()) {
            headerCells = emptyList()
            rowCells = emptyList()
            columnWidths = FloatArray(0)
            rowHeights = FloatArray(0)
            cachedTotalWidth = 0f
            cachedTotalHeight = 0f
            headerHeight = 0f
            metricsDirty = false
            metricsAvailableContentWidth = availableContentWidth
            return
        }

        val intrinsicHeaderCells = headers.map { createIntrinsicCellContent(it, headerPaint) }
        val intrinsicRowCells = rows.map { row ->
            headers.indices.map { columnIndex ->
                createIntrinsicCellContent(row.getOrNull(columnIndex).orEmpty(), cellPaint)
            }
        }

        columnWidths = calculateColumnWidths(intrinsicHeaderCells, intrinsicRowCells, availableContentWidth)
        headerCells = createLaidOutCells(intrinsicHeaderCells, headerPaint)
        rowCells = intrinsicRowCells.map { row -> createLaidOutCells(row, cellPaint) }
        headerHeight = calculateRowHeight(headerCells)
        rowHeights = FloatArray(rowCells.size) { index -> calculateRowHeight(rowCells[index]) }
        cachedTotalWidth = columnWidths.sum()
        cachedTotalHeight = headerHeight + rowHeights.sum()
        metricsDirty = false
        metricsAvailableContentWidth = availableContentWidth
    }

    private fun ensureMetrics(availableContentWidth: Int = availableViewportWidth) {
        val normalizedWidth = availableContentWidth.coerceAtLeast(0)
        if (metricsDirty || metricsAvailableContentWidth != normalizedWidth) {
            recalculateMetrics(normalizedWidth)
        }
    }

    private fun calculateColumnWidths(
        headerCells: List<IntrinsicCellContent>,
        rowCells: List<List<IntrinsicCellContent>>,
        availableContentWidth: Int
    ): FloatArray {
        val columns = headers.indices.map { index ->
            var minWidth = headerCells[index].minContentWidth + cellPaddingH * 2
            var maxWidth = headerCells[index].maxContentWidth + cellPaddingH * 2
            for (row in rowCells) {
                val cell = row.getOrNull(index) ?: continue
                minWidth = max(minWidth, cell.minContentWidth + cellPaddingH * 2)
                maxWidth = max(maxWidth, cell.maxContentWidth + cellPaddingH * 2)
            }
            MarkdownAutoTableLayout.ColumnIntrinsic(
                min = minWidth,
                max = maxWidth,
                hasOriginatingCell = true
            )
        }
        return MarkdownAutoTableLayout.compute(
            columns = columns,
            parentWidth = availableContentWidth.takeIf { it > 0 }?.toFloat(),
            fillParentWhenPossible = false
        ).columnWidths
    }

    private fun calculateRowHeight(cells: List<CellContent>): Float {
        val contentHeight = cells.maxOfOrNull { it.height } ?: lineHeight(cellPaint)
        return contentHeight + cellPaddingV * 2
    }

    private fun createLaidOutCells(cells: List<IntrinsicCellContent>, paint: TextPaint): List<CellContent> {
        return cells.mapIndexed { index, cell ->
            val contentWidth = (columnWidths.getOrNull(index) ?: 0f) - cellPaddingH * 2
            createCellContent(cell.text, paint, contentWidth, alignments.getOrNull(index) ?: MdBlock.Alignment.LEFT)
        }
    }

    private fun createIntrinsicCellContent(text: String, paint: TextPaint): IntrinsicCellContent {
        val spanned = spannedBuilder.buildInlineText(text, isStreaming, linkDefs)
        return createIntrinsicCellContent(spanned, paint)
    }

    private fun createIntrinsicCellContent(text: CharSequence, paint: TextPaint): IntrinsicCellContent {
        return IntrinsicCellContent(
            text = text,
            minContentWidth = measureMinContentWidth(text, paint),
            maxContentWidth = measureMaxContentWidth(text, paint)
        )
    }

    private fun createCellContent(
        text: CharSequence,
        paint: TextPaint,
        contentWidth: Float,
        alignment: MdBlock.Alignment
    ): CellContent {
        val layout = createStaticLayout(text, paint, contentWidth, alignment)
        return CellContent(
            text = text,
            layout = layout,
            height = layout.height.toFloat()
        )
    }

    private fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        contentWidth: Float,
        alignment: MdBlock.Alignment
    ): StaticLayout {
        val desiredWidth = ceil(max(1f, contentWidth)).toInt().coerceAtLeast(1)
        val layoutAlignment = when (alignment) {
            MdBlock.Alignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            MdBlock.Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            MdBlock.Alignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, desiredWidth)
            .setAlignment(layoutAlignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.08f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
    }

    private fun emptyCell(paint: TextPaint): CellContent {
        return createCellContent(buildSpannedString { append("") }, paint, 1f, MdBlock.Alignment.LEFT)
    }

    private fun resolveAvailableContentWidth(widthMeasureSpec: Int): Int {
        val viewportWidth = when {
            availableViewportWidth > 0 -> availableViewportWidth
            MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED -> MeasureSpec.getSize(widthMeasureSpec)
            else -> 0
        }
        return (viewportWidth - paddingLeft - paddingRight).coerceAtLeast(0)
    }

    private fun resolveCurrentAvailableContentWidth(): Int {
        val viewportWidth = availableViewportWidth.takeIf { it > 0 } ?: width
        return (viewportWidth - paddingLeft - paddingRight).coerceAtLeast(0)
    }

    private fun measureMaxContentWidth(text: CharSequence, paint: TextPaint): Float {
        if (text.isEmpty()) return 0f
        var maxWidth = 0f
        var lineStart = 0
        for (index in 0..text.length) {
            if (index == text.length || text[index] == '\n') {
                maxWidth = max(maxWidth, measureDesiredWidth(text, lineStart, index, paint))
                lineStart = index + 1
            }
        }
        return maxWidth
    }

    private fun measureMinContentWidth(text: CharSequence, paint: TextPaint): Float {
        if (text.isEmpty()) return 0f
        val rawText = text.toString()
        val iterator = BreakIterator.getLineInstance(Locale.getDefault())
        iterator.setText(rawText)

        var maxSegmentWidth = 0f
        var segmentStart = iterator.first()
        var segmentEnd = iterator.next()
        while (segmentEnd != BreakIterator.DONE) {
            val trimmedStart = trimSegmentStart(rawText, segmentStart, segmentEnd)
            val trimmedEnd = trimSegmentEnd(rawText, trimmedStart, segmentEnd)
            if (trimmedStart < trimmedEnd) {
                maxSegmentWidth = max(
                    maxSegmentWidth,
                    measureBreakAwareSegmentWidth(text, trimmedStart, trimmedEnd, paint)
                )
            }
            segmentStart = segmentEnd
            segmentEnd = iterator.next()
        }

        return maxSegmentWidth
    }

    private fun measureBreakAwareSegmentWidth(
        text: CharSequence,
        start: Int,
        end: Int,
        paint: TextPaint
    ): Float {
        var maxWidth = 0f
        var currentStart = start
        var hasExtraBreak = false
        for (index in start until end) {
            if (isUrlBreakCharacter(text[index])) {
                val breakEnd = index + 1
                maxWidth = max(maxWidth, measureDesiredWidth(text, currentStart, breakEnd, paint))
                currentStart = breakEnd
                hasExtraBreak = true
            }
        }

        if (!hasExtraBreak) {
            return measureDesiredWidth(text, start, end, paint)
        }

        if (currentStart < end) {
            maxWidth = max(maxWidth, measureDesiredWidth(text, currentStart, end, paint))
        }
        return maxWidth
    }

    private fun measureDesiredWidth(text: CharSequence, start: Int, end: Int, paint: TextPaint): Float {
        if (start >= end) return 0f
        return Layout.getDesiredWidth(text, start, end, paint)
    }

    private fun trimSegmentStart(text: String, start: Int, end: Int): Int {
        var index = start
        while (index < end && text[index].isWhitespace()) {
            index++
        }
        return index
    }

    private fun trimSegmentEnd(text: String, start: Int, end: Int): Int {
        var index = end
        while (index > start && text[index - 1].isWhitespace()) {
            index--
        }
        return index
    }

    private fun isUrlBreakCharacter(char: Char): Boolean {
        return char == '/' ||
            char == '?' ||
            char == '&' ||
            char == '=' ||
            char == '-' ||
            char == '_' ||
            char == '.' ||
            char == '#' ||
            char == ':'
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
        val lineLeft = min(info.layout.getLineLeft(line), info.layout.getLineRight(line))
        val lineRight = max(info.layout.getLineLeft(line), info.layout.getLineRight(line))
        if (relativeX < lineLeft || relativeX > lineRight) return null
        val offset = info.layout.getOffsetForHorizontal(line, relativeX)
            .coerceIn(0, (info.text.length - 1).coerceAtLeast(0))
        return info.text.getSpans(offset, offset, URLSpan::class.java).firstOrNull()
    }

    private fun lineHeight(paint: TextPaint): Float {
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }
}
