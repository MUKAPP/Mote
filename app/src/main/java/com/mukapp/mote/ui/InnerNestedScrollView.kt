package com.mukapp.mote.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.content.withStyledAttributes
import androidx.core.widget.NestedScrollView
import com.mukapp.mote.R

class InnerNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {
    private val maxHeight: Int

    init {
        var resolvedMaxHeight = 0
        context.withStyledAttributes(attrs, R.styleable.InnerNestedScrollView) {
            resolvedMaxHeight = getDimensionPixelSize(R.styleable.InnerNestedScrollView_maxHeight, 0)
        }
        maxHeight = resolvedMaxHeight
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val resolvedHeightSpec = if (maxHeight > 0) {
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, resolvedHeightSpec)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
    }
}
