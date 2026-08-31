package com.snapper.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.snapper.android.R
import com.snapper.android.settings.SnapSettings
import kotlin.math.ceil

internal class OverlayFeedback(
    private val context: Context,
    private val windows: WindowManager,
    private val main: Handler,
    private val afterHidden: Runnable,
) {
    companion object {
        private const val PRESENTATION_MS = 250L
        private const val VISIBLE_MS = 3_000L
        private const val DISMISSAL_MS = 200L
    }

    private val view = MessageView(context)

    private var pendingExit: Runnable? = null
    private var sequence = 0
    private var attached = false
    private var closed = false

    fun show(message: String): Boolean {
        if (closed) {
            return false
        }
        val token = ++sequence
        cancelPendingExit()
        view.animate().cancel()

        val display: Rect = windows.currentWindowMetrics.bounds
        view.prepare(message, display.width())
        val params = makeLayoutParams(display, view.preparedWidth, view.preparedHeight)

        val reduceAnimations = SnapSettings.reduceAnimations(context)
        view.alpha = if (reduceAnimations) 1f else 0f
        view.scaleX = if (reduceAnimations) 1f else 0.5f
        view.scaleY = if (reduceAnimations) 1f else 0.5f
        if (attached) {
            windows.updateViewLayout(view, params)
        } else {
            try {
                windows.addView(view, params)
            } catch (denied: WindowManager.BadTokenException) {
                return false
            }
            attached = true
        }

        view.contentDescription = message
        view.post {
            if (attached && sequence == token) {
                view.announceForAccessibility(message)
            }
        }

        if (reduceAnimations) {
            scheduleExit(token, true)
        } else {
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(PRESENTATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { scheduleExit(token, false) }
                .start()
        }
        return true
    }

    val isShowing: Boolean
        get() = attached

    fun hideNow() {
        sequence++
        cancelPendingExit()
        view.animate().cancel()
        detach(false)
    }

    fun onDisplayGeometryChanged() {
        if (!attached) {
            return
        }
        val display = windows.currentWindowMetrics.bounds
        view.prepare(view.message, display.width())
        windows.updateViewLayout(view, makeLayoutParams(display, view.preparedWidth, view.preparedHeight))
    }

    fun dismissImmediately() {
        closed = true
        sequence++
        cancelPendingExit()
        view.animate().cancel()
        detach(false)
    }

    private fun scheduleExit(token: Int, reduceAnimations: Boolean) {
        if (!attached || sequence != token) {
            return
        }
        val exit = Runnable {
            pendingExit = null
            if (!attached || sequence != token) {
                return@Runnable
            }
            if (reduceAnimations) {
                detach(true)
                return@Runnable
            }
            view.animate()
                .alpha(0f)
                .setDuration(DISMISSAL_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    if (sequence == token) {
                        detach(true)
                    }
                }
                .start()
        }
        pendingExit = exit
        main.postDelayed(exit, VISIBLE_MS)
    }

    private fun cancelPendingExit() {
        val exit = pendingExit ?: return
        main.removeCallbacks(exit)
        pendingExit = null
    }

    private fun detach(notifyHidden: Boolean) {
        if (!attached) {
            return
        }
        attached = false
        windows.removeViewImmediate(view)
        if (notifyHidden) {
            afterHidden.run()
        }
    }

    private fun makeLayoutParams(
        display: Rect,
        width: Int,
        height: Int,
    ): WindowManager.LayoutParams {
        val layout = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        layout.gravity = Gravity.TOP or Gravity.START
        layout.x = maxOf(0, (display.width() - width) / 2)
        val centerY = display.height() - view.dp(100f)
        layout.y = maxOf(0, minOf(display.height() - height, centerY - height / 2))
        layout.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        layout.title = "Snapper feedback"
        return layout
    }

    private class MessageView(context: Context) : View(context) {
        companion object {
            private const val ICON_LEFT_DP = 6f
            private const val ICON_TOP_DP = 5f
            private const val ICON_SIZE_DP = 20f
            private const val TEXT_LEFT_DP = 32f
            private const val WIDTH_EXTRA_DP = 38f
            private const val HEIGHT_EXTRA_DP = 10f
            private const val MIN_HEIGHT_DP = 30f
            private const val MAX_TEXT_HEIGHT_DP = 100f
            private const val CORNER_RADIUS_DP = 8f
        }

        private val density = resources.displayMetrics.density
        private val materialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        private val background = RectF()
        private val popupIcon: Drawable = requireNotNull(
            context.getDrawable(R.drawable.snapper_popup_image),
        )

        private lateinit var textLayout: StaticLayout
        var message = ""
            private set
        var preparedWidth = 0
            private set
        var preparedHeight = 0
            private set

        init {
            materialPaint.color = Color.argb(204, 30, 30, 32)
            textPaint.color = Color.WHITE
            textPaint.textSize = 17f * resources.displayMetrics.scaledDensity
            textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        0, 0, view.width, view.height,
                        dp(CORNER_RADIUS_DP).toFloat(),
                    )
                }
            }
            clipToOutline = true
        }

        fun prepare(value: String, displayWidth: Int) {
            message = value
            val maxTextWidth = maxOf(1, Math.round(displayWidth * 0.75f))
            val desiredTextWidth = maxOf(
                1,
                minOf(
                    maxTextWidth,
                    ceil(Layout.getDesiredWidth(message, textPaint).toDouble()).toInt(),
                ),
            )
            val lineHeight = maxOf(
                1,
                Math.round(textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent),
            )
            val maxLines = maxOf(1, dp(MAX_TEXT_HEIGHT_DP) / lineHeight)
            val layout = StaticLayout.Builder.obtain(
                message, 0, message.length, textPaint, desiredTextWidth,
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(false)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(desiredTextWidth)
                .setMaxLines(maxLines)
                .build()
            textLayout = layout
            preparedWidth = desiredTextWidth + dp(WIDTH_EXTRA_DP)
            preparedHeight = maxOf(
                dp(MIN_HEIGHT_DP),
                minOf(dp(MAX_TEXT_HEIGHT_DP), layout.height) + dp(HEIGHT_EXTRA_DP),
            )
            requestLayout()
            invalidate()
        }

        fun dp(value: Float): Int = Math.round(value * density)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(preparedWidth, preparedHeight)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            background.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(
                background, dp(CORNER_RADIUS_DP).toFloat(), dp(CORNER_RADIUS_DP).toFloat(),
                materialPaint,
            )

            val left = dp(ICON_LEFT_DP)
            val top = dp(ICON_TOP_DP)
            popupIcon.setBounds(left, top, left + dp(ICON_SIZE_DP), top + dp(ICON_SIZE_DP))
            popupIcon.draw(canvas)
            val layout = textLayout
            canvas.save()
            canvas.translate(
                dp(TEXT_LEFT_DP).toFloat(),
                maxOf(0f, (height - layout.height) / 2f),
            )
            layout.draw(canvas)
            canvas.restore()
        }

        override fun getAccessibilityClassName(): CharSequence = "android.widget.TextView"
    }
}
