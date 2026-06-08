package com.mukapp.mote.ui.smooth

import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.shape.CornerTreatment
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.ShapePath
import com.google.android.material.shape.Shapeable
import kotlin.math.min

/** 项目统一的 G1 圆角曲线，使用单段三次贝塞尔近似 quarter-circle。 */
object SmoothCorners {
    private const val ControlPointFactor = 0.55228475f

    fun addSmoothRoundRect(path: Path, rect: RectF, radius: Float) {
        val maxRadius = min(rect.width(), rect.height()) / 2f
        val r = radius.coerceAtLeast(0f).coerceAtMost(maxRadius)
        if (r <= 0f) {
            path.addRect(rect, Path.Direction.CW)
            return
        }

        path.moveTo(rect.left + r, rect.top)
        path.lineTo(rect.right - r, rect.top)
        path.cubicTo(
            rect.right - r + r * ControlPointFactor,
            rect.top,
            rect.right,
            rect.top + r - r * ControlPointFactor,
            rect.right,
            rect.top + r
        )

        path.lineTo(rect.right, rect.bottom - r)
        path.cubicTo(
            rect.right,
            rect.bottom - r + r * ControlPointFactor,
            rect.right - r + r * ControlPointFactor,
            rect.bottom,
            rect.right - r,
            rect.bottom
        )

        path.lineTo(rect.left + r, rect.bottom)
        path.cubicTo(
            rect.left + r - r * ControlPointFactor,
            rect.bottom,
            rect.left,
            rect.bottom - r + r * ControlPointFactor,
            rect.left,
            rect.bottom - r
        )

        path.lineTo(rect.left, rect.top + r)
        path.cubicTo(
            rect.left,
            rect.top + r - r * ControlPointFactor,
            rect.left + r - r * ControlPointFactor,
            rect.top,
            rect.left + r,
            rect.top
        )

        path.close()
    }

    fun toSmoothShapeAppearanceModel(model: ShapeAppearanceModel): ShapeAppearanceModel {
        return model.toBuilder()
            .setTopLeftCorner(SmoothCornerTreatment)
            .setTopRightCorner(SmoothCornerTreatment)
            .setBottomRightCorner(SmoothCornerTreatment)
            .setBottomLeftCorner(SmoothCornerTreatment)
            .build()
    }

    fun applyToViewTree(view: View) {
        applyToView(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyToViewTree(view.getChildAt(index))
            }
        }
    }

    fun applyToView(view: View) {
        if (view is Shapeable) {
            view.shapeAppearanceModel = toSmoothShapeAppearanceModel(view.shapeAppearanceModel)
        } else {
            applyShapeAppearanceByReflection(view)
        }
        view.background?.let(::applyShapeAppearanceByReflection)
        view.foreground?.let(::applyShapeAppearanceByReflection)
    }

    fun applyToDrawable(drawable: Drawable?) {
        if (drawable == null) return
        applyShapeAppearanceByReflection(drawable)
    }

    private fun applyShapeAppearanceByReflection(target: Any) {
        val getShapeModel = target.javaClass.methods.firstOrNull { method ->
            method.name == "getShapeAppearanceModel" && method.parameterTypes.isEmpty()
        } ?: return
        val setShapeModel = target.javaClass.methods.firstOrNull { method ->
            method.name == "setShapeAppearanceModel" &&
                method.parameterTypes.contentEquals(arrayOf(ShapeAppearanceModel::class.java))
        } ?: return
        val model = getShapeModel.invoke(target) as? ShapeAppearanceModel ?: return
        setShapeModel.invoke(target, toSmoothShapeAppearanceModel(model))
    }

    internal fun addSmoothCorner(shapePath: ShapePath, radius: Float) {
        if (radius <= 0f) {
            shapePath.reset(0f, 0f)
            return
        }
        shapePath.reset(0f, radius, 180f, 90f)
        shapePath.cubicToPoint(
            0f,
            radius - radius * ControlPointFactor,
            radius - radius * ControlPointFactor,
            0f,
            radius,
            0f
        )
    }
}

private object SmoothCornerTreatment : CornerTreatment() {
    override fun getCornerPath(
        shapePath: ShapePath,
        angle: Float,
        interpolation: Float,
        radius: Float
    ) {
        SmoothCorners.addSmoothCorner(shapePath, radius * interpolation)
    }
}
