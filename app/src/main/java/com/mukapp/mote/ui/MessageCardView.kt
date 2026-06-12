package com.mukapp.mote.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/**
 * 消息气泡卡片：
 * - 通过 [dispatchTouchEvent] 喂给 [GestureDetector] 检测长按，使整张卡片（含内部 Markdown 文本）
 *   任意位置长按都能触发菜单，而非只有文本与卡片之间的空白边距。
 * - 记录按下坐标，供调用方在手指位置弹出菜单。
 * - 借助 clickable 状态 + 手动驱动 pressed/hotspot，绘制覆盖整卡片（含子视图区域）的圆角波纹反馈。
 * - 命中可选文本（思考过程、工具结果）时不触发菜单，保留这些区域的文本选择能力。
 */
class MessageCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private var longPressListener: ((x: Int, y: Int) -> Unit)? = null
    private var downX = 0
    private var downY = 0

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                val listener = longPressListener ?: return
                // 命中可选文本区域时让其自行处理选择，不弹消息菜单
                if (isOverSelectableText(downX, downY)) {
                    return
                }
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener.invoke(downX, downY)
            }
        }
    )

    init {
        // clickable 让 MaterialCardView 安装圆角波纹前景；无点击行为，关闭点击音效
        isClickable = true
        isFocusable = true
        isSoundEffectsEnabled = false
    }

    fun setOnContentLongPressListener(listener: ((x: Int, y: Int) -> Unit)?) {
        longPressListener = listener
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x.toInt()
                downY = ev.y.toInt()
                // 手动驱动波纹起点与按下态，使子视图（Markdown 文本等）区域同样显示波纹
                drawableHotspotChanged(ev.x, ev.y)
                isPressed = true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
            }
        }
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isOverSelectableText(x: Int, y: Int): Boolean {
        return hitTestSelectableText(this, x, y)
    }

    /** 递归判断坐标（相对 [view]）下方是否为可选文本视图。 */
    private fun hitTestSelectableText(view: View, x: Int, y: Int): Boolean {
        if (view is TextView && view.isTextSelectable) {
            return true
        }
        if (view !is ViewGroup) {
            return false
        }
        for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            if (child.visibility != View.VISIBLE) {
                continue
            }
            val px = x + view.scrollX
            val py = y + view.scrollY
            if (px >= child.left && px < child.right && py >= child.top && py < child.bottom) {
                if (hitTestSelectableText(child, px - child.left, py - child.top)) {
                    return true
                }
            }
        }
        return false
    }
}
