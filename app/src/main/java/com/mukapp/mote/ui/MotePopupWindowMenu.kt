package com.mukapp.mote.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.mukapp.mote.R
import com.mukapp.mote.util.dpInt
import kotlin.math.hypot
import kotlin.math.max

internal data class MotePopupWindowItem(
    val id: Int,
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int? = null
)

internal object MotePopupWindowMenu {
    /** 在手指按下位置弹出菜单，图标间距由图标槽控制，不改变图标绘制尺寸。 */
    fun showAtTouch(
        anchor: View,
        touchX: Int,
        touchY: Int,
        items: List<MotePopupWindowItem>,
        onItemClick: (Int) -> Unit
    ) {
        if (items.isEmpty()) return

        val holder = createPopupWindow(
            anchor.context,
            items,
            onItemClick
        )
        measureContent(anchor, holder.content)

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val safeTouchX = touchX.coerceIn(0, (anchor.width - 1).coerceAtLeast(0))
        val safeTouchY = touchY.coerceIn(0, (anchor.height - 1).coerceAtLeast(0))
        val touchScreenX = anchorLocation[0] + safeTouchX
        val touchScreenY = anchorLocation[1] + safeTouchY
        val rawX = if (safeTouchX >= anchor.width / 2) {
            touchScreenX - holder.content.measuredWidth
        } else {
            touchScreenX
        }

        val frame = visibleDisplayFrame(anchor)
        val margin = PopupWindowMarginDp.dpInt
        val x = rawX.coerceToWindow(frame.left + margin, frame.right - holder.content.measuredWidth - margin)
        val y = touchScreenY.coerceToWindow(frame.top + margin, frame.bottom - holder.content.measuredHeight - margin)
        holder.popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        holder.showFrom(
            centerX = touchScreenX - x.toFloat(),
            centerY = touchScreenY - y.toFloat()
        )
    }

    /** 在锚点控件旁弹出菜单，空间不足时自动改为向上弹出。 */
    fun showAnchored(
        anchor: View,
        items: List<MotePopupWindowItem>,
        onItemClick: (Int) -> Unit
    ) {
        if (items.isEmpty()) return

        val holder = createPopupWindow(
            anchor.context,
            items,
            onItemClick
        )
        measureContent(anchor, holder.content)

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val anchorLeft = anchorLocation[0]
        val anchorTop = anchorLocation[1]
        val anchorBottom = anchorTop + anchor.height
        val frame = visibleDisplayFrame(anchor)
        val margin = PopupWindowMarginDp.dpInt
        val spaceBelow = frame.bottom - anchorBottom - margin
        val spaceAbove = anchorTop - frame.top - margin
        val rawY = if (spaceBelow >= holder.content.measuredHeight || spaceBelow >= spaceAbove) {
            anchorBottom
        } else {
            anchorTop - holder.content.measuredHeight
        }
        val x = anchorLeft.coerceToWindow(frame.left + margin, frame.right - holder.content.measuredWidth - margin)
        val y = rawY.coerceToWindow(frame.top + margin, frame.bottom - holder.content.measuredHeight - margin)
        val anchorCenterX = anchorLeft + anchor.width / 2f
        val anchorEdgeY = if (rawY >= anchorBottom) anchorBottom.toFloat() else anchorTop.toFloat()
        holder.popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        holder.showFrom(
            centerX = anchorCenterX - x,
            centerY = anchorEdgeY - y
        )
    }

    private fun createPopupWindow(
        context: Context,
        items: List<MotePopupWindowItem>,
        onItemClick: (Int) -> Unit
    ): PopupHolder {
        lateinit var popup: PopupWindow
        lateinit var holder: PopupHolder
        val content = createContent(context, items) { itemId ->
            holder.dismissWithReveal { onItemClick(itemId) }
        }
        popup = RevealPopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = true
            elevation = PopupWindowElevationDp.dpInt.toFloat()
            animationStyle = 0
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        content.visibility = View.INVISIBLE
        holder = PopupHolder(popup, content)
        (popup as RevealPopupWindow).onDismissRequested = {
            holder.dismissWithReveal()
        }
        popup.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                holder.dismissWithReveal()
                true
            } else {
                false
            }
        }
        anchorBackDispatcherOwner(context)?.onBackPressedDispatcher?.addCallback(holder.backCallback)
        return holder
    }

    private fun createContent(
        context: Context,
        items: List<MotePopupWindowItem>,
        onItemClick: (Int) -> Unit
    ): View {
        val iconColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val textColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface)
        val ripple = resolveThemeDrawable(context, android.R.attr.selectableItemBackground)
        val showIconSlot = items.any { it.iconRes != null }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.popup_message_menu_background)
            elevation = PopupWindowElevationDp.dpInt.toFloat()
            setPadding(0, PopupWindowVerticalPaddingDp.dpInt, 0, PopupWindowVerticalPaddingDp.dpInt)
            minimumWidth = PopupWindowMinWidthDp.dpInt
            items.forEach { item ->
                addView(createRow(context, item, showIconSlot, iconColor, textColor, ripple, onItemClick))
            }
        }
    }

    private fun createRow(
        context: Context,
        item: MotePopupWindowItem,
        showIconSlot: Boolean,
        iconColor: Int,
        textColor: Int,
        ripple: android.graphics.drawable.Drawable?,
        onItemClick: (Int) -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = ripple?.constantState?.newDrawable()?.mutate()
            isClickable = true
            isFocusable = true
            minimumHeight = PopupWindowItemHeightDp.dpInt
            setOnClickListener { onItemClick(item.id) }

            if (showIconSlot) {
                addView(FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(PopupIconSlotWidthDp.dpInt, PopupWindowItemHeightDp.dpInt)
                    item.iconRes?.let { iconRes ->
                        addView(ImageView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                PopupIconSizeDp.dpInt,
                                PopupIconSizeDp.dpInt,
                                Gravity.START or Gravity.CENTER_VERTICAL
                            ).apply {
                                marginStart = PopupIconStartPaddingDp.dpInt
                            }
                            imageTintList = ColorStateList.valueOf(iconColor)
                            setImageResource(iconRes)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        })
                    }
                })
            }

            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (!showIconSlot) {
                        marginStart = PopupTextStartPaddingDp.dpInt
                    }
                    marginEnd = PopupTextEndPaddingDp.dpInt
                }
                setText(item.titleRes)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, PopupTextSizeSp)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            })
        }
    }

    private fun measureContent(anchor: View, content: View) {
        val frame = visibleDisplayFrame(anchor)
        val margin = PopupWindowMarginDp.dpInt
        val maxWidth = (frame.width() - margin * 2).coerceAtLeast(0)
        val maxHeight = (frame.height() - margin * 2).coerceAtLeast(0)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
    }

    private fun visibleDisplayFrame(anchor: View): Rect {
        return Rect().also { frame ->
            anchor.getWindowVisibleDisplayFrame(frame)
            if (frame.isEmpty) {
                frame.set(0, 0, anchor.rootView.width, anchor.rootView.height)
            }
        }
    }

    private fun Int.coerceToWindow(min: Int, max: Int): Int {
        val upper = max.coerceAtLeast(min)
        return coerceIn(min, upper)
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun resolveThemeDrawable(context: Context, attr: Int): android.graphics.drawable.Drawable? {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getDrawable(context, typedValue.resourceId)
        } else {
            null
        }
    }

    private fun anchorBackDispatcherOwner(context: Context): OnBackPressedDispatcherOwner? {
        var currentContext = context
        while (currentContext is ContextWrapper) {
            if (currentContext is OnBackPressedDispatcherOwner) return currentContext
            currentContext = currentContext.baseContext
        }
        return currentContext as? OnBackPressedDispatcherOwner
    }

    private class PopupHolder(
        val popup: PopupWindow,
        val content: View
    ) {
        private var revealCenterX = 0f
        private var revealCenterY = 0f
        private var isDismissing = false
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismissWithReveal()
            }
        }

        fun showFrom(centerX: Float, centerY: Float) {
            revealCenterX = centerX.coerceIn(0f, content.measuredWidth.toFloat())
            revealCenterY = centerY.coerceIn(0f, content.measuredHeight.toFloat())
            content.post {
                content.visibility = View.VISIBLE
                circularReveal(content, revealCenterX, revealCenterY, 0f, revealRadius(), PopupRevealEnterDurationMs)
                    .start()
            }
        }

        fun dismissWithReveal(onDismissed: (() -> Unit)? = null) {
            if (isDismissing) return
            isDismissing = true
            backCallback.remove()
            if (!popup.isShowing || content.width == 0 || content.height == 0) {
                popup.dismissImmediately()
                onDismissed?.invoke()
                return
            }
            circularReveal(content, revealCenterX, revealCenterY, revealRadius(), 0f, PopupRevealExitDurationMs)
                .apply {
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            popup.dismissImmediately()
                            onDismissed?.invoke()
                        }
                    })
                }
                .start()
        }

        private fun revealRadius(): Float {
            val maxX = max(revealCenterX, content.width - revealCenterX)
            val maxY = max(revealCenterY, content.height - revealCenterY)
            return hypot(maxX.toDouble(), maxY.toDouble()).toFloat()
        }
    }

    private class RevealPopupWindow(
        contentView: View,
        width: Int,
        height: Int,
        focusable: Boolean
    ) : PopupWindow(contentView, width, height, focusable) {
        var onDismissRequested: (() -> Unit)? = null
        private var dismissImmediately = false

        override fun dismiss() {
            if (dismissImmediately) {
                super.dismiss()
            } else {
                onDismissRequested?.invoke() ?: super.dismiss()
            }
        }

        fun dismissImmediately() {
            dismissImmediately = true
            dismiss()
        }
    }

    private fun PopupWindow.dismissImmediately() {
        if (this is RevealPopupWindow) {
            dismissImmediately()
        } else {
            dismiss()
        }
    }

    private fun circularReveal(
        view: View,
        centerX: Float,
        centerY: Float,
        startRadius: Float,
        endRadius: Float,
        durationMs: Long
    ): Animator {
        return ViewAnimationUtils.createCircularReveal(
            view,
            centerX.toInt(),
            centerY.toInt(),
            startRadius,
            endRadius
        ).apply {
            duration = durationMs
        }
    }

    private const val PopupWindowMinWidthDp = 156
    private const val PopupWindowElevationDp = 3
    private const val PopupWindowVerticalPaddingDp = 12
    private const val PopupWindowItemHeightDp = 48
    private const val PopupIconStartPaddingDp = 16
    private const val PopupIconEndPaddingDp = 12
    private const val PopupIconSizeDp = 24
    private const val PopupIconSlotWidthDp = PopupIconStartPaddingDp + PopupIconSizeDp + PopupIconEndPaddingDp
    private const val PopupTextStartPaddingDp = 20
    private const val PopupTextEndPaddingDp = 20
    private const val PopupTextSizeSp = 16f
    private const val PopupWindowMarginDp = 8
    private const val PopupRevealEnterDurationMs = 180L
    private const val PopupRevealExitDurationMs = 120L
}
