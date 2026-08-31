package com.snapper.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.snapper.android.R
import com.snapper.android.actions.SnapperActionRegistry
import com.snapper.android.settings.SnapSettings
import com.snapper.android.storage.SnapRepository
import com.snapper.android.types.SnapperIpcContract
import java.io.File

class OverlayService : Service() {
    internal val main = Handler(Looper.getMainLooper())
    internal val work = OverlayWork(main) { stopIfIdle() }
    internal lateinit var windows: WindowManager
        private set
    internal lateinit var repository: SnapRepository
        private set
    internal lateinit var feedbackOverlay: OverlayFeedback
        private set
    internal lateinit var pins: PinBoard
        private set
    internal lateinit var actions: SnapActionRunner
        private set
    internal lateinit var capture: CaptureFlow
        private set
    private lateinit var displays: DisplayManager
    private lateinit var lastDisplayBounds: Rect
    private var lastDisplayRotation = Surface.ROTATION_0
    private val systemDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> capture.dismissSelectionForSystemNavigation()
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON)
                    if (reason == SYSTEM_DIALOG_HOME || reason == SYSTEM_DIALOG_RECENTS) {
                        capture.dismissSelectionForSystemNavigation()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        check(javaClass.name == SnapperIpcContract.OVERLAY_SERVICE_CLASS)
        windows = getSystemService(WindowManager::class.java)
        displays = getSystemService(DisplayManager::class.java)
        repository = SnapRepository(this)
        pins = PinBoard(this)
        startInForeground()
        SnapperActionRegistry.ensureExternalActions(this)
        feedbackOverlay = OverlayFeedback(this, windows, main) { stopIfIdle() }
        lastDisplayBounds = Rect(windows.currentWindowMetrics.bounds)
        lastDisplayRotation = displayRotation()
        actions = SnapActionRunner(this)
        capture = CaptureFlow(this)
        val dismissFilter = IntentFilter()
        dismissFilter.addAction(Intent.ACTION_SCREEN_OFF)
        dismissFilter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        registerReceiver(systemDismissReceiver, dismissFilter, Context.RECEIVER_EXPORTED)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleDisplayGeometryChanged()
        main.post { handleDisplayGeometryChanged() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == SnapActions.ACTION_STOP) {
            beginStopping()
            pins.closeAllImmediately()
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == SnapActions.ACTION_CLOSE_ALL) {
            pins.closeAll()
            return START_NOT_STICKY
        }
        if (work.stopping) {
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow ‘Display over other apps’ before using Snapper")
            beginStopping()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || action == null) {
            pins.restoreIfNeeded()
            return START_STICKY
        }
        if (requiresRootCapture(action) && !capture.rootCaptureSetupReady()) {
            pins.restoreIfNeeded()
            stopIfIdle()
            return START_NOT_STICKY
        }
        when (action) {
            SnapperIpcContract.ACTION_NORMAL -> capture.beginNormal()
            SnapperIpcContract.ACTION_FREEZE -> capture.beginFreeze()
            SnapperIpcContract.ACTION_INSTANT -> capture.beginInstant()
            SnapActions.ACTION_OPEN_LAST -> openLast()
            SnapActions.ACTION_OPEN_FILE ->
                openFile(File(requireNotNull(intent.getStringExtra(SnapActions.EXTRA_PATH))))
            SnapActions.ACTION_HIDE_ALL -> pins.requestUserPinsVisible(false)
            SnapActions.ACTION_SHOW_ALL -> pins.requestUserPinsVisible(true)
            SnapperIpcContract.ACTION_PREPARE_NATIVE_SCREENSHOT -> capture.prepareNativeScreenshot(intent)
        }
        if (action != SnapperIpcContract.ACTION_PREPARE_NATIVE_SCREENSHOT &&
            action != SnapperIpcContract.ACTION_FREEZE
        ) {
            pins.restoreIfNeeded()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        work.shutdown()
        unregisterReceiver(systemDismissReceiver)
        capture.destroy()
        feedbackOverlay.dismissImmediately()
        actions.dismissOcrChooser()
        pins.destroy()
        SnapActions.reportSelectionState(this, false)
        super.onDestroy()
    }

    private fun openLast() {
        val file = repository.latest()
        if (file == null) {
            toast("Snap history is empty")
            stopIfIdle()
            return
        }
        openFile(file)
    }

    private fun openFile(file: File) {
        val generation = work.generation
        work.execute {
            val bitmap = repository.decode(file)
            work.postToMain(generation, {
                if (bitmap == null) toast("Snap could not be decoded") else pins.addPin(bitmap, file)
            }, { recycle(bitmap) })
        }
    }

    internal fun beginStopping() {
        if (work.stopping) {
            return
        }
        work.beginStopping()
        actions.dismissOcrChooser()
        capture.cancelForStop()
        SnapActions.reportSelectionState(this, false)
    }

    internal fun stopIfIdle() {
        if (!work.stopping && !pins.hasWindows && !capture.selectionShowing &&
            !feedbackOverlay.isShowing && !capture.captureInProgress() &&
            !pins.restorationInFlight && work.inFlight == 0
        ) {
            beginStopping()
            stopSelf()
        }
    }

    internal fun canAttachOverlay(): Boolean = !work.stopping && Settings.canDrawOverlays(this)

    internal fun attachOverlay(view: View, params: WindowManager.LayoutParams): Boolean {
        if (!canAttachOverlay()) {
            return false
        }
        return try {
            windows.addView(view, params)
            true
        } catch (denied: WindowManager.BadTokenException) {
            false
        }
    }

    internal fun stopAfterOverlayFailure() {
        if (work.stopping) {
            return
        }
        toast(
            if (Settings.canDrawOverlays(this)) "Could not show the Snapper overlay"
            else "‘Display over other apps’ permission was removed",
        )
        beginStopping()
        stopSelf()
    }

    internal fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        return params
    }

    internal fun displayRotation(): Int = displays.getDisplay(Display.DEFAULT_DISPLAY).rotation

    internal fun toast(message: String) {
        if (work.stopping) {
            return
        }
        if (feedbackOverlay.show(message)) {
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    internal fun feedback(message: String) {
        if (SnapSettings.toastMessages(this)) {
            toast(message)
        }
    }

    internal fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, makeNotification())
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "Pinned screenshots", NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Keeps Snapper images visible over other apps"
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        startForeground(
            NOTIFICATION_ID, makeNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun makeNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            requireNotNull(packageManager.getLaunchIntentForPackage(packageName)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val close = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(SnapActions.ACTION_CLOSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val visibility = PendingIntent.getService(
            this, 3,
            Intent(this, OverlayService::class.java).setAction(
                if (pins.userPinsVisible) SnapActions.ACTION_HIDE_ALL else SnapActions.ACTION_SHOW_ALL,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val count = pins.count
        val text = if (count == 0) {
            "Ready to capture"
        } else {
            "$count snap${if (count == 1) "" else "s"} pinned" +
                if (pins.userPinsVisible) "" else " · hidden"
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_crop)
            .setContentTitle("Snapper")
            .setContentText(text)
            .setColor(Color.rgb(110, 168, 254))
            .setOngoing(count > 0)
            .setContentIntent(open)
        if (count > 0) {
            notification.addAction(
                Notification.Action.Builder(
                    null, if (pins.userPinsVisible) "Hide all" else "Show all", visibility,
                ).build(),
            )
        }
        return notification
            .addAction(Notification.Action.Builder(null, "Close all", close).build())
            .build()
    }

    private fun handleDisplayGeometryChanged() {
        if (work.stopping) {
            return
        }
        val currentBounds = Rect(windows.currentWindowMetrics.bounds)
        val currentRotation = displayRotation()
        val previousBounds = Rect(lastDisplayBounds)
        val quarterTurns = (currentRotation - lastDisplayRotation + 4) % 4
        if (quarterTurns == 0 && previousBounds.width() == currentBounds.width() &&
            previousBounds.height() == currentBounds.height()
        ) {
            return
        }
        feedbackOverlay.onDisplayGeometryChanged()
        capture.onDisplayRotationChanged(currentRotation)
        pins.onDisplayGeometryChanged(previousBounds, currentBounds, quarterTurns)
        lastDisplayBounds = currentBounds
        lastDisplayRotation = currentRotation
    }
}

private const val NOTIFICATION_ID = 3103
private const val CHANNEL_ID = "snapper_pins"
private const val SYSTEM_DIALOG_REASON = "reason"
private const val SYSTEM_DIALOG_HOME = "homekey"
private const val SYSTEM_DIALOG_RECENTS = "recentapps"

private fun requiresRootCapture(action: String): Boolean =
    action == SnapperIpcContract.ACTION_NORMAL ||
        action == SnapperIpcContract.ACTION_FREEZE ||
        action == SnapperIpcContract.ACTION_INSTANT
