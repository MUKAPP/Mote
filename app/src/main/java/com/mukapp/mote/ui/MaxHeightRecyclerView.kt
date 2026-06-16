package com.mukapp.mote.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.content.withStyledAttributes
import androidx.recyclerview.widget.RecyclerView
import com.mukapp.mote.R

class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    private val maxHeight: Int
    private var lastTouchY = 0f

    init {
        var resolvedMaxHeight = 0
        context.withStyledAttributes(attrs, R.styleable.MaxHeightRecyclerView) {
            resolvedMaxHeight = getDimensionPixelSize(R.styleable.MaxHeightRecyclerView_maxHeight, 0)
        }
        maxHeight = resolvedMaxHeight
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val resolvedHeightSpec = if (maxHeight > 0) {
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, resolvedHeightSpec)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(canScrollVertically(-1) || canScrollVertically(1))
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.y - lastTouchY
                val canScrollInGestureDirection = when {
                    deltaY > 0f -> canScrollVertically(-1)
                    deltaY < 0f -> canScrollVertically(1)
                    else -> canScrollVertically(-1) || canScrollVertically(1)
                }
                parent?.requestDisallowInterceptTouchEvent(canScrollInGestureDirection)
                lastTouchY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(event)
    }
}
