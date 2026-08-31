package com.snapper.android.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.snapper.android.actions.SnapperActionRegistry

internal class SelectionPainter(
    private val controls: SelectionControls,
    private val icons: Array<Bitmap>,
) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val cropShadePaint = Paint()
    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val bitmapSource = Rect()
    private val bitmapTarget = RectF()
    private val drawingBounds = RectF()

    init {
        dimPaint.color = Color.argb(128, 0, 0, 0)
        cropShadePaint.color = Color.argb(26, 0, 0, 0)
        guidelinePaint.color = Color.argb(38, 255, 255, 255)
        cornerPaint.color = Color.WHITE
        cornerPaint.style = Paint.Style.FILL
        dividerPaint.color = Color.argb(51, 255, 255, 255)
        pressedPaint.color = Color.argb(38, 255, 255, 255)
        onPointScaleChanged()
    }

    fun applyControlTint(tint: Int) {
        if ((tint and 0x00FF_FFFF) == 0) {
            panelBasePaint.color = Color.argb(DEFAULT_BLACK_MATERIAL_BASE_ALPHA, 255, 255, 255)
        } else {
            val luminance = (299 * Color.red(tint) + 587 * Color.green(tint) + 114 * Color.blue(tint)) / 1_000
            val baseAlpha = Math.max(0, Math.min(224, (luminance - 80) * 224 / 175))
            panelBasePaint.color = Color.argb(baseAlpha, 28, 30, 34)
        }
        panelPaint.color = Color.argb(CONTROL_TINT_ALPHA, Color.red(tint), Color.green(tint), Color.blue(tint))
    }

    fun onPointScaleChanged() {
        guidelinePaint.strokeWidth = controls.pointPixels(1f)
        dividerPaint.strokeWidth = controls.pointPixels(0.5f)
    }

    fun drawFrozen(canvas: Canvas, frozen: Bitmap, quarterTurns: Int, width: Int, height: Int) {
        bitmapSource.set(0, 0, frozen.width, frozen.height)
        if (quarterTurns == 0) {
            bitmapTarget.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(frozen, bitmapSource, bitmapTarget, bitmapPaint)
            return
        }
        val restore = canvas.save()
        val centerX = width / 2f
        val centerY = height / 2f
        if (quarterTurns == 2) {
            canvas.rotate(180f, centerX, centerY)
            bitmapTarget.set(0f, 0f, width.toFloat(), height.toFloat())
        } else {
            canvas.rotate(if (quarterTurns == 1) 90f else -90f, centerX, centerY)
            bitmapTarget.set(
                centerX - height / 2f, centerY - width / 2f,
                centerX + height / 2f, centerY + width / 2f,
            )
        }
        canvas.drawBitmap(frozen, bitmapSource, bitmapTarget, bitmapPaint)
        canvas.restoreToCount(restore)
    }

    fun drawDimmed(canvas: Canvas, selection: RectF, width: Int, height: Int) {
        if (selection.isEmpty) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            return
        }
        canvas.drawRect(0f, 0f, width.toFloat(), selection.top, dimPaint)
        canvas.drawRect(0f, selection.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, selection.top, selection.left, selection.bottom, dimPaint)
        canvas.drawRect(selection.right, selection.top, width.toFloat(), selection.bottom, dimPaint)
    }

    fun drawCropGuides(canvas: Canvas, selection: RectF) {
        if (selection.isEmpty) return
        canvas.drawRect(selection, cropShadePaint)
        val thirdWidth = selection.width() / 3f
        val thirdHeight = selection.height() / 3f
        canvas.drawLine(
            selection.left + thirdWidth, selection.top,
            selection.left + thirdWidth, selection.bottom, guidelinePaint,
        )
        canvas.drawLine(
            selection.left + thirdWidth * 2f, selection.top,
            selection.left + thirdWidth * 2f, selection.bottom, guidelinePaint,
        )
        canvas.drawLine(
            selection.left, selection.top + thirdHeight,
            selection.right, selection.top + thirdHeight, guidelinePaint,
        )
        canvas.drawLine(
            selection.left, selection.top + thirdHeight * 2f,
            selection.right, selection.top + thirdHeight * 2f, guidelinePaint,
        )
    }

    fun drawCropFrame(canvas: Canvas, selection: RectF) {
        val cornerWidth = clampFloat(
            selection.width(), controls.pointPixels(CORNER_MIN_SIZE_PT), controls.pointPixels(CORNER_MAX_SIZE_PT),
        )
        val cornerHeight = clampFloat(
            selection.height(), controls.pointPixels(CORNER_MIN_SIZE_PT), controls.pointPixels(CORNER_MAX_SIZE_PT),
        )
        val outside = controls.pointPixels(CORNER_OUTSIDE_PT)
        val radius = controls.pointPixels(CORNER_RADIUS_PT)
        val restore = canvas.save()
        canvas.clipOutRect(selection)
        canvas.drawRoundRect(
            selection.left - outside, selection.top - outside,
            selection.left - outside + cornerWidth, selection.top - outside + cornerHeight,
            radius, radius, cornerPaint,
        )
        canvas.drawRoundRect(
            selection.right - cornerWidth + outside, selection.top - outside,
            selection.right + outside, selection.top - outside + cornerHeight,
            radius, radius, cornerPaint,
        )
        canvas.drawRoundRect(
            selection.left - outside, selection.bottom - cornerHeight + outside,
            selection.left - outside + cornerWidth, selection.bottom + outside,
            radius, radius, cornerPaint,
        )
        canvas.drawRoundRect(
            selection.right - cornerWidth + outside, selection.bottom - cornerHeight + outside,
            selection.right + outside, selection.bottom + outside,
            radius, radius, cornerPaint,
        )
        canvas.restoreToCount(restore)
    }

    fun drawActionRail(canvas: Canvas, railIcons: IntArray, actionScrollX: Float, pressedIndex: Int) {
        val bounds = controls.actionBar
        val radius = controls.controlRadius()
        canvas.drawRoundRect(bounds, radius, radius, panelBasePaint)
        canvas.drawRoundRect(bounds, radius, radius, panelPaint)
        clipPath.reset()
        clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        val restore = canvas.save()
        canvas.clipPath(clipPath)
        val cellWidth = controls.actionCellPixels()
        val dividerInset = controls.dividerInset()
        for (index in railIcons.indices) {
            val left = controls.railCellLeft(index, actionScrollX)
            val right = left + cellWidth
            if (right <= bounds.left || left >= bounds.right) continue
            if (index == pressedIndex) {
                canvas.drawRect(left, bounds.top, right, bounds.bottom, pressedPaint)
            }
            if (index > 0) {
                canvas.drawLine(left, bounds.top + dividerInset, left, bounds.bottom - dividerInset, dividerPaint)
            }
            drawIcon(canvas, railIcons[index], left + cellWidth / 2f, bounds.centerY())
        }
        canvas.restoreToCount(restore)
    }

    fun drawBottomControls(canvas: Canvas, screenshotPressed: Boolean, cancelPressed: Boolean) {
        val bounds = controls.bottomBar
        val radius = controls.controlRadius()
        canvas.drawRoundRect(bounds, radius, radius, panelBasePaint)
        canvas.drawRoundRect(bounds, radius, radius, panelPaint)
        clipPath.reset()
        clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        val restore = canvas.save()
        canvas.clipPath(clipPath)
        if (screenshotPressed) canvas.drawRect(controls.screenshot, pressedPaint)
        val dividerInset = controls.dividerInset()
        canvas.drawLine(
            bounds.centerX(), bounds.top + dividerInset,
            bounds.centerX(), bounds.bottom - dividerInset, dividerPaint,
        )
        drawIcon(
            canvas, SnapperActionRegistry.SCREENSHOT_ICON,
            controls.screenshot.centerX(), controls.screenshot.centerY(),
        )
        if (cancelPressed) canvas.drawRect(controls.cancel, pressedPaint)
        drawIcon(canvas, SnapperActionRegistry.CLOSE_ICON, controls.cancel.centerX(), controls.cancel.centerY())
        canvas.restoreToCount(restore)
    }

    private fun drawIcon(canvas: Canvas, icon: Int, centerX: Float, centerY: Float) {
        if (icon < 0 || icon >= icons.size) return
        val bitmap = icons[icon]
        if (bitmap.isRecycled) return
        val half = controls.iconHalfSize()
        drawingBounds.set(centerX - half, centerY - half, centerX + half, centerY + half)
        bitmapSource.set(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, bitmapSource, drawingBounds, bitmapPaint)
    }

    private companion object {
        const val CORNER_MIN_SIZE_PT = 7f
        const val CORNER_MAX_SIZE_PT = 16f
        const val CORNER_OUTSIDE_PT = 3f
        const val CORNER_RADIUS_PT = 2f
        const val CONTROL_TINT_ALPHA = 128
        const val DEFAULT_BLACK_MATERIAL_BASE_ALPHA = 36
    }
}
