package com.snapper.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF

private const val PIN_VISIBLE_EDGE_DP = 20f

internal fun clamp(value: Int, minimum: Int, maximum: Int): Int = maxOf(minimum, minOf(maximum, value))

internal fun pinWindowVisibleEdge(context: Context, windowExtent: Int, frameInset: Int): Int {
    val contentExtent = maxOf(0, windowExtent - frameInset * 2)
    val visibleContent = minOf(
        contentExtent, Math.round(PIN_VISIBLE_EDGE_DP * context.resources.displayMetrics.density),
    )
    return frameInset + visibleContent
}

internal fun rotateNormalizedCenter(centerX: Float, centerY: Float, quarterTurns: Int): Pair<Float, Float> =
    when (quarterTurns) {
        1 -> 1f - centerY to centerX
        2 -> 1f - centerX to 1f - centerY
        3 -> centerY to 1f - centerX
        else -> centerX to centerY
    }

internal fun rotateBitmap(source: Bitmap, quarterTurns: Int): Bitmap {
    val normalizedTurns = (quarterTurns % 4 + 4) % 4
    if (normalizedTurns == 0) return source
    val matrix = Matrix()
    matrix.postRotate(normalizedTurns * 90f)
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

internal fun mapRectToSource(rect: RectF, surfaceWidth: Int, surfaceHeight: Int, quarterTurns: Int): RectF {
    val left = rect.left / maxOf(1, surfaceWidth)
    val top = rect.top / maxOf(1, surfaceHeight)
    val right = rect.right / maxOf(1, surfaceWidth)
    val bottom = rect.bottom / maxOf(1, surfaceHeight)
    return when ((quarterTurns % 4 + 4) % 4) {
        1 -> RectF(top, 1f - right, bottom, 1f - left)
        2 -> RectF(1f - right, 1f - bottom, 1f - left, 1f - top)
        3 -> RectF(1f - bottom, left, 1f - top, right)
        else -> RectF(left, top, right, bottom)
    }
}

internal fun recycle(bitmap: Bitmap?) {
    if (bitmap != null && !bitmap.isRecycled) {
        bitmap.recycle()
    }
}
