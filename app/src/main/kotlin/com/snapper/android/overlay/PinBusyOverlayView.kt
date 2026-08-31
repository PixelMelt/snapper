package com.snapper.android.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

internal class PinBusyOverlayView(context: Context, private val icon: Bitmap) : View(context) {
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconSource = Rect(0, 0, icon.width, icon.height)
    private val iconTarget = RectF()
    private val spinnerBounds = RectF()
    private var spinnerAnimator: ValueAnimator? = null
    private var spinnerRotation = 0f

    init {
        backgroundPaint.color = BACKGROUND_COLOR
        spinnerPaint.color = Color.WHITE
        spinnerPaint.style = Paint.Style.STROKE
        spinnerPaint.strokeCap = Paint.Cap.ROUND
        spinnerPaint.strokeWidth = SPINNER_STROKE_DP * density
        isClickable = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start(reduceAnimations: Boolean) {
        if (reduceAnimations) {
            alpha = 1f
        } else {
            alpha = 0f
            animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
        val animator = ValueAnimator.ofFloat(0f, 360f)
        animator.duration = SPINNER_TURN_MS
        animator.interpolator = LinearInterpolator()
        animator.repeatCount = ValueAnimator.INFINITE
        animator.addUpdateListener { animation ->
            spinnerRotation = animation.animatedValue as Float
            postInvalidateOnAnimation()
        }
        spinnerAnimator = animator
        animator.start()
    }

    fun stop() {
        animate().cancel()
        spinnerAnimator?.cancel()
        spinnerAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = Math.min(CORNER_RADIUS_DP * density, Math.min(width, height) * 0.5f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backgroundPaint)

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val available = Math.max(1f, Math.min(width, height).toFloat())
        val iconBox = Math.min(ICON_SIZE_DP * density, available * 0.72f)
        val scale = Math.min(iconBox / icon.width, iconBox / icon.height)
        val iconWidth = icon.width * scale
        val iconHeight = icon.height * scale
        iconTarget.set(
            centerX - iconWidth * 0.5f, centerY - iconHeight * 0.5f,
            centerX + iconWidth * 0.5f, centerY + iconHeight * 0.5f,
        )
        canvas.drawBitmap(icon, iconSource, iconTarget, iconPaint)

        val spinnerSize = Math.min(SPINNER_SIZE_DP * density, available * 0.84f)
        val spinnerRadius = spinnerSize * 0.5f
        spinnerPaint.strokeWidth = Math.min(SPINNER_STROKE_DP * density, Math.max(1f, spinnerSize * 0.09f))
        val halfStroke = spinnerPaint.strokeWidth * 0.5f
        spinnerBounds.set(
            centerX - spinnerRadius + halfStroke,
            centerY - spinnerRadius + halfStroke,
            centerX + spinnerRadius - halfStroke,
            centerY + spinnerRadius - halfStroke,
        )
        canvas.drawArc(spinnerBounds, spinnerRotation - 90f, 270f, false, spinnerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        val BACKGROUND_COLOR = Color.argb(191, 0, 0, 0)
        const val CORNER_RADIUS_DP = 8f
        const val ICON_SIZE_DP = 30f
        const val SPINNER_SIZE_DP = 36f
        const val SPINNER_STROKE_DP = 3f
        const val FADE_IN_MS = 500L
        const val SPINNER_TURN_MS = 850L
    }
}
