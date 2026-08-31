package com.snapper.android.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.WindowManager
import com.snapper.android.R
import com.snapper.android.actions.SnapperActionRegistry
import com.snapper.android.settings.SnapSettings
import com.snapper.android.types.SelectionAction
import java.io.File

internal class PinHolder(
    private val service: OverlayService,
    private val board: PinBoard,
    bitmap: Bitmap,
    file: File,
    val params: WindowManager.LayoutParams,
    desiredWidth: Int,
    desiredHeight: Int,
) : PinView.Listener {
    var desiredWidth = maxOf(1, desiredWidth)
    var desiredHeight = maxOf(1, desiredHeight)
    var removed = false
    var attached = false
    private var detached = false
    private var busy = false
    private var pendingRemoval: Runnable? = null
    val view = PinView(service, bitmap, file, this)

    private val stopping: Boolean
        get() = service.work.stopping

    private val actions: SnapActionRunner
        get() = service.actions

    fun reattachOnTop(): Boolean {
        service.windows.removeView(view)
        attached = false
        if (!service.attachOverlay(view, params)) {
            service.stopAfterOverlayFailure()
            return false
        }
        attached = true
        return true
    }

    override fun onBringToFront() {
        if (removed || stopping) {
            return
        }
        board.bringToFront(this)
    }

    override fun onMove(deltaX: Float, deltaY: Float) {
        if (removed || stopping) {
            return
        }
        val screen = service.windows.currentWindowMetrics.bounds
        val frameInset = view.frameInsetPx
        val visibleX = pinWindowVisibleEdge(service, params.width, frameInset)
        val visibleY = pinWindowVisibleEdge(service, params.height, frameInset)
        params.x = clamp(params.x + Math.round(deltaX), -params.width + visibleX, screen.width() - visibleX)
        params.y = clamp(params.y + Math.round(deltaY), -params.height + visibleY, screen.height() - visibleY)
        service.windows.updateViewLayout(view, params)
    }

    override fun onResize(scaleFactor: Float) {
        if (removed || stopping) {
            return
        }
        val screen = service.windows.currentWindowMetrics.bounds
        val centerX = params.x + params.width / 2f
        val centerY = params.y + params.height / 2f
        val inset = view.frameInsetPx
        val currentContentWidth = maxOf(1, params.width - inset * 2)
        val maximumContentWidth = maxOf(1, screen.width() - inset * 2)
        var contentWidth = clamp(Math.round(currentContentWidth * scaleFactor), 1, maximumContentWidth)
        var contentHeight = maxOf(
            1, Math.round(contentWidth * view.bitmap.height / view.bitmap.width.toFloat()),
        )
        val maximumContentHeight = maxOf(1, screen.height() - inset * 2)
        if (contentHeight > maximumContentHeight) {
            contentHeight = maximumContentHeight
            contentWidth = maxOf(
                1, Math.round(contentHeight * view.bitmap.width / view.bitmap.height.toFloat()),
            )
        }
        params.width = contentWidth + inset * 2
        params.height = contentHeight + inset * 2
        desiredWidth = params.width
        desiredHeight = params.height
        val visibleX = pinWindowVisibleEdge(service, params.width, inset)
        val visibleY = pinWindowVisibleEdge(service, params.height, inset)
        params.x = clamp(Math.round(centerX - params.width / 2f), -params.width + visibleX, screen.width() - visibleX)
        params.y = clamp(Math.round(centerY - params.height / 2f), -params.height + visibleY, screen.height() - visibleY)
        service.windows.updateViewLayout(view, params)
    }

    override fun onGeometrySettled() {
        if (removed || stopping) {
            return
        }
        board.checkpoint()
    }

    fun onDisplayGeometryChanged(previousBounds: Rect, currentBounds: Rect, quarterTurns: Int): Boolean {
        if (removed || previousBounds.width() <= 0 || previousBounds.height() <= 0 ||
            currentBounds.width() <= 0 || currentBounds.height() <= 0
        ) {
            return false
        }
        val centerXFraction = (params.x + params.width / 2f) / previousBounds.width()
        val centerYFraction = (params.y + params.height / 2f) / previousBounds.height()
        val (rotatedX, rotatedY) = rotateNormalizedCenter(centerXFraction, centerYFraction, quarterTurns)
        val fit = minOf(
            1f,
            currentBounds.width() / desiredWidth.toFloat(),
            currentBounds.height() / desiredHeight.toFloat(),
        )
        params.width = maxOf(1, Math.round(desiredWidth * fit))
        params.height = maxOf(1, Math.round(desiredHeight * fit))
        val frameInset = view.frameInsetPx
        val visibleX = pinWindowVisibleEdge(service, params.width, frameInset)
        val visibleY = pinWindowVisibleEdge(service, params.height, frameInset)
        params.x = clamp(
            Math.round(rotatedX * currentBounds.width() - params.width / 2f),
            -params.width + visibleX, currentBounds.width() - visibleX,
        )
        params.y = clamp(
            Math.round(rotatedY * currentBounds.height() - params.height / 2f),
            -params.height + visibleY, currentBounds.height() - visibleY,
        )
        service.windows.updateViewLayout(view, params)
        return true
    }

    override fun onClose() {
        if (removed || stopping) {
            return
        }
        removed = true
        board.close(this)
    }

    override fun onCopy() {
        if (!beginBusyAction(SelectionAction.COPY)) {
            return
        }
        actions.copy(view.file, R.string.feedback_pin_copied, ::finishBusyAction, ::failBusyAction)
    }

    override fun onSave() {
        if (!beginBusyAction(SelectionAction.SAVE)) {
            return
        }
        actions.save(view.file, ::finishBusyAction, ::failBusyAction)
    }

    override fun onShare() {
        if (removed || stopping || busy || view.isBusy) {
            return
        }
        actions.share(view.file)
        onClose()
    }

    override fun onOcr() {
        if (removed || detached || stopping || busy || view.isBusy) {
            return
        }
        actions.requestOcrScript({ script ->
            if (!beginBusyAction(SelectionAction.OCR)) {
                return@requestOcrScript
            }
            actions.scanOcr(view.file, script, true, ::finishBusyAction, ::failBusyAction)
        })
    }

    override fun onImgur() {
        if (!beginBusyAction(SelectionAction.IMGUR)) {
            return
        }
        actions.uploadImgur(view.file, ::finishBusyAction, ::failBusyAction)
    }

    override fun onUrlScheme() {
        if (!beginBusyAction(SelectionAction.URL_SCHEME)) {
            return
        }
        actions.runUrlScheme(view.file, ::finishBusyAction, ::failBusyAction)
    }

    override fun onScanQr() {
        if (!beginBusyAction(SelectionAction.QR)) {
            return
        }
        actions.scanQr(view.file, false, ::finishBusyAction, ::failBusyAction)
    }

    override fun onExternalAction(selectionAction: Int) {
        val provider = SnapperActionRegistry.actionForSelectionCode(selectionAction)
        if (provider == null || !provider.isExternal || !beginBusyAction(selectionAction)) {
            return
        }
        actions.launchExternal(
            view.file,
            provider,
            SnapperActionRegistry.ExternalSource.PIN,
            if (provider.removeSnapAfterProcessing) ::finishBusyAction else ::finishBusyActionKeepingPin,
            ::failBusyAction,
        )
    }

    private fun beginBusyAction(selectionAction: Int): Boolean {
        if (removed || detached || stopping || busy || view.isBusy) {
            return false
        }
        if (!view.beginBusy(selectionAction)) {
            return false
        }
        busy = true
        return true
    }

    private fun finishBusyAction() {
        if (!busy) {
            return
        }
        busy = false
        if (!removed && !detached && !stopping) {
            onClose()
        }
    }

    private fun finishBusyActionKeepingPin() {
        if (!busy) {
            return
        }
        busy = false
        if (!removed && !detached && !stopping) {
            view.endBusy(false)
        }
    }

    private fun failBusyAction() {
        if (!busy) {
            return
        }
        busy = false
        if (!removed && !detached && !stopping) {
            view.endBusy(true)
        }
    }

    fun remove() {
        removed = true
        detachAndRecycle()
    }

    fun discardUnattached() {
        removed = true
        detached = true
        view.recycleBitmapAfterDetach()
    }

    fun animateRemovalAfterDelay(afterRemoval: () -> Unit) {
        board.retiring.add(this)
        val removal = Runnable {
            pendingRemoval = null
            animateRemoval(afterRemoval)
        }
        pendingRemoval = removal
        service.main.postDelayed(removal, CLOSE_ALL_REMOVAL_DELAY_MS)
    }

    fun animateRemoval(afterRemoval: () -> Unit) {
        if (detached) {
            board.retiring.remove(this)
            afterRemoval()
            return
        }
        if (SnapSettings.reduceAnimations(service) || !view.isAttachedToWindow) {
            board.retiring.remove(this)
            detachAndRecycle()
            afterRemoval()
            return
        }
        if (this !in board.retiring) {
            board.retiring.add(this)
        }
        view.animate().cancel()
        view.animate()
            .scaleX(0.001f)
            .scaleY(0.001f)
            .alpha(0f)
            .setDuration(PIN_REMOVAL_MS)
            .withEndAction {
                board.retiring.remove(this)
                detachAndRecycle()
                afterRemoval()
            }
            .start()
    }

    private fun detachAndRecycle() {
        if (detached) {
            return
        }
        detached = true
        pendingRemoval?.let { service.main.removeCallbacks(it) }
        pendingRemoval = null
        board.retiring.remove(this)
        view.animate().cancel()
        view.recycleBitmapAfterDetach()
        if (attached) {
            attached = false
            service.windows.removeView(view)
        }
    }
}

private const val PIN_REMOVAL_MS = 200L
private const val CLOSE_ALL_REMOVAL_DELAY_MS = 100L
