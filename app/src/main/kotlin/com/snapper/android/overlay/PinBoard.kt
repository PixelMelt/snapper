package com.snapper.android.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Surface
import android.view.View
import android.view.WindowManager
import com.snapper.android.settings.SnapSettings
import com.snapper.android.storage.SnapRepository
import java.io.File

internal class PinBoard(private val service: OverlayService) {
    private val work get() = service.work
    private val repository get() = service.repository
    private val pins = mutableListOf<PinHolder>()
    val retiring = mutableListOf<PinHolder>()
    private var restorationSession: SnapRepository.LivePinSession?
    private var stateRevision: Long
    private var restorationStarted = false
    private var restorationComplete = false
    private var pendingUserPinsVisible: Boolean? = null

    var userPinsVisible: Boolean
        private set

    var restorationInFlight = false
        private set

    init {
        val session = repository.loadLivePins()
        restorationSession = session
        repository.publishLivePins(session)
        stateRevision = session.revision
        userPinsVisible = session.userPinsVisible
    }

    val count: Int
        get() = pins.size

    val hasWindows: Boolean
        get() = pins.isNotEmpty() || retiring.isNotEmpty()

    fun addPin(bitmap: Bitmap, file: File) {
        val bounds = service.windows.currentWindowMetrics.bounds
        addPin(
            bitmap, file, bitmap.width.toFloat(), bitmap.height.toFloat(),
            bounds.exactCenterX(), bounds.exactCenterY(),
        )
    }

    fun addCapturedPin(
        bitmap: Bitmap,
        file: File,
        logicalWidth: Float,
        logicalHeight: Float,
        centerX: Float,
        centerY: Float,
        actionSurfaceWidth: Int,
        actionSurfaceHeight: Int,
        actionSurfaceRotation: Int,
    ) {
        val bounds = service.windows.currentWindowMetrics.bounds
        val centerXFraction = centerX / maxOf(1, actionSurfaceWidth)
        val centerYFraction = centerY / maxOf(1, actionSurfaceHeight)
        val quarterTurns = (service.displayRotation() - actionSurfaceRotation + 4) % 4
        val (rotatedX, rotatedY) = rotateNormalizedCenter(centerXFraction, centerYFraction, quarterTurns)
        addPin(
            bitmap, file, logicalWidth, logicalHeight,
            rotatedX * bounds.width(), rotatedY * bounds.height(),
        )
    }

    private fun addPin(
        bitmap: Bitmap,
        file: File,
        logicalWidth: Float,
        logicalHeight: Float,
        centerX: Float,
        centerY: Float,
    ) {
        val bounds = service.windows.currentWindowMetrics.bounds
        val inset = PinView.frameInset(service)
        val availableWidth = maxOf(1, bounds.width() - inset * 2)
        val availableHeight = maxOf(1, bounds.height() - inset * 2)
        val scale = minOf(
            1f,
            availableWidth / maxOf(1f, logicalWidth),
            availableHeight / maxOf(1f, logicalHeight),
        )
        val contentWidth = maxOf(1, Math.round(logicalWidth * scale))
        val contentHeight = maxOf(1, Math.round(logicalHeight * scale))
        val width = contentWidth + inset * 2
        val height = contentHeight + inset * 2
        val desiredWidth = maxOf(1, Math.round(logicalWidth)) + inset * 2
        val desiredHeight = maxOf(1, Math.round(logicalHeight)) + inset * 2
        val params = service.overlayParams(width, height)
        params.x = Math.round(centerX - width / 2f)
        params.y = Math.round(centerY - height / 2f)
        attachPin(bitmap, file, params, desiredWidth, desiredHeight, true)
    }

    private fun addRestoredPin(bitmap: Bitmap, file: File, state: SnapRepository.LivePinState): Boolean {
        val bounds = service.windows.currentWindowMetrics.bounds
        val desiredWidth = maxOf(1, state.width)
        val desiredHeight = maxOf(1, state.height)
        val fit = minOf(
            1f,
            bounds.width() / desiredWidth.toFloat(),
            bounds.height() / desiredHeight.toFloat(),
        )
        val width = maxOf(1, Math.round(desiredWidth * fit))
        val height = maxOf(1, Math.round(desiredHeight * fit))
        val params = service.overlayParams(width, height)
        val (centerXFraction, centerYFraction) = if (
            state.displayRotation >= Surface.ROTATION_0 &&
            state.displayRotation <= Surface.ROTATION_270
        ) {
            val quarterTurns = (service.displayRotation() - state.displayRotation + 4) % 4
            rotateNormalizedCenter(state.centerXFraction, state.centerYFraction, quarterTurns)
        } else {
            state.centerXFraction to state.centerYFraction
        }
        val centerX = centerXFraction * bounds.width()
        val centerY = centerYFraction * bounds.height()
        val frameInset = PinView.frameInset(service)
        val visibleX = pinWindowVisibleEdge(service, width, frameInset)
        val visibleY = pinWindowVisibleEdge(service, height, frameInset)
        params.x = clamp(Math.round(centerX - width / 2f), -width + visibleX, bounds.width() - visibleX)
        params.y = clamp(Math.round(centerY - height / 2f), -height + visibleY, bounds.height() - visibleY)
        return attachPin(bitmap, file, params, desiredWidth, desiredHeight, false)
    }

    private fun attachPin(
        bitmap: Bitmap,
        file: File,
        params: WindowManager.LayoutParams,
        desiredWidth: Int,
        desiredHeight: Int,
        checkpoint: Boolean,
    ): Boolean {
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val holder = PinHolder(service, this, bitmap, file, params, desiredWidth, desiredHeight)
        holder.view.visibility = if (userPinsVisible && !service.capture.captureInProgress() &&
            !service.capture.selectionShowing
        ) View.VISIBLE else View.INVISIBLE
        val animatePresentation = checkpoint && !SnapSettings.reduceAnimations(service)
        if (animatePresentation) {
            holder.view.scaleX = 0.05f
            holder.view.scaleY = 0.05f
        }
        if (!service.attachOverlay(holder.view, params)) {
            holder.discardUnattached()
            service.stopAfterOverlayFailure()
            return false
        }
        holder.attached = true
        repository.protectLivePin(file.name)
        pins.add(holder)
        if (animatePresentation) {
            holder.view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(PIN_PRESENTATION_MS)
                .start()
        }
        service.refreshNotification()
        if (checkpoint) {
            checkpoint()
        }
        return true
    }

    fun closeAll() {
        work.cancelDelayedActions()
        service.actions.dismissOcrChooser()
        service.capture.removeSelection(true)
        val closing = ArrayList(pins)
        pins.clear()
        clearLivePinsAndPrune()
        service.refreshNotification()
        if (closing.isEmpty()) {
            service.stopIfIdle()
            return
        }
        var remaining = closing.size
        val finished = {
            remaining--
            if (remaining == 0) service.stopIfIdle()
        }
        for (pin in closing) {
            pin.removed = true
            pin.animateRemovalAfterDelay(finished)
        }
    }

    fun closeAllImmediately() {
        service.actions.dismissOcrChooser()
        service.capture.removeSelection(true)
        for (pin in ArrayList(pins)) {
            pin.remove()
        }
        pins.clear()
        clearLivePinsAndPrune()
        service.refreshNotification()
    }

    fun destroy() {
        for (pin in ArrayList(pins)) {
            pin.remove()
        }
        pins.clear()
        for (pin in ArrayList(retiring)) {
            pin.remove()
        }
        retiring.clear()
    }

    fun setVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        for (pin in pins) {
            pin.view.visibility = visibility
        }
        for (pin in retiring) {
            pin.view.visibility = visibility
        }
    }

    fun requestUserPinsVisible(visible: Boolean) {
        if (!restorationComplete) {
            pendingUserPinsVisible = visible
            restoreIfNeeded()
            return
        }
        setUserPinsVisible(visible)
    }

    private fun setUserPinsVisible(visible: Boolean) {
        if (pins.isEmpty()) {
            service.toast("No pins are open")
            service.stopIfIdle()
            return
        }
        userPinsVisible = visible
        setVisible(visible && !service.capture.captureInProgress())
        service.toast(if (visible) "All pins shown" else "All pins hidden")
        service.refreshNotification()
        checkpoint()
    }

    fun bringToFront(holder: PinHolder) {
        if (pins.isEmpty() || pins.last() === holder) {
            return
        }
        if (!holder.reattachOnTop()) {
            return
        }
        pins.remove(holder)
        pins.add(holder)
        checkpoint()
    }

    fun close(holder: PinHolder) {
        pins.remove(holder)
        if (!restorationInFlight) {
            checkpoint()
        }
        service.refreshNotification()
        holder.animateRemoval { service.stopIfIdle() }
    }

    fun restoreIfNeeded() {
        if (restorationStarted || work.stopping) {
            return
        }
        restorationStarted = true
        restorationInFlight = true
        val session = checkNotNull(restorationSession)
        restorationSession = null
        if (session.pins.isEmpty()) {
            finishRestoration(session, emptyList())
            return
        }
        val generation = work.generation
        work.execute {
            val restored = ArrayList<RestoredPin>()
            for (state in session.pins) {
                if (!work.isCurrent(generation)) {
                    recycleRestored(restored)
                    return@execute
                }
                val file = repository.resolveProviderPath(state.basename) ?: continue
                val bitmap = repository.decode(file) ?: continue
                restored.add(RestoredPin(bitmap, file, state))
            }
            work.postToMain(generation, { finishRestoration(session, restored) }, { recycleRestored(restored) })
        }
    }

    private fun finishRestoration(session: SnapRepository.LivePinSession, restored: List<RestoredPin>) {
        userPinsVisible = session.userPinsVisible
        val pinsOpenedByCurrentCommand = ArrayList(pins)
        for ((index, item) in restored.withIndex()) {
            if (!addRestoredPin(item.bitmap, item.file, item.state)) {
                for (pending in index + 1 until restored.size) {
                    recycle(restored[pending].bitmap)
                }
                restorationInFlight = false
                restorationComplete = true
                return
            }
        }
        for (pin in pinsOpenedByCurrentCommand) {
            if (!pin.reattachOnTop()) {
                restorationInFlight = false
                restorationComplete = true
                return
            }
            pins.remove(pin)
            pins.add(pin)
        }
        restorationInFlight = false
        restorationComplete = true
        checkpoint()
        val requested = pendingUserPinsVisible
        pendingUserPinsVisible = null
        if (requested != null) {
            setUserPinsVisible(requested)
        }
        service.refreshNotification()
        service.stopIfIdle()
    }

    fun checkpoint() {
        if (work.stopping || restorationInFlight) {
            return
        }
        val revision = repository.nextLivePinRevision(stateRevision)
        stateRevision = revision
        val snapshot = snapshot(revision)
        repository.publishLivePins(snapshot)
        work.execute {
            if (!repository.writeLivePins(snapshot)) {
                service.main.post { service.toast("Could not save open pin state") }
            }
        }
    }

    private fun snapshot(revision: Long): SnapRepository.LivePinSession {
        val bounds = service.windows.currentWindowMetrics.bounds
        val displayWidth = maxOf(1, bounds.width())
        val displayHeight = maxOf(1, bounds.height())
        val rotation = service.displayRotation()
        val states = pins.map { pin ->
            val params = pin.params
            SnapRepository.LivePinState(
                pin.view.file.name,
                (params.x + params.width / 2f) / displayWidth,
                (params.y + params.height / 2f) / displayHeight,
                pin.desiredWidth, pin.desiredHeight,
                displayWidth, displayHeight, rotation,
            )
        }
        return SnapRepository.LivePinSession(revision, userPinsVisible, states)
    }

    private fun clearLivePinsAndPrune() {
        val revision = repository.nextLivePinRevision(stateRevision)
        stateRevision = revision
        if (!repository.clearLivePins(revision)) {
            service.toast("Could not save open pin state")
        }
        work.execute { repository.prune() }
    }

    fun onDisplayGeometryChanged(previousBounds: Rect, currentBounds: Rect, quarterTurns: Int) {
        var changed = false
        for (pin in pins) {
            if (pin.onDisplayGeometryChanged(previousBounds, currentBounds, quarterTurns)) {
                changed = true
            }
        }
        if (changed && !restorationInFlight) {
            checkpoint()
        }
    }

    private class RestoredPin(val bitmap: Bitmap, val file: File, val state: SnapRepository.LivePinState)

    private fun recycleRestored(restored: List<RestoredPin>) {
        for (item in restored) {
            recycle(item.bitmap)
        }
    }
}

private const val PIN_PRESENTATION_MS = 500L
