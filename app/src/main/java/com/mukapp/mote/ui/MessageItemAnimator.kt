package com.mukapp.mote.ui

import androidx.recyclerview.widget.DefaultItemAnimator

/**
 * 消息列表动画：新消息淡入（DefaultItemAnimator 的 add 动画本身即淡入，时长由 addDuration 控制），
 * 禁用 change 动画。不再自定义 animateAdd：自跑动画并返回 false 违反 ItemAnimator 契约，
 * 动画无法被 endAnimations 取消，holder 回收复用时会残留 alpha 动画。
 */
class MessageItemAnimator : DefaultItemAnimator() {

    init {
        // 禁用 change 动画，避免流式更新时闪烁
        supportsChangeAnimations = false
        addDuration = 180
    }
}
