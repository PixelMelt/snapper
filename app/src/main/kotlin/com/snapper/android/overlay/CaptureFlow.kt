package com.snapper.android.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.WindowManager
import com.snapper.android.actions.SnapperActionRegistry
import com.snapper.android.capture.RootCapture
import com.snapper.android.settings.SnapSettings
import com.snapper.android.types.OcrScript
import com.snapper.android.types.SelectionAction
import com.snapper.android.types.SnapSourceMetadata
import com.snapper.android.types.SnapperIpcContract
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface SelectionCaptureAction {
    val code: Int

    data class Standard(override val code: Int) : SelectionCaptureAction {
        init {
            require(code != SelectionAction.OCR && code != SelectionAction.SCREENSHOT)
        }
    }

    data class Ocr(val script: OcrScript) : SelectionCaptureAction {
        override val code = SelectionAction.OCR
    }
}

internal data class CaptureAttribution(
    val sourceRequested: Boolean,
    val metadata: SnapSourceMetadata,
) {
    fun completedWith(detected: SnapSourceMetadata?): CaptureAttribution {
        if (!sourceRequested) {
            check(detected == null)
            return this
        }
        return copy(metadata = checkNotNull(detected))
    }
}

internal class CaptureFlow(private val service: OverlayService) {
    private val work get() = service.work
    private val main get() = service.main
    private val captureBusy = AtomicBoolean()
    private val fullScreenCaptureBusy = AtomicBoolean()

    @Volatile
    private var rootCaptureGeneration = 1
    private var pendingCaptureStart: Runnable? = null
    private var pendingCaptureDeadline: Runnable? = null
    private var selectionView: SelectionView? = null
    private var selectionBitmap: Bitmap? = null
    private val stateHeartbeat = object : Runnable {
        override fun run() {
            if (work.stopping || selectionView == null) {
                return
            }
            SnapActions.reportSelectionState(service, true)
            main.postDelayed(this, STATE_HEARTBEAT_MS)
        }
    }

    val selectionShowing: Boolean
        get() = selectionView != null

    fun captureInProgress(): Boolean = captureBusy.get() || fullScreenCaptureBusy.get()

    fun rootCaptureSetupReady(): Boolean {
        if (SnapSettings.rootVerified(service)) {
            return true
        }
        service.toast("Open Snapper and check Root access before capturing")
        return false
    }

    fun beginNormal() {
        if (captureInProgress()) {
            service.toast("A capture is already in progress")
            return
        }
        service.feedbackOverlay.hideNow()
        service.actions.dismissOcrChooser()
        showSelection(null, false, startCaptureAttribution(), service.displayRotation())
    }

    fun beginFreeze() {
        if (captureInProgress()) {
            service.toast("A capture is already in progress")
            return
        }
        service.actions.dismissOcrChooser()
        removeSelection(true)
        val attribution = startCaptureAttribution()
        runRootCapture(attribution, FREEZE_READY_DEADLINE_MS) { result ->
            try {
                when (result) {
                    is RootCapture.Result.Success -> showSelection(
                        result.bitmap, false,
                        attribution.completedWith(result.sourceMetadata), result.rotation,
                    )
                    is RootCapture.Result.Failure -> service.toast(result.message)
                }
            } finally {
                service.pins.restoreIfNeeded()
                if (result is RootCapture.Result.Failure) {
                    service.stopIfIdle()
                }
            }
        }
    }

    fun beginInstant() {
        if (captureInProgress()) {
            service.toast("A capture is already in progress")
            return
        }
        service.feedbackOverlay.hideNow()
        service.actions.dismissOcrChooser()
        showSelection(null, true, startCaptureAttribution(), service.displayRotation())
    }

    fun prepareNativeScreenshot(intent: Intent) {
        val generation = intent.getLongExtra(
            SnapperIpcContract.EXTRA_NATIVE_SCREENSHOT_GENERATION, -1L,
        )
        cancelRootCapture()
        service.feedbackOverlay.hideNow()
        service.actions.dismissOcrChooser()
        removeSelection(true)
        if (generation >= 0L) {
            SnapActions.reportNativeScreenshotReady(service, generation)
        }
        service.stopIfIdle()
    }

    fun dismissSelectionForSystemNavigation() {
        if (selectionView == null && !service.actions.ocrChooserShowing) {
            return
        }
        service.actions.dismissOcrChooser()
        removeSelection(true)
        service.stopIfIdle()
    }

    fun onDisplayRotationChanged(currentRotation: Int) {
        selectionView?.onDisplayRotationChanged(currentRotation)
    }

    fun cancelForStop() {
        cancelCaptureDeadline()
        cancelPendingCaptureStart()
        captureBusy.set(false)
        fullScreenCaptureBusy.set(false)
        main.removeCallbacks(stateHeartbeat)
    }

    fun destroy() {
        cancelCaptureDeadline()
        cancelPendingCaptureStart()
        main.removeCallbacks(stateHeartbeat)
        removeSelection(true)
        fullScreenCaptureBusy.set(false)
    }

    fun removeSelection(recycleBitmap: Boolean) {
        val view = selectionView ?: return
        service.windows.removeViewImmediate(view)
        selectionView = null
        if (recycleBitmap) {
            recycle(selectionBitmap)
        }
        selectionBitmap = null
        refreshBridgeState()
        if (!captureInProgress() && !work.stopping) {
            service.pins.setVisible(service.pins.userPinsVisible)
        }
    }

    private fun cancelRootCapture() {
        rootCaptureGeneration++
        cancelCaptureDeadline()
        cancelPendingCaptureStart()
        if (captureBusy.getAndSet(false) && !work.stopping) {
            service.pins.setVisible(service.pins.userPinsVisible && selectionView == null)
            refreshBridgeState()
        }
    }

    private fun cancelPendingCaptureStart() {
        pendingCaptureStart?.let { main.removeCallbacks(it) }
        pendingCaptureStart = null
    }

    private fun cancelCaptureDeadline() {
        pendingCaptureDeadline?.let { main.removeCallbacks(it) }
        pendingCaptureDeadline = null
    }

    private fun startCaptureAttribution(): CaptureAttribution = CaptureAttribution(
        !SnapSettings.anonymousAppNameEnabled(service),
        SnapSourceMetadata.fallback(System.currentTimeMillis()),
    )

    private fun showSelection(
        frozen: Bitmap?,
        instant: Boolean,
        attribution: CaptureAttribution,
        capturedRotation: Int,
    ) {
        service.actions.dismissOcrChooser()
        removeSelection(true)
        val initial = if (SnapSettings.keepSelection(service)) {
            rememberedRect(service.displayRotation())
        } else {
            null
        }
        val view = SelectionView(
            service, frozen, instant, initial, capturedRotation,
            object : SelectionView.Listener {
                override fun onAction(
                    rect: RectF,
                    surfaceWidth: Int,
                    surfaceHeight: Int,
                    action: Int,
                    frozenQuarterTurns: Int,
                    actionSurfaceRotation: Int,
                ) {
                    if (action == SelectionAction.SCREENSHOT) {
                        takeFullScreenScreenshot()
                        return
                    }
                    val owner = selectionView
                    val actionRect = RectF(rect)
                    if (action == SelectionAction.OCR) {
                        service.actions.requestOcrScript({ script ->
                            continueSelectionAction(
                                owner, actionRect, surfaceWidth, surfaceHeight,
                                SelectionCaptureAction.Ocr(script), frozenQuarterTurns,
                                actionSurfaceRotation, attribution,
                            )
                        })
                    } else {
                        continueSelectionAction(
                            owner, actionRect, surfaceWidth, surfaceHeight,
                            SelectionCaptureAction.Standard(action), frozenQuarterTurns,
                            actionSurfaceRotation, attribution,
                        )
                    }
                }

                override fun onFeedback(message: String) {
                    service.toast(message)
                }

                override fun onCancelled() {
                    removeSelection(true)
                    service.stopIfIdle()
                }
            },
        )
        val params = service.overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        view.isFocusableInTouchMode = true
        if (!service.attachOverlay(view, params)) {
            recycle(frozen)
            service.stopAfterOverlayFailure()
            return
        }
        selectionView = view
        selectionBitmap = frozen
        view.requestFocus()
        refreshBridgeState()
        if (!SnapSettings.reduceAnimations(service)) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(140).start()
        }
    }

    private fun continueSelectionAction(
        owner: SelectionView?,
        rect: RectF,
        surfaceWidth: Int,
        surfaceHeight: Int,
        action: SelectionCaptureAction,
        frozenQuarterTurns: Int,
        actionSurfaceRotation: Int,
        attribution: CaptureAttribution,
    ) {
        if (selectionView !== owner || owner == null || work.stopping) {
            return
        }
        if (action is SelectionCaptureAction.Ocr && (
                owner.width != surfaceWidth || owner.height != surfaceHeight ||
                    service.displayRotation() != actionSurfaceRotation
                )
        ) {
            service.toast("Screen changed; choose OCR again")
            return
        }
        rememberRect(rect, surfaceWidth, surfaceHeight, actionSurfaceRotation)
        val frozenSource = selectionBitmap
        removeSelection(false)
        if (frozenSource != null) {
            cropSaveAndPerform(
                frozenSource, rect, surfaceWidth, surfaceHeight,
                action, frozenQuarterTurns, actionSurfaceRotation, attribution,
            )
        } else {
            runRootCapture(attribution) { result ->
                when (result) {
                    is RootCapture.Result.Success -> {
                        val captureQuarterTurns = (actionSurfaceRotation - result.rotation + 4) % 4
                        cropSaveAndPerform(
                            result.bitmap, rect, surfaceWidth, surfaceHeight,
                            action, captureQuarterTurns, actionSurfaceRotation,
                            attribution.completedWith(result.sourceMetadata),
                        )
                    }
                    is RootCapture.Result.Failure -> {
                        service.toast(result.message)
                        service.stopIfIdle()
                    }
                }
            }
        }
    }

    private fun takeFullScreenScreenshot() {
        if (!rootCaptureSetupReady()) {
            return
        }
        if (captureBusy.get() || !fullScreenCaptureBusy.compareAndSet(false, true)) {
            service.toast("A capture is already in progress")
            return
        }
        service.feedbackOverlay.hideNow()
        removeSelection(true)
        val generation = work.generation
        val delayMs = SnapSettings.screenshotDelayMs(service)
        val clipboardDirect = SnapSettings.screenshotToClipboard(service)
        work.executeCapture {
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs.toLong())
                }
                if (!work.isCurrent(generation)) return@executeCapture
                if (!clipboardDirect) {
                    val error = RootCapture.requestNativeScreenshot()
                    if (error != null) {
                        work.postToMain(generation, { service.toast("Could not take screenshot: $error") })
                    }
                    return@executeCapture
                }
                when (val result = RootCapture.capture { !work.isCurrent(generation) }) {
                    is RootCapture.Result.Failure ->
                        work.postToMain(generation, { service.toast(result.message) })
                    is RootCapture.Result.Success -> {
                        try {
                            val file = service.repository.saveClipboard(result.bitmap)
                            work.postToMain(generation, { service.actions.copyFullScreenshot(file) })
                        } catch (error: IOException) {
                            work.postToMain(generation, {
                                service.toast("Could not copy screenshot: ${error.message}")
                            })
                        } finally {
                            recycle(result.bitmap)
                        }
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                main.post { finishFullScreenCapture(generation) }
            }
        }
    }

    private fun finishFullScreenCapture(generation: Int) {
        fullScreenCaptureBusy.set(false)
        if (!work.isCurrent(generation)) {
            return
        }
        service.pins.setVisible(
            service.pins.userPinsVisible && selectionView == null && !captureBusy.get(),
        )
        refreshBridgeState()
        service.stopIfIdle()
    }

    private fun runRootCapture(
        attribution: CaptureAttribution,
        readyDeadlineMs: Long = 0L,
        callback: (RootCapture.Result) -> Unit,
    ) {
        if (!rootCaptureSetupReady()) {
            service.stopIfIdle()
            return
        }
        if (fullScreenCaptureBusy.get() || !captureBusy.compareAndSet(false, true)) {
            service.toast("A capture is already in progress")
            return
        }
        val generation = work.generation
        val captureGeneration = ++rootCaptureGeneration
        refreshBridgeState()
        service.feedbackOverlay.hideNow()
        service.pins.setVisible(false)
        val start = Runnable {
            pendingCaptureStart = null
            if (!isCurrentRootCapture(generation, captureGeneration)) {
                return@Runnable
            }
            val captureRotation = service.displayRotation()
            work.executeCapture {
                if (!isCurrentRootCapture(generation, captureGeneration)) {
                    return@executeCapture
                }
                val cancelled = { !isCurrentRootCapture(generation, captureGeneration) }
                val rawResult = if (attribution.sourceRequested) {
                    RootCapture.captureWithAttribution(service, cancelled)
                } else {
                    RootCapture.capture(cancelled)
                }
                val completedRotation = service.displayRotation()
                val result = when (rawResult) {
                    is RootCapture.Result.Failure -> rawResult
                    is RootCapture.Result.Success -> {
                        if (completedRotation != captureRotation) {
                            recycle(rawResult.bitmap)
                            RootCapture.Result.Failure("Screen rotated during capture; try again")
                        } else {
                            rawResult.copy(rotation = captureRotation)
                        }
                    }
                }
                if (!isCurrentRootCapture(generation, captureGeneration)) {
                    if (result is RootCapture.Result.Success) recycle(result.bitmap)
                    return@executeCapture
                }
                if (result is RootCapture.Result.Success) {
                    SnapSettings.setRootVerified(service, true)
                }
                work.postToMain(generation, {
                    if (!isCurrentRootCapture(generation, captureGeneration)) {
                        if (result is RootCapture.Result.Success) recycle(result.bitmap)
                        return@postToMain
                    }
                    cancelCaptureDeadline()
                    captureBusy.set(false)
                    service.pins.setVisible(service.pins.userPinsVisible)
                    callback(result)
                    refreshBridgeState()
                }, {
                    if (result is RootCapture.Result.Success) recycle(result.bitmap)
                })
            }
        }
        pendingCaptureStart = start
        main.postDelayed(start, SURFACE_SETTLE_MS)
        if (readyDeadlineMs > 0L) {
            val deadline = Runnable {
                pendingCaptureDeadline = null
                if (!isCurrentRootCapture(generation, captureGeneration)) {
                    return@Runnable
                }
                cancelRootCapture()
                callback(RootCapture.Result.Failure("Freeze capture was not ready in time"))
            }
            pendingCaptureDeadline = deadline
            main.postDelayed(deadline, readyDeadlineMs)
        }
    }

    private fun isCurrentRootCapture(generation: Int, captureGeneration: Int): Boolean =
        work.isCurrent(generation) && rootCaptureGeneration == captureGeneration

    private fun cropSaveAndPerform(
        source: Bitmap,
        rect: RectF,
        surfaceWidth: Int,
        surfaceHeight: Int,
        action: SelectionCaptureAction,
        sourceQuarterTurns: Int,
        actionSurfaceRotation: Int,
        attribution: CaptureAttribution,
    ) {
        val logicalRect = RectF(rect)
        val generation = work.generation
        work.executeCapture {
            try {
                val sourceRect = mapRectToSource(rect, surfaceWidth, surfaceHeight, sourceQuarterTurns)
                val left = clamp(Math.round(sourceRect.left * source.width), 0, source.width - 1)
                val top = clamp(Math.round(sourceRect.top * source.height), 0, source.height - 1)
                val right = clamp(Math.round(sourceRect.right * source.width), left + 1, source.width)
                val bottom = clamp(Math.round(sourceRect.bottom * source.height), top + 1, source.height)
                val window = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                var cropped = if (window === source) source.copy(Bitmap.Config.ARGB_8888, false) else window
                if (sourceQuarterTurns != 0) {
                    val oriented = rotateBitmap(cropped, sourceQuarterTurns)
                    if (oriented !== cropped) cropped.recycle()
                    cropped = oriented
                }
                val externalProvider = SnapperActionRegistry.actionForSelectionCode(action.code)
                val externalKeepsPin = externalProvider != null &&
                    externalProvider.isExternal && !externalProvider.removeSnapAfterProcessing
                val keepsPin = action.code == SelectionAction.FLOAT ||
                    action.code == SelectionAction.IMGUR || externalKeepsPin
                val file = try {
                    service.repository.save(cropped, keepsPin, attribution.metadata)
                } catch (error: IOException) {
                    recycle(cropped)
                    throw error
                }
                if (keepsPin) {
                    work.postToMain(generation, {
                        try {
                            service.pins.addCapturedPin(
                                cropped, file,
                                logicalRect.width(), logicalRect.height(),
                                logicalRect.centerX(), logicalRect.centerY(),
                                surfaceWidth, surfaceHeight, actionSurfaceRotation,
                            )
                            if (action.code == SelectionAction.IMGUR) {
                                service.actions.uploadImgur(file)
                            } else if (externalKeepsPin) {
                                service.actions.launchExternal(
                                    file, checkNotNull(externalProvider),
                                    SnapperActionRegistry.ExternalSource.CROP,
                                )
                            }
                        } finally {
                            service.repository.releaseLivePin(file.name)
                        }
                    }, {
                        service.repository.releaseLivePin(file.name)
                        recycle(cropped)
                    })
                } else {
                    recycle(cropped)
                    work.postToMain(generation, { service.actions.performCropAction(file, action) })
                }
            } catch (error: IOException) {
                work.postToMain(generation, { service.toast("Could not create snap: ${error.message}") })
            } finally {
                recycle(source)
            }
        }
    }

    private fun refreshBridgeState() {
        main.removeCallbacks(stateHeartbeat)
        if (work.stopping) {
            return
        }
        val active = selectionView != null
        SnapActions.reportSelectionState(service, active)
        if (active) {
            main.postDelayed(stateHeartbeat, STATE_HEARTBEAT_MS)
        }
    }

    private fun rememberRect(rect: RectF, width: Int, height: Int, rotation: Int) {
        service.getSharedPreferences(RECT_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("left", rect.left / maxOf(1, width))
            .putFloat("top", rect.top / maxOf(1, height))
            .putFloat("right", rect.right / maxOf(1, width))
            .putFloat("bottom", rect.bottom / maxOf(1, height))
            .putInt("rotation", rotation)
            .apply()
    }

    private fun rememberedRect(currentRotation: Int): RectF? {
        val preferences = service.getSharedPreferences(RECT_PREFS, Context.MODE_PRIVATE)
        if (!preferences.contains("left")) {
            return null
        }
        val left = preferences.getFloat("left", 0f)
        val top = preferences.getFloat("top", 0f)
        val right = preferences.getFloat("right", 1f)
        val bottom = preferences.getFloat("bottom", 1f)
        if (right <= left || bottom <= top) {
            return null
        }
        val storedRotation = preferences.getInt("rotation", currentRotation)
        return when ((currentRotation - storedRotation + 4) % 4) {
            1 -> RectF(1f - bottom, left, 1f - top, right)
            2 -> RectF(1f - right, 1f - bottom, 1f - left, 1f - top)
            3 -> RectF(top, 1f - right, bottom, 1f - left)
            else -> RectF(left, top, right, bottom)
        }
    }
}

private const val RECT_PREFS = "selection"
private const val SURFACE_SETTLE_MS = 32L
private const val FREEZE_READY_DEADLINE_MS = 800L
private const val STATE_HEARTBEAT_MS = 2_000L
