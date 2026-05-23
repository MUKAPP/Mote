package com.mukapp.mote.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.mukapp.mote.util.dpInt

/**
 * 消息列表动画：新消息从底部轻微滑入 + 淡入，其余操作不做动画。
 */
class MessageItemAnimator : DefaultItemAnimator() {

    private val slideDistance = 10.dpInt.toFloat()

    init {
        // 禁用 change 动画，避免流式更新时闪烁
        supportsChangeAnimations = false
        addDuration = 180
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        holder.itemView.alpha = 0f
        holder.itemView.translationY = slideDistance
        val alphaAnim = ObjectAnimator.ofFloat(holder.itemView, "alpha", 0f, 1f)
        val translateAnim = ObjectAnimator.ofFloat(holder.itemView, "translationY", slideDistance, 0f)
        val animatorSet = AnimatorSet().apply {
            playTogether(alphaAnim, translateAnim)
            duration = addDuration
            interpolator = DecelerateInterpolator()
        }
        animatorSet.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                holder.itemView.alpha = 1f
                holder.itemView.translationY = 0f
                dispatchAddFinished(holder)
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                holder.itemView.alpha = 1f
                holder.itemView.translationY = 0f
            }
        })
        animatorSet.start()
        return false
    }
}
