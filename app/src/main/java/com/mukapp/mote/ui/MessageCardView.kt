package com.mukapp.mote.ui

import android.content.Context
import android.text.Spanned
import android.text.style.ClickableSpan
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
 * - 当按下位置被上层交互元素消费时（可点击控件、可选文本、链接等），不触发波纹与菜单，交由其自身处理。
 */
class MessageCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private var longPressListener: ((x: Int, y: Int) -> Unit)? = null
    private var downX = 0
    private var downY = 0
    private var consumedByChild = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                val listener = longPressListener ?: return
                // 被上层交互元素消费的位置不弹菜单
                if (consumedByChild) {
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
                consumedByChild = isOverConsumingTarget(downX, downY)
                if (!consumedByChild) {
                    // 仅在未被上层消费时显示波纹，并将起点定位到手指处
                    drawableHotspotChanged(ev.x, ev.y)
                    isPressed = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
            }
        }
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isOverConsumingTarget(x: Int, y: Int): Boolean {
        return hitTestConsuming(this, x, y, isRoot = true)
    }

    /** 递归判断坐标（相对 [view]）下方是否存在会消费触摸的交互元素。 */
    private fun hitTestConsuming(view: View, x: Int, y: Int, isRoot: Boolean): Boolean {
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                if (child.visibility != View.VISIBLE) {
                    continue
                }
                val px = x + view.scrollX
                val py = y + view.scrollY
                if (px >= child.left && px < child.right && py >= child.top && py < child.bottom) {
                    if (hitTestConsuming(child, px - child.left, py - child.top, false)) {
                        return true
                    }
                }
            }
        }
        if (isRoot) {
            // 根卡片自身的 clickable 仅用于绘制波纹，不算消费
            return false
        }
        if (view is TextView) {
            // 普通正文文本不算消费（放行波纹与菜单）；仅可选文本或命中链接时算消费
            if (view.isTextSelectable) {
                return true
            }
            return hasLinkAt(view, x, y)
        }
        return view.isClickable || view.isLongClickable
    }

    /** 判断坐标（相对 [textView]）是否落在可点击的链接 span 上。 */
    private fun hasLinkAt(textView: TextView, x: Int, y: Int): Boolean {
        val spanned = textView.text as? Spanned ?: return false
        val layout = textView.layout ?: return false
        val px = x - textView.totalPaddingLeft + textView.scrollX
        val py = y - textView.totalPaddingTop + textView.scrollY
        if (px < 0 || py < 0) {
            return false
        }
        val line = layout.getLineForVertical(py)
        if (px > layout.getLineRight(line)) {
            return false
        }
        val offset = layout.getOffsetForHorizontal(line, px.toFloat())
        return spanned.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()
    }
}
