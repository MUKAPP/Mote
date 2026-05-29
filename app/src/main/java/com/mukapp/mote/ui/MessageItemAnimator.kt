package com.mukapp.mote.ui

import android.animation.ObjectAnimator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * 消息列表动画：新消息仅淡入，其余操作不做动画。
 */
class MessageItemAnimator : DefaultItemAnimator() {

    init {
        // 禁用 change 动画，避免流式更新时闪烁
        supportsChangeAnimations = false
        addDuration = 180
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        holder.itemView.alpha = 0f
        val alphaAnim = ObjectAnimator.ofFloat(holder.itemView, "alpha", 0f, 1f).apply {
            duration = addDuration
        }
        alphaAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                holder.itemView.alpha = 1f
                dispatchAddFinished(holder)
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                holder.itemView.alpha = 1f
            }
        })
        alphaAnim.start()
        return false
    }
}
