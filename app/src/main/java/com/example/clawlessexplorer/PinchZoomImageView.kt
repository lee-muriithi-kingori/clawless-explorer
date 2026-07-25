package com.example.clawlessexplorer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class PinchZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            currentScale *= scaleFactor
            currentScale = currentScale.coerceIn(minScale, maxScale)
            matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            constrainMatrix()
            imageMatrix = matrix
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            // Snap back to min if close
            getCurrentScale().let { scale ->
                if (scale < minScale * 1.1f) {
                    animateScaleTo(minScale)
                }
            }
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val current = getCurrentScale()
            val target = if (current > (minScale + maxScale) / 2f) minScale else maxScale * 0.6f
            animateScaleTo(target, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (getCurrentScale() > minScale) {
                matrix.postTranslate(-dx, -dy)
                constrainMatrix()
                imageMatrix = matrix
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetMatrix()
    }

    private fun resetMatrix() {
        matrix.reset()
        currentScale = minScale
        if (drawable != null) {
            val viewW = width.toFloat()
            val viewH = height.toFloat()
            val drawableW = drawable.intrinsicWidth.toFloat()
            val drawableH = drawable.intrinsicHeight.toFloat()
            if (drawableW <= 0 || drawableH <= 0 || viewW <= 0 || viewH <= 0) return

            val scale = min(viewW / drawableW, viewH / drawableH)
            val dx = (viewW - drawableW * scale) / 2f
            val dy = (viewH - drawableH * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            imageMatrix = matrix
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return true
    }

    private fun getCurrentScale(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun constrainMatrix() {
        val drawable = drawable ?: return
        val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        matrix.mapRect(rect)

        var dx = 0f
        var dy = 0f

        if (rect.width() >= width) {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < width) dx = width - rect.right
        } else {
            dx = (width - rect.width()) / 2f - rect.left
        }

        if (rect.height() >= height) {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < height) dy = height - rect.bottom
        } else {
            dy = (height - rect.height()) / 2f - rect.top
        }

        matrix.postTranslate(dx, dy)
    }

    private fun animateScaleTo(targetScale: Float, pivotX: Float = width / 2f, pivotY: Float = height / 2f) {
        val startScale = getCurrentScale()
        val animator = ValueAnimator.ofFloat(0f, 1f).setDuration(300)
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val fraction = anim.animatedValue as Float
            val scale = startScale + (targetScale - startScale) * fraction
            val factor = scale / getCurrentScale()
            matrix.postScale(factor, factor, pivotX, pivotY)
            constrainMatrix()
            imageMatrix = matrix
        }
        animator.start()
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
    }
}
