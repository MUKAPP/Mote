package com.mukapp.mote.ui.markdown

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AnyRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

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
