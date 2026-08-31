package com.snapper.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.PopupMenu
import com.snapper.android.R
import com.snapper.android.actions.SnapperActionRegistry
import com.snapper.android.settings.SnapSettings
import com.snapper.android.types.SelectionAction
import java.io.File

internal class PinView(
    context: Context,
    val bitmap: Bitmap,
    val file: File,
    private val listener: Listener,
) : FrameLayout(context) {
    interface Listener {
        fun onBringToFront()
        fun onMove(deltaX: Float, deltaY: Float)
        fun onResize(scaleFactor: Float)
        fun onGeometrySettled()
        fun onClose()
        fun onCopy()
        fun onSave()
        fun onShare()
        fun onOcr()
        fun onScanQr()
        fun onImgur()
        fun onUrlScheme()
        fun onExternalAction(selectionAction: Int)
    }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapSource = Rect()
    private val imageTarget = RectF()
    private val imageClip = Path()
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val multiTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout()
    private val multiTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout()
    private val shadowColorMode = SnapSettings.shadowColor(context)
    private val frameInset = frameInset(context).toFloat()
    private val actionIcons: Array<Bitmap>
    private val menuAnchor = View(context)
    private val scales: ScaleGestureDetector
    private var previousRawX = 0f
    private var previousRawY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var menuAnchorLocalX = 0f
    private var menuAnchorLocalY = 0f
    private var menuAnchorRemembered = false
    private var shadowEnabled = shadowColorMode != SnapSettings.SHADOW_NONE
    private var moving = false
    private var movedSinceDown = false
    private var fastMenuForGesture = false
    private var longPressTriggered = false
    private var tapSequenceCandidate = false
    private var tapCount = 0
    private var tapSequenceDeadline = 0L
    private var lastTapRawX = 0f
    private var lastTapRawY = 0f
    private var actionMenu: PopupMenu? = null
    private var busyOverlay: PinBusyOverlayView? = null
    private var recycleBitmapOnDetach = false

    private val longPress = Runnable { handleLongPressTimeout() }

    private val finishTapSequence = Runnable {
        val completedTaps = tapCount
        clearTapSequenceState()
        if (completedTaps == 2 && !SnapSettings.fastTapMenu(context)) {
            performTapAction(SnapSettings.doubleTapAction(context))
        }
    }

    init {
        SnapperActionRegistry.ensureExternalActions(context)
        actionIcons = SnapperActionRegistry.decodeActionIcons(context)
        SnapperActionRegistry.prewarmMenuIcons(context)

        setWillNotDraw(false)
        menuAnchor.setBackgroundColor(Color.TRANSPARENT)
        menuAnchor.isClickable = false
        menuAnchor.isFocusable = false
        menuAnchor.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(menuAnchor, LayoutParams(1, 1))

        isClickable = true
        isScreenReaderFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        restoreAccessibilityDescription()
        scales = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    movedSinceDown = true
                    cancelPendingLongPress()
                    cancelTapSequence()
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    movedSinceDown = true
                    listener.onResize(detector.scaleFactor)
                    return true
                }
            },
        )
    }

    val isBusy: Boolean
        get() = busyOverlay != null

    val frameInsetPx: Int
        get() = Math.round(frameInset)

    fun recycleBitmapAfterDetach() {
        recycleBitmapOnDetach = true
        if (!isAttachedToWindow && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    fun beginBusy(selectionAction: Int): Boolean {
        if (busyOverlay != null) {
            return false
        }
        val action = SnapperActionRegistry.actionForSelectionCode(selectionAction) ?: return false
        val busyIcon = action.providerIcon ?: actionIcons[action.iconIndex]

        cancelPendingLongPress()
        cancelTapSequence()
        moving = false
        actionMenu?.dismiss()

        val overlay = PinBusyOverlayView(context, busyIcon)
        val inset = Math.round(frameInset)
        val layout = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        layout.setMargins(inset, inset, inset, inset)
        busyOverlay = overlay
        addView(overlay, layout)
        val actionTitle = action.menuTitle(context)
        contentDescription = "Pinned screenshot. $actionTitle in progress."
        announceForAccessibility("$actionTitle in progress")
        overlay.start(SnapSettings.reduceAnimations(context))
        return true
    }

    fun endBusy(actionFailed: Boolean) {
        val overlay = busyOverlay ?: return
        busyOverlay = null
        overlay.stop()
        removeView(overlay)
        restoreAccessibilityDescription()
        if (actionFailed) {
            announceForAccessibility("Action failed. Pin retained.")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bitmap.isRecycled) {
            return
        }
        val inset = frameInset
        imageTarget.set(inset, inset, width - inset, height - inset)
        cardPaint.color = Color.WHITE
        if (shadowEnabled && shadowColorMode != SnapSettings.SHADOW_NONE) {
            val color = if (shadowColorMode == SnapSettings.SHADOW_WHITE) {
                Color.argb(128, 255, 255, 255)
            } else {
                Color.argb(128, 0, 0, 0)
            }
            cardPaint.setShadowLayer(3f * density, 0f, 2f * density, color)
        } else {
            cardPaint.clearShadowLayer()
        }
        val radius = 8f * density
        imageClip.reset()
        imageClip.addRoundRect(imageTarget, radius, radius, Path.Direction.CW)
        canvas.drawPath(imageClip, cardPaint)
        bitmapSource.set(0, 0, bitmap.width, bitmap.height)
        val saveCount = canvas.save()
        canvas.clipPath(imageClip)
        canvas.drawBitmap(bitmap, bitmapSource, imageTarget, imagePaint)
        canvas.restoreToCount(saveCount)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scales.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousRawX = event.rawX
                previousRawY = event.rawY
                downRawX = previousRawX
                downRawY = previousRawY
                rememberMenuAnchor(event)
                movedSinceDown = false
                moving = true
                longPressTriggered = false
                fastMenuForGesture = SnapSettings.fastTapMenu(context)
                if (fastMenuForGesture) {
                    cancelTapSequence()
                    tapSequenceCandidate = false
                } else {
                    tapSequenceCandidate = canContinueTapSequence(event)
                    if (tapCount > 0) {
                        if (tapSequenceCandidate) {
                            removeCallbacks(finishTapSequence)
                        } else {
                            val completedTaps = tapCount
                            cancelTapSequence()
                            if (completedTaps == 2) {
                                performTapAction(SnapSettings.doubleTapAction(context))
                                if (!isAttachedToWindow) {
                                    moving = false
                                    return true
                                }
                            }
                        }
                    }
                    postDelayed(longPress, longPressTimeoutMs.toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    rememberMenuAnchor(event)
                }
                if (moving && event.pointerCount == 1 && !scales.isInProgress) {
                    val rawX = event.rawX
                    val rawY = event.rawY
                    if (!movedSinceDown && (
                            Math.abs(rawX - downRawX) > touchSlop ||
                                Math.abs(rawY - downRawY) > touchSlop
                            )
                    ) {
                        movedSinceDown = true
                        cancelPendingLongPress()
                        cancelTapSequence()
                    }
                    if (movedSinceDown) {
                        listener.onMove(rawX - previousRawX, rawY - previousRawY)
                    }
                    previousRawX = rawX
                    previousRawY = rawY
                } else if (moving) {
                    previousRawX = event.rawX
                    previousRawY = event.rawY
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                movedSinceDown = true
                cancelPendingLongPress()
                cancelTapSequence()
                previousRawX = event.rawX
                previousRawY = event.rawY
            }
            MotionEvent.ACTION_POINTER_UP -> {
                movedSinceDown = true
                cancelPendingLongPress()
                cancelTapSequence()
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    previousRawX = event.getRawX(remaining)
                    previousRawY = event.getRawY(remaining)
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingLongPress()
                val geometryChanged = movedSinceDown
                if (!movedSinceDown && !scales.isInProgress && !longPressTriggered) {
                    if (fastMenuForGesture) {
                        rememberMenuAnchor(event)
                        showActionMenu(true)
                    } else {
                        registerTap(event)
                    }
                }
                moving = false
                if (geometryChanged) {
                    listener.onBringToFront()
                    listener.onGeometrySettled()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val geometryChanged = movedSinceDown
                moving = false
                cancelPendingLongPress()
                cancelTapSequence()
                if (geometryChanged) {
                    listener.onGeometrySettled()
                }
            }
        }
        return true
    }

    private fun handleLongPressTimeout() {
        if (!isAttachedToWindow || fastMenuForGesture || !moving || movedSinceDown || scales.isInProgress) {
            return
        }
        longPressTriggered = true
        moving = false
        cancelTapSequence()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showActionMenu(false)
    }

    private fun showActionMenu(reportClick: Boolean) {
        if (busyOverlay != null || actionMenu != null) {
            return
        }
        if (reportClick) {
            super.performClick()
        }
        positionMenuAnchor()
        val providers = SnapperActionRegistry.enabledPinActions(context)
        announceForAccessibility(menuAnnouncement(providers))
        actionMenu = showPinActionMenu(context, menuAnchor, providers, actionIcons, ::activateMenuItem) {
            actionMenu = null
        }
    }

    private fun rememberMenuAnchor(event: MotionEvent) {
        menuAnchorLocalX = event.x
        menuAnchorLocalY = event.y
        menuAnchorRemembered = true
    }

    private fun positionMenuAnchor() {
        val requestedX = if (menuAnchorRemembered) menuAnchorLocalX else width * 0.5f
        val requestedY = if (menuAnchorRemembered) menuAnchorLocalY else height * 0.5f
        menuAnchor.translationX = Math.max(0f, Math.min(requestedX, Math.max(0, width - 1).toFloat()))
        menuAnchor.translationY = Math.max(0f, Math.min(requestedY, Math.max(0, height - 1).toFloat()))
    }

    private fun menuAnnouncement(providers: List<SnapperActionRegistry.Action>): String {
        val message = StringBuilder(context.getString(R.string.pin_menu_actions))
            .append(": ")
            .append(context.getString(R.string.pin_menu_remove))
        for (action in providers) {
            message.append(", ").append(action.menuTitle(context))
        }
        return message.toString()
    }

    override fun onDetachedFromWindow() {
        cancelPendingLongPress()
        cancelTapSequence()
        actionMenu?.dismiss()
        actionMenu = null
        busyOverlay?.stop()
        moving = false
        super.onDetachedFromWindow()
        if (recycleBitmapOnDetach && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    override fun performClick(): Boolean {
        if (!isAttachedToWindow || busyOverlay != null || actionMenu != null) {
            return false
        }
        super.performClick()
        showActionMenu(false)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val canOpenActions = busyOverlay == null && actionMenu == null
        info.isClickable = canOpenActions
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
        if (canOpenActions) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                    context.getString(R.string.pin_menu_actions),
                ),
            )
        }
        if (busyOverlay == null) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id,
                    context.getString(R.string.pin_menu_remove),
                ),
            )
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id) {
            menuAnchorRemembered = false
            return performClick()
        }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id) {
            if (busyOverlay != null || !isAttachedToWindow) {
                return false
            }
            cancelPendingLongPress()
            cancelTapSequence()
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            listener.onClose()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun restoreAccessibilityDescription() {
        contentDescription = "Pinned screenshot. Double tap to open actions."
    }

    private fun activateMenuItem(itemId: Int): Boolean {
        if (busyOverlay != null) {
            return true
        }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (itemId == MENU_REMOVE) {
            listener.onClose()
            return true
        }
        when (val action = itemId - MENU_ACTION_BASE) {
            SelectionAction.COPY -> listener.onCopy()
            SelectionAction.SAVE -> listener.onSave()
            SelectionAction.SHARE -> listener.onShare()
            SelectionAction.OCR -> listener.onOcr()
            SelectionAction.QR -> {
                announceForAccessibility("Scanning pinned screenshot for a QR code")
                listener.onScanQr()
            }
            SelectionAction.IMGUR -> listener.onImgur()
            SelectionAction.URL_SCHEME -> listener.onUrlScheme()
            else -> {
                val provider = SnapperActionRegistry.actionForSelectionCode(action)
                if (provider == null || !provider.isExternal) return false
                listener.onExternalAction(action)
            }
        }
        return true
    }

    private fun canContinueTapSequence(event: MotionEvent): Boolean {
        if (tapCount == 0 || event.eventTime > tapSequenceDeadline) {
            return false
        }
        val deltaX = event.rawX - lastTapRawX
        val deltaY = event.rawY - lastTapRawY
        return deltaX * deltaX + deltaY * deltaY <= multiTapSlop * multiTapSlop
    }

    private fun registerTap(event: MotionEvent) {
        var continues = tapCount > 0 && tapSequenceCandidate
        if (continues) {
            val deltaX = event.rawX - lastTapRawX
            val deltaY = event.rawY - lastTapRawY
            continues = deltaX * deltaX + deltaY * deltaY <= multiTapSlop * multiTapSlop
        }
        if (!continues) {
            cancelTapSequence()
        }

        tapCount++
        lastTapRawX = event.rawX
        lastTapRawY = event.rawY
        tapSequenceDeadline = event.eventTime + multiTapTimeoutMs
        tapSequenceCandidate = false
        removeCallbacks(finishTapSequence)

        if (tapCount >= 3) {
            clearTapSequenceState()
            performTapAction(SnapSettings.tripleTapAction(context))
        } else {
            postDelayed(finishTapSequence, multiTapTimeoutMs.toLong())
        }
    }

    private fun performTapAction(action: Int) {
        if (busyOverlay != null) {
            return
        }
        when (action) {
            SnapSettings.TAP_ACTION_DISMISS -> {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                listener.onClose()
            }
            SnapSettings.TAP_ACTION_TOGGLE_SHADOW -> {
                shadowEnabled = !shadowEnabled
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                announceForAccessibility(if (shadowEnabled) "Pin shadow shown" else "Pin shadow hidden")
            }
        }
    }

    private fun cancelPendingLongPress() {
        removeCallbacks(longPress)
    }

    private fun cancelTapSequence() {
        removeCallbacks(finishTapSequence)
        clearTapSequenceState()
    }

    private fun clearTapSequenceState() {
        tapCount = 0
        tapSequenceDeadline = 0
        tapSequenceCandidate = false
    }

    companion object {
        fun frameInset(context: Context): Int {
            if (SnapSettings.shadowColor(context) == SnapSettings.SHADOW_NONE) {
                return 0
            }
            return Math.ceil(5.0 * context.resources.displayMetrics.density).toInt()
        }
    }
}
