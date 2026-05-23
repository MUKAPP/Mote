package com.mukapp.mote.util

import android.content.res.Resources
import android.util.TypedValue

/** 获取系统屏幕参数 */
private val metrics get() = Resources.getSystem().displayMetrics

/** * dp 转 px
 * 适用于 Float, Int, Double 等所有的 Number 类型
 */
val Number.dp: Float
    get() = this.toFloat() * metrics.density

val Number.dpInt: Int
    get() = (this.toFloat() * metrics.density + 0.5f).toInt()

/** * sp 转 px (常用于字体大小)
 */
val Number.sp: Float
    get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this.toFloat(), metrics)

val Number.spInt: Int
    get() = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this.toFloat(), metrics) + 0.5f).toInt()

/** * px 转 dp
 */
val Number.pxToDp: Float
    get() = this.toFloat() / metrics.density

val Number.pxToDpInt: Int
    get() = (this.toFloat() / metrics.density + 0.5f).toInt()

/** * px 语义化属性 (如需代码保持可读性，例如 56.px)
 */
val Number.px: Float
    get() = this.toFloat()

val Number.pxInt: Int
    get() = this.toInt()