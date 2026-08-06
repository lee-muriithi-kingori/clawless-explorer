package com.example.clawlessexplorer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore

class PdfRendererView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pageBitmap: Bitmap? = null
    private var pageNumber: Int = 0
    private var zoom: Float = 1.0f
    private var renderThread: Thread? = null
    private var zoomAnimator: ValueAnimator? = null
    private var panX: Float = 0f
    private var panY: Float = 0f
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    private val minZoom = 0.5f
    private val maxZoom = 5.0f
    private val renderScale = 2

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    var onRenderComplete: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = zoom
                val newZoom = (zoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                if (newZoom == oldZoom) return true
                val factor = newZoom / oldZoom
                val fx = detector.focusX - viewWidth / 2f
                val fy = detector.focusY - viewHeight / 2f
                panX = fx * (1f - factor) + factor * panX
                panY = fy * (1f - factor) + factor * panY
                zoom = newZoom
                constrainPan()
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val targetZoom = if (zoom > 1.5f) 1.0f else 2.5f
                animateZoomTo(targetZoom, e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                if (zoom > 1.01f) {
                    panX -= dx
                    panY -= dy
                    constrainPan()
                    invalidate()
                }
                return true
            }
        }
    )

    init {
        isClickable = true
        isFocusable = true
    }

    fun loadPage(core: PdfiumCore, doc: PdfDocument, page: Int) {
        pageNumber = page
        renderThread?.interrupt()

        Thread {
            renderThread = Thread.currentThread()
            try {
                core.openPage(doc, page)
                val pageWidth = core.getPageWidth(doc, page)
                val pageHeight = core.getPageHeight(doc, page)

                val cappedWidth = (pageWidth * renderScale).coerceAtMost(2048)
                val cappedHeight = (pageHeight * renderScale).coerceAtMost(2048)

                val bitmap = Bitmap.createBitmap(
                    cappedWidth,
                    cappedHeight,
                    Bitmap.Config.ARGB_8888
                )
                core.renderPageBitmap(
                    doc, bitmap, page,
                    0, 0,
                    cappedWidth,
                    cappedHeight
                )

                pageBitmap?.recycle()
                pageBitmap = bitmap
                panX = 0f
                panY = 0f

                post {
                    if (!isAttachedToWindow) return@post
                    zoom = if (viewWidth > 0 && bitmap.width > 0) {
                        (viewWidth.toFloat() / bitmap.width).coerceIn(minZoom, maxZoom)
                    } else {
                        1.0f
                    }
                    invalidate()
                    onRenderComplete?.invoke()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    fun getPageNumber(): Int = pageNumber

    fun getZoom(): Float = zoom

    fun zoomIn() {
        val target = (zoom * 1.25f).coerceIn(minZoom, maxZoom)
        animateZoomTo(target, viewWidth / 2f, viewHeight / 2f)
    }

    fun zoomOut() {
        val target = (zoom / 1.25f).coerceIn(minZoom, maxZoom)
        animateZoomTo(target, viewWidth / 2f, viewHeight / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        if (oldw == 0 && oldh == 0) {
            pageBitmap?.let {
                zoom = if (it.width > 0) {
                    (w.toFloat() / it.width).coerceIn(minZoom, maxZoom)
                } else {
                    1.0f
                }
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = pageBitmap ?: return

        canvas.save()
        canvas.translate(panX + viewWidth / 2f, panY + viewHeight / 2f)
        canvas.scale(zoom, zoom)
        canvas.translate(-bitmap.width / 2f, -bitmap.height / 2f)
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    private fun constrainPan() {
        val bitmap = pageBitmap ?: return
        val scaledW = bitmap.width * zoom
        val scaledH = bitmap.height * zoom
        val maxPanX = maxOf(0f, (scaledW - viewWidth) / 2f)
        val maxPanY = maxOf(0f, (scaledH - viewHeight) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun animateZoomTo(targetZoom: Float, pivotX: Float, pivotY: Float) {
        zoomAnimator?.cancel()
        val startZoom = zoom
        val startPanX = panX
        val startPanY = panY
        val factor = targetZoom / startZoom
        val targetPanX = (pivotX - viewWidth / 2f) * (1f - factor) + factor * startPanX
        val targetPanY = (pivotY - viewHeight / 2f) * (1f - factor) + factor * startPanY

        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(300)
        zoomAnimator?.interpolator = DecelerateInterpolator()
        zoomAnimator?.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            zoom = startZoom + (targetZoom - startZoom) * t
            panX = startPanX + (targetPanX - startPanX) * t
            panY = startPanY + (targetPanY - startPanY) * t
            constrainPan()
            invalidate()
        }
        zoomAnimator?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderThread?.interrupt()
        renderThread = null
        zoomAnimator?.cancel()
        zoomAnimator = null
        pageBitmap?.recycle()
        pageBitmap = null
    }
}
