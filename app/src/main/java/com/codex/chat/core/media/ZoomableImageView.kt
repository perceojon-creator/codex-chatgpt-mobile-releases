package com.codex.chat.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView profesional con soporte táctil completo:
 * - Pellizco con dos dedos (Pinch-to-zoom) de 1.0x a 6.0x
 * - Doble toque para alternar zoom (1.0x <-> 2.5x) centrado en el toque
 * - Paneo / arrastre fluido cuando la imagen está aumentada
 * - Fijación de límites para mantener la imagen centrada y visible
 * - Protección contra intercepción de gestos de contenedores padre
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val currentMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var currentScale = 1.0f
    private val minScale = 1.0f
    private val maxScale = 6.0f

    private var isInitialized = false
    private var baseWidth = 0f
    private var baseHeight = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val targetScale = (currentScale * factor).coerceIn(minScale, maxScale)
            val actualFactor = targetScale / currentScale
            currentScale = targetScale

            currentMatrix.postScale(actualFactor, actualFactor, detector.focusX, detector.focusY)
            clampTranslation()
            imageMatrix = currentMatrix
            invalidate()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.2f) {
                resetZoom()
            } else {
                val targetZoom = 2.5f
                val factor = targetZoom / currentScale
                currentScale = targetZoom
                currentMatrix.postScale(factor, factor, e.x, e.y)
                clampTranslation()
                imageMatrix = currentMatrix
                invalidate()
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (currentScale > 1.05f) {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentMatrix.postTranslate(-distanceX, -distanceY)
                clampTranslation()
                imageMatrix = currentMatrix
                invalidate()
                return true
            }
            return false
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        isInitialized = false
        fitImageCenter()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        isInitialized = false
        fitImageCenter()
    }

    fun resetZoom() {
        currentScale = 1.0f
        fitImageCenter()
    }

    fun fitImageCenter() {
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return

        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return

        baseWidth = dw
        baseHeight = dh

        currentMatrix.reset()
        val scale = minOf(viewW / dw, viewH / dh)
        currentMatrix.postScale(scale, scale)

        val scaledW = dw * scale
        val scaledH = dh * scale
        val dx = (viewW - scaledW) / 2f
        val dy = (viewH - scaledH) / 2f
        currentMatrix.postTranslate(dx, dy)

        currentScale = 1.0f
        imageMatrix = currentMatrix
        isInitialized = true
        invalidate()
    }

    private fun clampTranslation() {
        val rect = getDisplayedRect()
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() >= viewW) {
            if (rect.left > 0) deltaX = -rect.left
            if (rect.right < viewW) deltaX = viewW - rect.right
        } else {
            deltaX = (viewW - rect.width()) / 2f - rect.left
        }

        if (rect.height() >= viewH) {
            if (rect.top > 0) deltaY = -rect.top
            if (rect.bottom < viewH) deltaY = viewH - rect.bottom
        } else {
            deltaY = (viewH - rect.height()) / 2f - rect.top
        }

        currentMatrix.postTranslate(deltaX, deltaY)
    }

    private fun getDisplayedRect(): RectF {
        val rect = RectF()
        val d = drawable ?: return rect
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        currentMatrix.mapRect(rect)
        return rect
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInitialized) return super.onTouchEvent(event)

        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)

        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (currentScale <= 1.05f) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }
}
