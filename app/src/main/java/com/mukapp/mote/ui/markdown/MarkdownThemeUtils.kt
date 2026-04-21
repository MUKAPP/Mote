package com.mukapp.mote.ui.markdown

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AnyRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.mukapp.mote.R

internal data class MarkdownCodeColors(
    @get:ColorInt val blockBackgroundColor: Int,
    @get:ColorInt val headerTextColor: Int,
    @get:ColorInt val codeTextColor: Int,
    @get:ColorInt val dividerColor: Int,
    @get:ColorInt val strokeColor: Int,
    @get:ColorInt val keywordColor: Int,
    @get:ColorInt val stringColor: Int,
    @get:ColorInt val commentColor: Int,
    @get:ColorInt val numberColor: Int,
    @get:ColorInt val annotationColor: Int
)

internal fun resolveMarkdownCodeColors(context: Context): MarkdownCodeColors {
    return MarkdownCodeColors(
        blockBackgroundColor = ContextCompat.getColor(context, R.color.markdown_code_block_background),
        headerTextColor = ContextCompat.getColor(context, R.color.markdown_code_block_header_text),
        codeTextColor = ContextCompat.getColor(context, R.color.markdown_code_block_text),
        dividerColor = ContextCompat.getColor(context, R.color.markdown_code_block_divider),
        strokeColor = ContextCompat.getColor(context, R.color.markdown_code_block_stroke),
        keywordColor = ContextCompat.getColor(context, R.color.markdown_code_keyword),
        stringColor = ContextCompat.getColor(context, R.color.markdown_code_string),
        commentColor = ContextCompat.getColor(context, R.color.markdown_code_comment),
        numberColor = ContextCompat.getColor(context, R.color.markdown_code_number),
        annotationColor = ContextCompat.getColor(context, R.color.markdown_code_annotation)
    )
}

internal fun resolveThemeColor(context: Context, attr: Int, @ColorInt fallback: Int): Int {
    val typedValue = TypedValue()
    val resolved = context.theme.resolveAttribute(attr, typedValue, true)
    return if (resolved) {
        if (typedValue.resourceId != 0) ContextCompat.getColor(context, typedValue.resourceId) else typedValue.data
    } else {
        fallback
    }
}

internal fun blendWithAlpha(@ColorInt color: Int, alpha: Int): Int {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return (alpha shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun resolveThemeResource(context: Context, attr: Int, @AnyRes fallback: Int): Int {
    val typedValue = TypedValue()
    val resolved = context.theme.resolveAttribute(attr, typedValue, true)
    return if (resolved && typedValue.resourceId != 0) typedValue.resourceId else fallback
}
