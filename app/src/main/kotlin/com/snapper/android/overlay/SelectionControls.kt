package com.snapper.android.overlay

import android.graphics.RectF

internal class SelectionControls(private val density: Float) {
    val actionBar = RectF()
    val bottomBar = RectF()
    val screenshot = RectF()
    val cancel = RectF()

    var bottomControlsVisible = true
        private set

    var pointScale = 1f
        private set

    fun updatePointScale(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        val previousCellWidth = actionCellPixels()
        val shortEdge = Math.min(width, height)
        pointScale = shortEdge / REFERENCE_SHORT_EDGE_PT
        val currentCellWidth = actionCellPixels()
        return if (previousCellWidth > 0f && previousCellWidth != currentCellWidth) {
            currentCellWidth / previousCellWidth
        } else {
            1f
        }
    }

    fun pointPixels(points: Float): Float = points * pointScale

    fun actionCellPixels(): Float = Math.max(pointPixels(ACTION_CELL_PT), MIN_TOUCH_TARGET_DP * density)

    fun controlHeightPixels(): Float = Math.max(pointPixels(CONTROL_HEIGHT_PT), MIN_TOUCH_TARGET_DP * density)

    fun minimumTouchTarget(): Float = MIN_TOUCH_TARGET_DP * density

    fun controlBottomInset(): Float = pointPixels(CONTROL_BOTTOM_INSET_PT)

    fun controlRadius(): Float = pointPixels(CONTROL_RADIUS_PT)

    fun dividerInset(): Float = pointPixels(DIVIDER_INSET_PT)

    fun iconHalfSize(): Float = pointPixels(ICON_HALF_SIZE_PT)

    fun maximumActionScroll(itemCount: Int): Float {
        if (actionBar.isEmpty) return 0f
        return Math.max(0f, itemCount * actionCellPixels() - actionBar.width())
    }

    fun railCellLeft(index: Int, actionScrollX: Float): Float =
        actionBar.left - actionScrollX + index * actionCellPixels()

    fun railIndexAt(x: Float, y: Float, itemCount: Int, actionScrollX: Float): Int {
        if (!actionBar.contains(x, y)) return -1
        val contentX = x - actionBar.left + actionScrollX
        val index = (contentX / actionCellPixels()).toInt()
        return if (index in 0 until itemCount) index else -1
    }

    fun update(
        viewWidth: Int,
        viewHeight: Int,
        topInset: Float,
        selection: RectF,
        selectionReady: Boolean,
        instantMode: Boolean,
        itemCount: Int,
        actionScrollX: Float,
    ): Float {
        if (instantMode) {
            actionBar.setEmpty()
            bottomBar.setEmpty()
            screenshot.setEmpty()
            cancel.setEmpty()
            bottomControlsVisible = false
            return actionScrollX
        }

        val height = controlHeightPixels()
        val bottom = viewHeight - controlBottomInset()
        val bottomWidth = actionCellPixels() * 2f
        bottomBar.set((viewWidth - bottomWidth) / 2f, bottom - height, (viewWidth + bottomWidth) / 2f, bottom)
        screenshot.set(bottomBar.left, bottomBar.top, bottomBar.centerX(), bottomBar.bottom)
        cancel.set(bottomBar.centerX(), bottomBar.top, bottomBar.right, bottomBar.bottom)

        if (!selectionReady) {
            actionBar.setEmpty()
            updateBottomControlsVisibility(selection)
            return 0f
        }

        val margin = pointPixels(SCREEN_MARGIN_PT)
        val contentWidth = itemCount * actionCellPixels()
        val width = Math.min(
            contentWidth, Math.min(pointPixels(ACTION_RAIL_MAX_WIDTH_PT), viewWidth - margin * 2f),
        )
        val left = clampFloat(selection.centerX() - width / 2f, margin, viewWidth - margin - width)
        val gap = pointPixels(ACTION_GAP_PT)
        val belowTop = selection.bottom + gap
        val aboveTop = selection.top - gap - height
        val minimumTop = Math.max(
            topInset + margin, pointPixels(if (topInset > pointPixels(20f)) 40f else 20f),
        )
        val top = if (belowTop + height <= viewHeight) {
            belowTop
        } else if (aboveTop >= minimumTop) {
            aboveTop
        } else {
            clampFloat(aboveTop, minimumTop, viewHeight - height)
        }
        actionBar.set(left, top, left + width, top + height)
        val scroll = clampFloat(actionScrollX, 0f, maximumActionScroll(itemCount))
        updateBottomControlsVisibility(selection)
        return scroll
    }

    private fun updateBottomControlsVisibility(selection: RectF) {
        if (selection.isEmpty) {
            bottomControlsVisible = true
            return
        }
        val occupied = RectF(selection)
        if (!actionBar.isEmpty) occupied.union(actionBar)
        bottomControlsVisible = !RectF.intersects(bottomBar, occupied)
    }

    private companion object {
        const val REFERENCE_SHORT_EDGE_PT = 375f
        const val MIN_TOUCH_TARGET_DP = 48f
        const val ACTION_CELL_PT = 50f
        const val ACTION_RAIL_MAX_WIDTH_PT = 320f
        const val CONTROL_HEIGHT_PT = 46f
        const val CONTROL_RADIUS_PT = 8f
        const val ACTION_GAP_PT = 10f
        const val SCREEN_MARGIN_PT = 3f
        const val CONTROL_BOTTOM_INSET_PT = 27f
        const val ICON_HALF_SIZE_PT = 20f
        const val DIVIDER_INSET_PT = 4f
    }
}

internal fun clampFloat(value: Float, minimum: Float, maximum: Float): Float {
    if (maximum < minimum) return minimum
    return Math.max(minimum, Math.min(maximum, value))
}
