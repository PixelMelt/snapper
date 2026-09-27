package com.snapper.android.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.snapper.android.actions.SnapperActionRegistry
import com.snapper.android.settings.SnapSettings
import com.snapper.android.types.SelectionAction

internal class SelectionView(
    context: Context,
    private val frozenBitmap: Bitmap?,
    private val instantMode: Boolean,
    initialNormalized: RectF?,
    private val frozenRotation: Int,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        fun onAction(
            rect: RectF,
            surfaceWidth: Int,
            surfaceHeight: Int,
            action: Int,
            frozenQuarterTurns: Int,
            actionSurfaceRotation: Int,
        )

        fun onFeedback(message: String)

        fun onCancelled()
    }

    private val initialNormalized = initialNormalized?.let(::RectF)
    private val actionOrder = SnapperActionRegistry.enabledCropActions(context).toTypedArray()
    private val railIcons = IntArray(actionOrder.size + 1) { index ->
        if (index == actionOrder.size) SnapperActionRegistry.CLOSE_ICON else actionOrder[index].iconIndex
    }
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val controls = SelectionControls(density)
    private val painter = SelectionPainter(controls, SnapperActionRegistry.decodeActionIcons(context))
    private val accessibility = SelectionAccessibility(this)
    private val selection = RectF()
    private val gestureStartSelection = RectF()
    private val accessibilityBounds = Rect()
    private val accessibilityVisibleBounds = Rect()
    private lateinit var colorSchemeObservation: AutoCloseable
    private val outsideLongPress = Runnable {
        if (!longPressArmed || trackedControl != CONTROL_NONE) return@Runnable
        longPressArmed = false
        longPressTriggered = true
        activeGesture = GESTURE_NONE
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener.onCancelled()
    }

    private var startX = 0f
    private var startY = 0f
    private var latestX = 0f
    private var latestY = 0f
    private var actionScrollAtDown = 0f
    private var actionScrollVelocity = 0f
    private var actionLastMotionX = 0f
    private var actionLastMotionTime = 0L
    private var actionScrollAnimator: ValueAnimator? = null
    private var activeGesture = GESTURE_NONE
    private var trackedControl = CONTROL_NONE
    private var pressedControl = CONTROL_NONE
    private var surfaceRotation = frozenRotation
    private var trackingActionRail = false
    private var scrollingActions = false
    private var selectionReady = false
    private var initialApplied = false
    private var longPressArmed = false
    private var longPressTriggered = false
    private var lastCropTapTime = 0L
    private var lastCropTapX = 0f
    private var lastCropTapY = 0f
    private var accessibilitySignature = Long.MIN_VALUE
    private var accessibilityScrollX = Float.NaN
    private var accessibilityMaximumScrollX = Float.NaN
    private var accessibilityPending = false
    private var gestureExclusion: List<Rect> = emptyList()

    var actionScrollX = 0f
        private set

    init {
        controls.updatePointScale(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        painter.onPointScaleChanged()
        applyControlTint()
        contentDescription = if (instantMode) {
            "Instant screen selection. Drag an area to float it immediately. Use Close selection to cancel."
        } else {
            selectionDescription()
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibility)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateGestureExclusion()
    }

    /**
     * Crop drags start and end anywhere on the overlay, including the left and right strips the
     * system reserves for the back gesture, so a drag across the full width would otherwise be
     * stolen as a back swipe. Claim the whole overlay instead.
     *
     * The window manager honours only the bottom-most 200dp of an exclusion per edge unless the
     * window asks for sticky-immersive navigation bars, which [suppressSystemGestures] does when
     * the overlay attaches.
     */
    private fun updateGestureExclusion() {
        val bounds = if (width <= 0 || height <= 0) emptyList() else listOf(Rect(0, 0, width, height))
        if (bounds == gestureExclusion) return
        gestureExclusion = bounds
        systemGestureExclusionRects = bounds
    }

    private fun suppressSystemGestures() {
        val controller = windowInsetsController ?: return
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsets.Type.navigationBars())
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val remappingSelection = oldWidth > 0 && oldHeight > 0 && !selection.isEmpty
        if (remappingSelection) cancelGestureForRotation()
        updatePointScale(width, height)
        if (remappingSelection) {
            val currentRotation = currentSurfaceRotation()
            rotateSelection((currentRotation - surfaceRotation + 4) % 4, oldWidth, oldHeight, width, height)
            surfaceRotation = currentRotation
            constrainSelection()
            selectionReady = selectionLargeEnough()
            invalidate()
            return
        }
        surfaceRotation = currentSurfaceRotation()
        if (initialApplied || initialNormalized == null || width <= 0 || height <= 0) return
        selection.set(
            initialNormalized.left * width, initialNormalized.top * height,
            initialNormalized.right * width, initialNormalized.bottom * height,
        )
        constrainSelection()
        selectionReady = selectionLargeEnough()
        if (!selectionReady) selection.setEmpty()
        initialApplied = true
    }

    fun onDisplayRotationChanged(currentRotation: Int) {
        val quarterTurns = (currentRotation - surfaceRotation + 4) % 4
        if (quarterTurns == 0) {
            return
        }
        cancelGestureForRotation()
        if (width > 0 && height > 0 && !selection.isEmpty) {
            rotateSelection(quarterTurns, width, height, width, height)
            constrainSelection()
            selectionReady = selectionLargeEnough()
        }
        surfaceRotation = currentRotation
        invalidate()
    }

    private fun rotateSelection(quarterTurns: Int, oldWidth: Int, oldHeight: Int, width: Int, height: Int) {
        val left = selection.left / oldWidth
        val top = selection.top / oldHeight
        val right = selection.right / oldWidth
        val bottom = selection.bottom / oldHeight
        when (quarterTurns) {
            1 -> selection.set((1f - bottom) * width, left * height, (1f - top) * width, right * height)
            2 -> selection.set((1f - right) * width, (1f - bottom) * height, (1f - left) * width, (1f - top) * height)
            3 -> selection.set(top * width, (1f - right) * height, bottom * width, (1f - left) * height)
            else -> selection.set(left * width, top * height, right * width, bottom * height)
        }
    }

    private fun selectionLargeEnough(): Boolean =
        selection.width() >= MINIMUM_SIZE_PX && selection.height() >= MINIMUM_SIZE_PX

    private fun cancelGestureForRotation() {
        removeCallbacks(outsideLongPress)
        longPressArmed = false
        longPressTriggered = false
        activeGesture = GESTURE_NONE
        trackedControl = CONTROL_NONE
        pressedControl = CONTROL_NONE
        trackingActionRail = false
        scrollingActions = false
        cancelActionScrollAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frozenBitmap != null && !frozenBitmap.isRecycled) {
            painter.drawFrozen(canvas, frozenBitmap, frozenQuarterTurns(), width, height)
        }
        painter.drawDimmed(canvas, selection, width, height)
        if (instantMode) {
            updateControlBounds()
            return
        }
        painter.drawCropGuides(canvas, selection)
        updateControlBounds()
        if (!selection.isEmpty) painter.drawCropFrame(canvas, selection, width, height)
        if (selectionReady) painter.drawActionRail(canvas, railIcons, actionScrollX, pressedRailIndex())
        if (controls.bottomControlsVisible) {
            painter.drawBottomControls(
                canvas,
                pressedControl == SelectionAction.SCREENSHOT,
                pressedControl == CONTROL_CANCEL,
            )
        }
    }

    private fun pressedRailIndex(): Int {
        if (scrollingActions || pressedControl == CONTROL_NONE) return -1
        for (index in 0 until itemCount()) {
            if (actionAt(index) == pressedControl) return index
        }
        return -1
    }

    private fun applyControlTint() {
        painter.applyControlTint(SnapSettings.colorSchemeColor(context))
    }

    private fun updateControlBounds() {
        actionScrollX = controls.update(
            width, height, topInset(), selection, selectionReady, instantMode, itemCount(), actionScrollX,
        )
        syncAccessibility()
    }

    private fun syncAccessibility() {
        var signature = 17L
        signature = mixSignature(signature, width)
        signature = mixSignature(signature, height)
        signature = mixSignature(signature, if (controls.bottomControlsVisible) 1 else 0)
        signature = mixSignature(signature, if (selectionReady) 1 else 0)
        signature = mixSignature(signature, java.lang.Float.floatToIntBits(actionScrollX))
        for (rect in arrayOf(controls.actionBar, controls.screenshot, controls.cancel)) {
            signature = mixSignature(signature, java.lang.Float.floatToIntBits(rect.left))
            signature = mixSignature(signature, java.lang.Float.floatToIntBits(rect.top))
            signature = mixSignature(signature, java.lang.Float.floatToIntBits(rect.right))
            signature = mixSignature(signature, java.lang.Float.floatToIntBits(rect.bottom))
        }
        if (signature != accessibilitySignature) {
            accessibilitySignature = signature
            accessibilityPending = true
        }
        if (!accessibilityPending || isAccessibilityGeometryMoving() || !isAttachedToWindow) return
        accessibilityPending = false
        val maximum = maximumActionScroll()
        if (!accessibilityScrollX.isNaN() && (
                Math.abs(actionScrollX - accessibilityScrollX) >= 0.5f ||
                    Math.abs(maximum - accessibilityMaximumScrollX) >= 0.5f
                )
        ) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        }
        accessibilityScrollX = actionScrollX
        accessibilityMaximumScrollX = maximum
        accessibility.invalidateRoot()
    }

    private fun isAccessibilityGeometryMoving(): Boolean =
        activeGesture != GESTURE_NONE || scrollingActions || actionScrollAnimator?.isRunning == true

    fun populateScrollEvent(event: AccessibilityEvent) {
        val maximum = maximumActionScroll()
        val cellWidth = controls.actionCellPixels()
        event.isScrollable = maximum > 0f
        event.scrollX = Math.round(actionScrollX)
        event.maxScrollX = Math.round(maximum)
        event.scrollDeltaX = Math.round(actionScrollX - accessibilityScrollX)
        event.itemCount = itemCount()
        event.fromIndex = Math.max(0, Math.floor((actionScrollX / cellWidth).toDouble()).toInt())
        event.toIndex = Math.min(
            itemCount() - 1,
            Math.max(0, Math.ceil(((actionScrollX + controls.actionBar.width()) / cellWidth).toDouble()).toInt() - 1),
        )
    }

    fun maximumActionScroll(): Float = controls.maximumActionScroll(itemCount())

    fun performHostAccessibilityAction(action: Int): Boolean {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id) {
            activateControl(CONTROL_CANCEL)
            return true
        }
        val forward = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
        val right = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        val backward = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        val left = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        if (action == forward || action == right) return scrollActionsForAccessibility(1)
        if (action == backward || action == left) return scrollActionsForAccessibility(-1)
        return false
    }

    fun activateVirtualView(virtualViewId: Int): Boolean {
        if (!virtualViewBounds(virtualViewId, accessibilityBounds)) return false
        val control = controlForVirtualView(virtualViewId)
        if (control == CONTROL_NONE) return false
        activateControl(control)
        return true
    }

    private fun scrollActionsForAccessibility(direction: Int): Boolean {
        val target = clampFloat(actionScrollX + direction * controls.actionCellPixels(), 0f, maximumActionScroll())
        if (Math.abs(target - actionScrollX) < 0.5f) return false
        cancelActionScrollAnimation()
        actionScrollX = target
        updateControlBounds()
        postInvalidateOnAnimation()
        return true
    }

    fun collectVisibleVirtualViews(ids: MutableList<Int>) {
        for (index in 0 until itemCount()) {
            val virtualViewId = VIRTUAL_RAIL_BASE_ID + index
            if (virtualViewBounds(virtualViewId, accessibilityBounds)) ids.add(virtualViewId)
        }
        if (virtualViewBounds(VIRTUAL_CAMERA_ID, accessibilityBounds)) ids.add(VIRTUAL_CAMERA_ID)
        if (virtualViewBounds(VIRTUAL_CLOSE_ID, accessibilityBounds)) ids.add(VIRTUAL_CLOSE_ID)
    }

    fun virtualViewAt(x: Float, y: Float): Int {
        if (virtualViewBounds(VIRTUAL_CAMERA_ID, accessibilityBounds) &&
            accessibilityBounds.contains(x.toInt(), y.toInt())
        ) {
            return VIRTUAL_CAMERA_ID
        }
        if (virtualViewBounds(VIRTUAL_CLOSE_ID, accessibilityBounds) &&
            accessibilityBounds.contains(x.toInt(), y.toInt())
        ) {
            return VIRTUAL_CLOSE_ID
        }
        for (index in 0 until itemCount()) {
            val virtualViewId = VIRTUAL_RAIL_BASE_ID + index
            if (virtualViewBounds(virtualViewId, accessibilityBounds) &&
                accessibilityBounds.contains(x.toInt(), y.toInt())
            ) {
                return virtualViewId
            }
        }
        return ExploreByTouchHelper.INVALID_ID
    }

    fun virtualViewBounds(virtualViewId: Int, outBounds: Rect): Boolean {
        if (virtualViewId == VIRTUAL_CAMERA_ID) {
            return !instantMode && controls.bottomControlsVisible &&
                setClippedAccessibilityBounds(controls.screenshot, outBounds)
        }
        if (virtualViewId == VIRTUAL_CLOSE_ID) {
            if (instantMode) {
                val size = Math.max(controls.minimumTouchTarget(), controls.actionCellPixels())
                val bottom = height - controls.controlBottomInset()
                return setClippedAccessibilityBounds(
                    (width - size) / 2f, bottom - size, (width + size) / 2f, bottom, outBounds,
                )
            }
            return controls.bottomControlsVisible && setClippedAccessibilityBounds(controls.cancel, outBounds)
        }
        val index = virtualViewId - VIRTUAL_RAIL_BASE_ID
        if (instantMode || !selectionReady || controls.actionBar.isEmpty || index < 0 || index >= itemCount()) {
            return false
        }
        val bar = controls.actionBar
        val left = controls.railCellLeft(index, actionScrollX)
        val right = left + controls.actionCellPixels()
        return setClippedAccessibilityBounds(
            Math.max(left, bar.left), bar.top, Math.min(right, bar.right), bar.bottom, outBounds,
        )
    }

    private fun setClippedAccessibilityBounds(bounds: RectF, outBounds: Rect): Boolean =
        setClippedAccessibilityBounds(bounds.left, bounds.top, bounds.right, bounds.bottom, outBounds)

    private fun setClippedAccessibilityBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        outBounds: Rect,
    ): Boolean {
        if (right <= left || bottom <= top) return false
        outBounds.set(
            Math.floor(left.toDouble()).toInt(), Math.floor(top.toDouble()).toInt(),
            Math.ceil(right.toDouble()).toInt(), Math.ceil(bottom.toDouble()).toInt(),
        )
        if (!outBounds.intersect(0, 0, width, height)) return false
        if (!getLocalVisibleRect(accessibilityVisibleBounds) || !outBounds.intersect(accessibilityVisibleBounds)) {
            return false
        }
        return !outBounds.isEmpty
    }

    private fun controlForVirtualView(virtualViewId: Int): Int {
        if (virtualViewId == VIRTUAL_CAMERA_ID) return SelectionAction.SCREENSHOT
        if (virtualViewId == VIRTUAL_CLOSE_ID) return CONTROL_CANCEL
        val index = virtualViewId - VIRTUAL_RAIL_BASE_ID
        return if (index >= 0 && index < itemCount()) actionAt(index) else CONTROL_NONE
    }

    fun accessibilityLabel(virtualViewId: Int): String {
        if (virtualViewId == VIRTUAL_CAMERA_ID) return "Take full-screen screenshot"
        if (virtualViewId == VIRTUAL_CLOSE_ID) return "Close selection"
        val index = virtualViewId - VIRTUAL_RAIL_BASE_ID
        if (index < 0 || index >= itemCount()) return "Selection action"
        return when (actionAt(index)) {
            SelectionAction.FLOAT -> "Float selected area"
            SelectionAction.COPY -> "Copy selected area"
            SelectionAction.SAVE -> "Save selected area"
            SelectionAction.SHARE -> "Share selected area"
            SelectionAction.QR -> "Scan selected area for a QR code"
            SelectionAction.OCR -> "Recognize text in selected area"
            SelectionAction.IMGUR -> "Upload selected area to Imgur"
            SelectionAction.URL_SCHEME -> "Save selected area and open configured URL"
            CONTROL_CANCEL -> "Close selection"
            else -> "Selection action"
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                activateControl(CONTROL_CANCEL)
            }
            return true
        }
        return accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibility.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelOutsideLongPress()
                longPressTriggered = false
                updateControlBounds()
                trackedControl = hitControl(x, y)
                pressedControl = trackedControl
                trackingActionRail = controls.actionBar.contains(x, y)
                scrollingActions = false
                cancelActionScrollAnimation()
                actionScrollAtDown = actionScrollX
                actionScrollVelocity = 0f
                actionLastMotionX = x
                actionLastMotionTime = event.eventTime
                startX = x
                latestX = x
                startY = y
                latestY = y
                gestureStartSelection.set(selection)
                if (trackedControl != CONTROL_NONE) {
                    activeGesture = GESTURE_NONE
                    invalidate()
                    return true
                }
                val outsideCrop = selection.isEmpty ||
                    (!selection.contains(x, y) && !isResizeGesture(hitSelectionGesture(x, y)))
                activeGesture = hitSelectionGesture(x, y)
                if (outsideCrop) armOutsideLongPress()
                if (activeGesture == GESTURE_NEW) {
                    selection.set(x, y, x, y)
                    selectionReady = false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                latestX = x
                latestY = y
                if (distanceFromStartSquared(x, y) > touchSlop * touchSlop) {
                    cancelOutsideLongPress()
                }
                if (trackedControl != CONTROL_NONE) {
                    if (trackingActionRail && handleActionRailMove(x, y, event.eventTime)) return true
                    val current = hitControl(x, y)
                    val nextPressed = if (current == trackedControl) trackedControl else CONTROL_NONE
                    if (pressedControl != nextPressed) {
                        pressedControl = nextPressed
                        invalidate()
                    }
                } else {
                    updateSelectionGesture(x, y)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                latestX = x
                latestY = y
                cancelOutsideLongPress()
                if (longPressTriggered) {
                    longPressTriggered = false
                    return true
                }
                if (trackedControl != CONTROL_NONE) {
                    val settleRail = scrollingActions
                    val activate = if (!scrollingActions && hitControl(x, y) == trackedControl) {
                        trackedControl
                    } else {
                        CONTROL_NONE
                    }
                    resetControlTracking()
                    invalidate()
                    if (settleRail) {
                        settleActionScroll()
                        return true
                    }
                    if (activate != CONTROL_NONE) activateControl(activate)
                    return true
                }
                updateSelectionGesture(x, y)
                finishSelectionGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelOutsideLongPress()
                longPressTriggered = false
                val settleRail = scrollingActions
                resetControlTracking()
                activeGesture = GESTURE_NONE
                if (!gestureStartSelection.isEmpty) {
                    selection.set(gestureStartSelection)
                    selectionReady = true
                } else {
                    selection.setEmpty()
                    selectionReady = false
                }
                if (settleRail) settleActionScroll()
                invalidate()
                return true
            }
            else -> return true
        }
    }

    private fun handleActionRailMove(x: Float, y: Float, eventTime: Long): Boolean {
        val dx = x - startX
        val dy = y - startY
        if (!scrollingActions && maximumActionScroll() > 0f &&
            Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)
        ) {
            scrollingActions = true
            pressedControl = CONTROL_NONE
        }
        if (!scrollingActions) return false
        val elapsed = eventTime - actionLastMotionTime
        if (elapsed > 0L) {
            val instantaneous = (actionLastMotionX - x) * 1_000f / elapsed
            actionScrollVelocity = actionScrollVelocity * 0.65f + instantaneous * 0.35f
        }
        actionLastMotionX = x
        actionLastMotionTime = eventTime
        actionScrollX = clampFloat(actionScrollAtDown - dx, 0f, maximumActionScroll())
        invalidate()
        return true
    }

    private fun settleActionScroll() {
        val maximum = maximumActionScroll()
        if (maximum <= 0f) {
            actionScrollX = 0f
            return
        }
        val page = controls.actionCellPixels()
        val projected = actionScrollX + actionScrollVelocity * 0.12f
        val target = clampFloat(Math.round(projected / page) * page, 0f, maximum)
        if (Math.abs(target - actionScrollX) < 0.5f || SnapSettings.reduceAnimations(context)) {
            actionScrollX = target
            invalidate()
            return
        }
        val animator = ValueAnimator.ofFloat(actionScrollX, target)
        animator.duration = 120L
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            actionScrollX = animation.animatedValue as Float
            invalidate()
        }
        actionScrollAnimator = animator
        animator.start()
    }

    private fun cancelActionScrollAnimation() {
        val animator = actionScrollAnimator ?: return
        animator.cancel()
        actionScrollAnimator = null
    }

    private fun resetControlTracking() {
        trackedControl = CONTROL_NONE
        pressedControl = CONTROL_NONE
        trackingActionRail = false
        scrollingActions = false
    }

    private fun armOutsideLongPress() {
        longPressArmed = true
        postDelayed(outsideLongPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelOutsideLongPress() {
        if (!longPressArmed) return
        longPressArmed = false
        removeCallbacks(outsideLongPress)
    }

    private fun distanceFromStartSquared(x: Float, y: Float): Float {
        val dx = x - startX
        val dy = y - startY
        return dx * dx + dy * dy
    }

    private fun updateSelectionGesture(x: Float, y: Float) {
        if (activeGesture == GESTURE_NONE) return
        if (activeGesture == GESTURE_NEW) {
            selection.set(Math.min(startX, x), Math.min(startY, y), Math.max(startX, x), Math.max(startY, y))
            snapSelectionEdges()
        } else if (activeGesture == GESTURE_MOVE) {
            selection.set(gestureStartSelection)
            selection.offset(x - startX, y - startY)
            constrainMovedSelection()
        } else {
            selection.set(gestureStartSelection)
            resizeSelection(x, y)
        }
        invalidate()
    }

    private fun finishSelectionGesture() {
        val finishedGesture = activeGesture
        activeGesture = GESTURE_NONE
        val tap = Math.abs(latestX - startX) <= touchSlop && Math.abs(latestY - startY) <= touchSlop
        if (!tap) lastCropTapTime = 0L
        if (tap && !instantMode && isDoubleCropTap(latestX, latestY)) {
            if (finishedGesture == GESTURE_NEW && !gestureStartSelection.isEmpty) {
                selection.set(gestureStartSelection)
                selectionReady = true
            }
            if (selectionReady) {
                announceForAccessibility("Floating selected area")
                listener.onAction(
                    RectF(selection), width, height, SelectionAction.FLOAT,
                    frozenQuarterTurns(), currentSurfaceRotation(),
                )
            }
            return
        }
        if (!selectionLargeEnough()) {
            selection.set(gestureStartSelection)
            selectionReady = !selection.isEmpty
            if (!selectionReady) selection.setEmpty()
            if (!tap) showMinimumHint() else invalidate()
            return
        }
        selectionReady = true
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        performClick()
        if (instantMode) {
            listener.onAction(
                RectF(selection), width, height, SelectionAction.FLOAT,
                frozenQuarterTurns(), currentSurfaceRotation(),
            )
        } else {
            invalidate()
        }
    }

    private fun isDoubleCropTap(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val maximumDistance = 48f * density
        val dx = x - lastCropTapX
        val dy = y - lastCropTapY
        val doubled = now - lastCropTapTime <= 360 && dx * dx + dy * dy <= maximumDistance * maximumDistance
        lastCropTapTime = if (doubled) 0 else now
        lastCropTapX = x
        lastCropTapY = y
        return doubled
    }

    private fun hitSelectionGesture(x: Float, y: Float): Int {
        if (selection.isEmpty) return GESTURE_NEW
        val hit = HANDLE_HIT_DP * density
        val leftRoom = selection.left
        val rightRoom = width - selection.right
        val topRoom = selection.top
        val bottomRoom = height - selection.bottom
        val horizontal = if (y < selection.top - hit || y > selection.bottom + hit) 0 else nearerHandle(
            x - selection.left, handleReach(leftRoom, selection.width()), GESTURE_LEFT,
            selection.right - x, handleReach(rightRoom, selection.width()), GESTURE_RIGHT,
        )
        val vertical = if (x < selection.left - hit || x > selection.right + hit) 0 else nearerHandle(
            y - selection.top, handleReach(topRoom, selection.height()), GESTURE_TOP,
            selection.bottom - y, handleReach(bottomRoom, selection.height()), GESTURE_BOTTOM,
        )
        if (horizontal == 0) return if (vertical == 0) GESTURE_MOVE else vertical
        if (vertical == 0) return horizontal
        val horizontalRoom = if (horizontal == GESTURE_LEFT) leftRoom else rightRoom
        val verticalRoom = if (vertical == GESTURE_TOP) topRoom else bottomRoom
        if (horizontalRoom >= hit && verticalRoom >= hit) {
            val verticalEndInset = Math.min(END_INSET_DP * density, selection.height() / 2f)
            val horizontalEndInset = Math.min(END_INSET_DP * density, selection.width() / 2f)
            if (y >= selection.top + verticalEndInset && y <= selection.bottom - verticalEndInset) return horizontal
            if (x >= selection.left + horizontalEndInset && x <= selection.right - horizontalEndInset) return vertical
        }
        return horizontal or vertical
    }

    private fun nearerHandle(
        firstInside: Float,
        firstReach: Float,
        first: Int,
        secondInside: Float,
        secondReach: Float,
        second: Int,
    ): Int {
        val hit = HANDLE_HIT_DP * density
        val firstHit = firstInside >= -hit && firstInside <= firstReach
        val secondHit = secondInside >= -hit && secondInside <= secondReach
        return when {
            firstHit && secondHit -> if (Math.abs(firstInside) <= Math.abs(secondInside)) first else second
            firstHit -> first
            secondHit -> second
            else -> 0
        }
    }

    private fun handleReach(roomOutside: Float, selectionSize: Float): Float {
        val hit = HANDLE_HIT_DP * density
        val pinned = clampFloat((hit - roomOutside) / hit, 0f, 1f)
        val reach = hit + (BORDER_HANDLE_REACH_DP * density - hit) * pinned
        return Math.min(reach, Math.max(hit, selectionSize / 3f))
    }

    private fun isResizeGesture(gesture: Int): Boolean =
        (gesture and (GESTURE_LEFT or GESTURE_TOP or GESTURE_RIGHT or GESTURE_BOTTOM)) != 0

    private fun resizeSelection(x: Float, y: Float) {
        if ((activeGesture and (GESTURE_LEFT or GESTURE_RIGHT)) != 0) {
            val draggingLeft = (activeGesture and GESTURE_LEFT) != 0
            val anchorX = if (draggingLeft) gestureStartSelection.right else gestureStartSelection.left
            val edgeX = if (draggingLeft) gestureStartSelection.left else gestureStartSelection.right
            val targetX = dragEdge(edgeX, x, startX, width.toFloat())
            selection.left = Math.min(anchorX, targetX)
            selection.right = Math.max(anchorX, targetX)
        }
        if ((activeGesture and (GESTURE_TOP or GESTURE_BOTTOM)) != 0) {
            val draggingTop = (activeGesture and GESTURE_TOP) != 0
            val anchorY = if (draggingTop) gestureStartSelection.bottom else gestureStartSelection.top
            val edgeY = if (draggingTop) gestureStartSelection.top else gestureStartSelection.bottom
            val targetY = dragEdge(edgeY, y, startY, height.toFloat())
            selection.top = Math.min(anchorY, targetY)
            selection.bottom = Math.max(anchorY, targetY)
        }
    }

    private fun dragEdge(edge: Float, finger: Float, fingerStart: Float, maximum: Float): Float {
        val threshold = BORDER_SNAP_DP * density
        val travel = finger - fingerStart
        if (travel < 0f && finger <= threshold) return 0f
        if (travel > 0f && finger >= maximum - threshold) return maximum
        return snap(edge + travel, maximum)
    }

    private fun snapSelectionEdges() {
        selection.left = snap(selection.left, width.toFloat())
        selection.top = snap(selection.top, height.toFloat())
        selection.right = snap(selection.right, width.toFloat())
        selection.bottom = snap(selection.bottom, height.toFloat())
        constrainSelection()
    }

    private fun constrainSelection() {
        selection.sort()
        selection.left = clampFloat(selection.left, 0f, width.toFloat())
        selection.top = clampFloat(selection.top, 0f, height.toFloat())
        selection.right = clampFloat(selection.right, 0f, width.toFloat())
        selection.bottom = clampFloat(selection.bottom, 0f, height.toFloat())
    }

    private fun constrainMovedSelection() {
        if (selection.left < 0) selection.offset(-selection.left, 0f)
        if (selection.right > width) selection.offset(width - selection.right, 0f)
        if (selection.top < 0) selection.offset(0f, -selection.top)
        if (selection.bottom > height) selection.offset(0f, height - selection.bottom)
    }

    private fun snap(value: Float, maximum: Float): Float {
        val clamped = clampFloat(value, 0f, maximum)
        return if (clamped <= maximum - clamped) magnetize(clamped) else maximum - magnetize(maximum - clamped)
    }

    private fun magnetize(distance: Float): Float {
        val threshold = BORDER_SNAP_DP * density
        val zone = BORDER_MAGNET_DP * density
        if (distance <= threshold) return 0f
        if (distance >= zone) return distance
        return (distance - threshold) * zone / (zone - threshold)
    }

    private fun hitControl(x: Float, y: Float): Int {
        if (instantMode) return CONTROL_NONE
        if (controls.bottomControlsVisible && controls.cancel.contains(x, y)) return CONTROL_CANCEL
        if (controls.bottomControlsVisible && controls.screenshot.contains(x, y)) {
            return SelectionAction.SCREENSHOT
        }
        if (selectionReady) {
            val index = controls.railIndexAt(x, y, itemCount(), actionScrollX)
            if (index >= 0) return actionAt(index)
        }
        return CONTROL_NONE
    }

    private fun activateControl(control: Int) {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        performClick()
        if (control == CONTROL_CANCEL) {
            announceForAccessibility("Selection cancelled")
            listener.onCancelled()
        } else if (control == SelectionAction.SCREENSHOT && !instantMode) {
            announceForAccessibility("Taking a full-screen screenshot")
            listener.onAction(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                width, height, SelectionAction.SCREENSHOT, frozenQuarterTurns(), currentSurfaceRotation(),
            )
        } else if (selectionReady && isCropAction(control)) {
            announceForAccessibility(actionAnnouncement(control))
            listener.onAction(
                RectF(selection), width, height, control, frozenQuarterTurns(), currentSurfaceRotation(),
            )
        }
    }

    private fun isCropAction(action: Int): Boolean =
        action == SelectionAction.FLOAT || action == SelectionAction.SHARE ||
            action == SelectionAction.COPY || action == SelectionAction.SAVE ||
            action == SelectionAction.QR || action == SelectionAction.OCR ||
            action == SelectionAction.IMGUR || action == SelectionAction.URL_SCHEME

    private fun actionAnnouncement(action: Int): String = when (action) {
        SelectionAction.FLOAT -> "Floating selected area"
        SelectionAction.COPY -> "Copying selected area"
        SelectionAction.SAVE -> "Saving selected area"
        SelectionAction.SHARE -> "Sharing selected area"
        SelectionAction.QR -> "Scanning selected area for a QR code"
        SelectionAction.OCR -> "Recognizing text in selected area"
        SelectionAction.IMGUR -> "Uploading selected area to Imgur"
        SelectionAction.URL_SCHEME -> "Saving selected area and opening its configured URL"
        else -> "Selection action started"
    }

    private fun showMinimumHint() {
        performHapticFeedback(HapticFeedbackConstants.REJECT)
        listener.onFeedback("Drag a larger area")
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        colorSchemeObservation = SnapSettings.observe(context, SnapSettings.Setting.COLOR_SCHEME) {
            applyControlTint()
            postInvalidateOnAnimation()
        }
        applyControlTint()
        suppressSystemGestures()
        accessibilitySignature = Long.MIN_VALUE
        accessibilityPending = true
        accessibilityScrollX = Float.NaN
        accessibilityMaximumScrollX = Float.NaN
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        colorSchemeObservation.close()
        cancelOutsideLongPress()
        cancelActionScrollAnimation()
        accessibilityPending = false
        accessibilitySignature = Long.MIN_VALUE
        super.onDetachedFromWindow()
    }

    private fun updatePointScale(width: Int, height: Int) {
        val scrollScale = controls.updatePointScale(width, height)
        if (scrollScale != 1f) {
            actionScrollX *= scrollScale
            actionScrollAtDown *= scrollScale
            actionScrollVelocity *= scrollScale
        }
        painter.onPointScaleChanged()
    }

    private fun itemCount(): Int = actionOrder.size + 1

    private fun actionAt(index: Int): Int =
        if (index == actionOrder.size) CONTROL_CANCEL else actionOrder[index].selectionAction

    private fun selectionDescription(): String {
        val description = StringBuilder("Screen selection. Drag an area, then choose ")
        for (index in actionOrder.indices) {
            if (index > 0) description.append(", ")
            description.append(actionOrder[index].menuTitle(context))
        }
        if (actionOrder.isNotEmpty()) description.append(", or Close") else description.append("Close")
        description.append(". The camera button captures the full screen.")
        return description.toString()
    }

    private fun topInset(): Float {
        val insets = rootWindowInsets ?: return 0f
        return insets.getInsets(WindowInsets.Type.systemBars()).top.toFloat()
    }

    private fun frozenQuarterTurns(): Int {
        if (frozenBitmap == null) return 0
        return (currentSurfaceRotation() - frozenRotation + 4) % 4
    }

    private fun currentSurfaceRotation(): Int = display?.rotation ?: surfaceRotation

    private companion object {
        const val GESTURE_NONE = 0
        const val GESTURE_NEW = 1
        const val GESTURE_MOVE = 2
        const val GESTURE_LEFT = 1 shl 2
        const val GESTURE_TOP = 1 shl 3
        const val GESTURE_RIGHT = 1 shl 4
        const val GESTURE_BOTTOM = 1 shl 5

        const val CONTROL_NONE = -1
        const val CONTROL_CANCEL = 100

        const val VIRTUAL_CAMERA_ID = 1
        const val VIRTUAL_CLOSE_ID = 2
        const val VIRTUAL_RAIL_BASE_ID = 1_000

        const val MINIMUM_SIZE_PX = 1f

        const val HANDLE_HIT_DP = 22f
        const val BORDER_HANDLE_REACH_DP = 48f
        const val END_INSET_DP = 4.4f
        const val BORDER_SNAP_DP = 16f
        const val BORDER_MAGNET_DP = 48f

        fun mixSignature(signature: Long, value: Int): Long = (signature xor value.toLong()) * 1_099_511_628_211L
    }
}
