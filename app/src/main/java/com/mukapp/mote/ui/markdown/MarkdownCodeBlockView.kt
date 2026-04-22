package com.mukapp.mote.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.TextViewCompat
import com.google.android.material.card.MaterialCardView
import com.mukapp.mote.R
import com.mukapp.mote.util.dpInt

class MarkdownCodeBlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private var codeColors = resolveMarkdownCodeColors(context)
    private var codeSpanRenderer = MarkdownCodeSpanRenderer(context, codeColors)
    private val blockBackgroundColor by lazy {
        codeColors.blockBackgroundColor
    }
    private val headerTextColor by lazy {
        codeColors.headerTextColor
    }
    private val codeTextColor by lazy {
        codeColors.codeTextColor
    }
    private val dividerColor by lazy {
        codeColors.dividerColor
    }
    private val strokeLineColor by lazy {
        codeColors.strokeColor
    }
    private val copyIconTint by lazy {
        codeColors.headerTextColor
    }

    /**
     * 设置共享的 MarkdownCodeSpanRenderer，避免每个代码块视图都创建独立的 Prism4j 实例。
     */
    fun setSharedCodeSpanRenderer(renderer: MarkdownCodeSpanRenderer) {
        this.codeSpanRenderer = renderer
    }

    private var codeContent: String = ""
    /** 上一次成功渲染的语言，用于在 setCodeBlock 完全等价时复用 Spannable，避免 prism4j 重复 tokenize */
    private var lastRenderedLanguage: String? = null
    /** 上一次成功渲染的代码内容 */
    private var lastRenderedCode: String? = null

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private val headerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setPadding(12.dpInt, 10.dpInt, 8.dpInt, 8.dpInt)
    }

    private val languageView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setTextColor(headerTextColor)
        TextViewCompat.setTextAppearance(this, resolveThemeResource(context, com.google.android.material.R.attr.textAppearanceLabelSmall, 0))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        includeFontPadding = false
        maxLines = 1
    }

    private val copyButton = AppCompatImageButton(context).apply {
        layoutParams = LinearLayout.LayoutParams(28.dpInt, 28.dpInt)
        setImageResource(R.drawable.ic_content_copy)
        imageTintList = android.content.res.ColorStateList.valueOf(copyIconTint)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(4.dpInt, 4.dpInt, 4.dpInt, 4.dpInt)
        minimumWidth = 28.dpInt
        minimumHeight = 28.dpInt
        adjustViewBounds = false
        background = AppCompatResources.getDrawable(
            context,
            resolveThemeResource(context, android.R.attr.selectableItemBackgroundBorderless, 0)
        )
        contentDescription = context.getString(R.string.action_copy)
        setOnClickListener { copyCode() }
    }

    private val dividerView = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1.dpInt)
        setBackgroundColor(dividerColor)
    }

    private val scrollView = HorizontalScrollView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private val codeView = TextView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(12.dpInt, 12.dpInt, 12.dpInt, 12.dpInt)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(codeTextColor)
        typeface = android.graphics.Typeface.MONOSPACE
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
        setHorizontallyScrolling(true)
    }

    init {
        radius = 12.dpInt.toFloat()
        cardElevation = 0f
        strokeWidth = 1.dpInt
        strokeColor = strokeLineColor
        setCardBackgroundColor(blockBackgroundColor)
        preventCornerOverlap = false
        useCompatPadding = false

        headerLayout.addView(languageView)
        headerLayout.addView(copyButton)
        scrollView.addView(codeView)

        container.addView(headerLayout)
        container.addView(dividerView)
        container.addView(scrollView)
        addView(container)
    }

    fun setCodeBlock(language: String, code: String) {
        codeContent = code
        languageView.text = language.ifBlank { "text" }
        // 如果与上次完全一致，直接跳过 prism4j 高亮渲染，避免流式期间重复 tokenize
        if (lastRenderedLanguage == language && lastRenderedCode == code) {
            return
        }
        codeView.text = codeSpanRenderer.buildCodeContent(code, language)
        lastRenderedLanguage = language
        lastRenderedCode = code
    }

    private fun copyCode() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), codeContent))
        Toast.makeText(context, context.getString(R.string.action_copy), Toast.LENGTH_SHORT).show()
    }
}
