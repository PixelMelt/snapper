package com.snapper.android.xposed

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import com.snapper.android.types.SnapperIpcContract
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Member

class PhysicalScreenshotHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(loadPackage: XC_LoadPackage.LoadPackageParam) {
        if (loadPackage.packageName != SYSTEM_PACKAGE) {
            return
        }
        installScreenshotHook(loadPackage.classLoader)
        installStateBridge(loadPackage.classLoader)
    }

    private class PendingSnapperDispatch(
        val generation: Long,
        val handler: Handler,
        val method: Member,
        val receiver: Any,
        val arguments: Array<Any?>,
        val watchdog: Runnable,
    )

    private class PendingNativeScreenshot(
        val generation: Long,
        val handler: Handler,
        val method: Member,
        val receiver: Any,
        val arguments: Array<Any?>,
        val delayMs: Int,
        val readyWatchdog: Runnable,
    ) {
        var phase: Int = NATIVE_WAITING_FOR_READY
        var delayedScreenshot: Runnable? = null
    }

    companion object {
        private const val SYSTEM_PACKAGE = SnapperIpcContract.SYSTEM_PACKAGE
        private const val PHONE_WINDOW_MANAGER =
            "com.android.server.policy.PhoneWindowManager"
        private const val SCREENSHOT_HELPER =
            "com.android.internal.util.ScreenshotHelper"
        private const val SCREENSHOT_REQUEST =
            "com.android.internal.util.ScreenshotRequest"
        private const val APP_PACKAGE = SnapperIpcContract.APP_PACKAGE
        private val OVERLAY_SERVICE = ComponentName(
            APP_PACKAGE, SnapperIpcContract.OVERLAY_SERVICE_CLASS)
        private val HARDWARE_SETTINGS_URI =
            Uri.parse("content://${SnapperIpcContract.HARDWARE_SETTINGS_AUTHORITY}")
        private const val METHOD_GET_HARDWARE_MODE =
            SnapperIpcContract.HARDWARE_SETTINGS_METHOD_GET_MODE
        private const val RESULT_HARDWARE_MODE =
            SnapperIpcContract.HARDWARE_SETTINGS_RESULT_MODE
        private const val RESULT_SCREENSHOT_DELAY_MS =
            SnapperIpcContract.HARDWARE_SETTINGS_RESULT_SCREENSHOT_DELAY_MS
        private const val ACTION_NORMAL = SnapperIpcContract.ACTION_NORMAL
        private const val ACTION_INSTANT = SnapperIpcContract.ACTION_INSTANT
        private const val ACTION_FREEZE = SnapperIpcContract.ACTION_FREEZE
        private const val ACTION_PREPARE_NATIVE_SCREENSHOT =
            SnapperIpcContract.ACTION_PREPARE_NATIVE_SCREENSHOT
        private const val ACTION_NATIVE_SCREENSHOT_READY =
            SnapperIpcContract.ACTION_NATIVE_SCREENSHOT_READY
        private const val ACTION_SELECTION_STATE =
            SnapperIpcContract.ACTION_SELECTION_STATE
        private const val EXTRA_SELECTION_ACTIVE = SnapperIpcContract.EXTRA_SELECTION_ACTIVE
        private const val EXTRA_NATIVE_SCREENSHOT_GENERATION =
            SnapperIpcContract.EXTRA_NATIVE_SCREENSHOT_GENERATION
        private const val BRIDGE_PERMISSION =
            SnapperIpcContract.REPORT_SELECTION_STATE_PERMISSION
        private const val SCREENSHOT_KEY_CHORD = 1
        private const val HARDWARE_MODE_NATIVE = SnapperIpcContract.HARDWARE_MODE_NATIVE
        private const val HARDWARE_MODE_NORMAL = SnapperIpcContract.HARDWARE_MODE_NORMAL
        private const val HARDWARE_MODE_INSTANT = SnapperIpcContract.HARDWARE_MODE_INSTANT
        private const val HARDWARE_MODE_FREEZE = SnapperIpcContract.HARDWARE_MODE_FREEZE
        private const val DEFAULT_SCREENSHOT_DELAY_MS =
            SnapperIpcContract.DEFAULT_SCREENSHOT_DELAY_MS
        private const val MAX_SCREENSHOT_DELAY_MS = 1_000
        private const val DISPATCH_CONFIRMATION_MS = 5_000L
        private const val ACTIVE_STATE_LEASE_MS = 5_000L
        private const val SNAPPER_READY_WATCHDOG_MS = 1_000L
        private const val NATIVE_READY_WATCHDOG_MS = 750L

        private const val SELECTION_IDLE = 0
        private const val SELECTION_DISPATCHED = 1
        private const val SELECTION_ACTIVE = 2
        private const val SELECTION_NATIVE_PENDING = 3
        private const val NATIVE_WAITING_FOR_READY = 1
        private const val NATIVE_WAITING_FOR_DELAY = 2

        private val DISPATCH_LOCK = Any()
        private var selectionState = SELECTION_IDLE
        private var selectionStateDeadline = 0L
        private var stateReceiverRegistered = false
        private var chordCallbackLogged = false
        private var hardwareSettingsFailureLogged = false
        private var screenshotDelayFailureLogged = false
        private var lastLoggedHardwareMode = -1
        private var snapperDispatchGeneration = 0L
        private var pendingSnapperDispatch: PendingSnapperDispatch? = null
        private var nativeScreenshotGeneration = 0L
        private var pendingNativeScreenshot: PendingNativeScreenshot? = null

        private val SELECTION_STATE_RECEIVER = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_NATIVE_SCREENSHOT_READY) {
                    nativeScreenshotReady(intent.getLongExtra(
                        EXTRA_NATIVE_SCREENSHOT_GENERATION, -1L))
                    return
                }
                if (intent.action != ACTION_SELECTION_STATE) {
                    return
                }
                synchronized(DISPATCH_LOCK) {
                    if (intent.getBooleanExtra(EXTRA_SELECTION_ACTIVE, false)) {
                        if (selectionState == SELECTION_DISPATCHED) {
                            acknowledgeSnapperDispatchLocked()
                            selectionState = SELECTION_ACTIVE
                            selectionStateDeadline = SystemClock.elapsedRealtime() +
                                ACTIVE_STATE_LEASE_MS
                            logInfo("Snapper capture surface acknowledged")
                            return
                        }
                        if (selectionState != SELECTION_NATIVE_PENDING) {
                            selectionState = SELECTION_ACTIVE
                            selectionStateDeadline = SystemClock.elapsedRealtime() +
                                ACTIVE_STATE_LEASE_MS
                        }
                    } else if (selectionState == SELECTION_ACTIVE) {
                        selectionState = SELECTION_IDLE
                        selectionStateDeadline = 0L
                    }
                }
            }
        }

        private fun installScreenshotHook(classLoader: ClassLoader) {
            try {
                XposedHelpers.findAndHookMethod(
                    SCREENSHOT_HELPER,
                    classLoader,
                    "takeScreenshotInternal",
                    SCREENSHOT_REQUEST,
                    Handler::class.java,
                    "java.util.function.Consumer",
                    Long::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            interceptRecognizedScreenshotRequest(param)
                        }
                    })
                logInfo("screenshot hook installed: ScreenshotHelper.takeScreenshotInternal")
            } catch (failure: Throwable) {
                logFailure("ScreenshotHelper screenshot hook unavailable", failure)
            }
        }

        private fun installStateBridge(classLoader: ClassLoader) {
            try {
                XposedHelpers.findAndHookMethod(
                    PHONE_WINDOW_MANAGER,
                    classLoader,
                    "systemReady",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            registerStateReceiverFrom(param.thisObject)
                        }
                    })
            } catch (failure: Throwable) {
                logFailure("Early selection-state bridge unavailable", failure)
            }
        }

        private fun interceptRecognizedScreenshotRequest(
            param: XC_MethodHook.MethodHookParam,
        ) {
            try {
                val request = param.args[0] ?: return
                val handler = param.args[1] as? Handler ?: return
                val sourceValue = XposedHelpers.getObjectField(request, "mSource")
                val typeValue = XposedHelpers.getObjectField(request, "mType")
                if (sourceValue !is Int || sourceValue != SCREENSHOT_KEY_CHORD ||
                    typeValue !is Int || typeValue != 1) {
                    return
                }
                if (!chordCallbackLogged) {
                    chordCallbackLogged = true
                    logInfo("physical screenshot request observed via ScreenshotHelper")
                }
                if (dispatchToSnapper(param, handler)) {
                    param.setResult(null)
                }
            } catch (failure: Throwable) {
                logFailure("Snapper screenshot dispatch failed", failure)
            }
        }

        private fun dispatchToSnapper(
            param: XC_MethodHook.MethodHookParam,
            handler: Handler,
        ): Boolean {
            synchronized(DISPATCH_LOCK) {
                val now = SystemClock.elapsedRealtime()
                val context = contextFrom(param.thisObject)
                if (context == null) {
                    return false
                }
                ensureStateReceiver(context)
                if (selectionState != SELECTION_IDLE &&
                    selectionState != SELECTION_NATIVE_PENDING &&
                    now >= selectionStateDeadline) {
                    if (selectionState == SELECTION_DISPATCHED) {
                        cancelPendingSnapperDispatchLocked()
                    } else {
                        selectionState = SELECTION_IDLE
                        selectionStateDeadline = 0L
                    }
                }
                if (selectionState == SELECTION_NATIVE_PENDING) {
                    cancelPendingNativeScreenshotLocked()
                    logInfo("pending native screenshot cancelled by a fresh chord")
                    return false
                }
                if (selectionState == SELECTION_DISPATCHED) {
                    cancelPendingSnapperDispatchLocked()
                    return prepareAndScheduleNativeScreenshot(context, handler, param)
                }
                if (selectionState == SELECTION_ACTIVE) {
                    return prepareAndScheduleNativeScreenshot(context, handler, param)
                }
                if (!canTakeOver(context)) {
                    return false
                }
                val action = actionForHardwareMode(context) ?: return false
                return startSnapperWithFallback(context, handler, param, action, now)
            }
        }

        private fun startSnapperWithFallback(
            context: Context,
            handler: Handler,
            param: XC_MethodHook.MethodHookParam,
            action: String,
            now: Long,
        ): Boolean {
            val method = param.method ?: return false
            val receiver = param.thisObject ?: return false

            val generation = ++snapperDispatchGeneration
            val watchdog = Runnable { snapperReadyWatchdog(generation) }
            val pending = PendingSnapperDispatch(
                generation,
                handler,
                method,
                receiver,
                param.args.clone(),
                watchdog,
            )
            pendingSnapperDispatch = pending
            selectionState = SELECTION_DISPATCHED
            selectionStateDeadline = now + SNAPPER_READY_WATCHDOG_MS +
                DISPATCH_CONFIRMATION_MS

            try {
                if (!handler.postDelayed(watchdog, SNAPPER_READY_WATCHDOG_MS)) {
                    rollbackSnapperDispatchLocked(pending)
                    return false
                }
                val trigger = Intent(action).setComponent(OVERLAY_SERVICE)
                val accepted = context.startForegroundService(trigger)
                if (accepted == null) {
                    rollbackSnapperDispatchLocked(pending)
                    return false
                }
            } catch (failure: Throwable) {
                rollbackSnapperDispatchLocked(pending)
                logFailure("Snapper service scheduling failed", failure)
                return false
            }
            logInfo("Snapper service accepted; waiting for capture surface")
            return true
        }

        private fun snapperReadyWatchdog(generation: Long) {
            val pending: PendingSnapperDispatch
            synchronized(DISPATCH_LOCK) {
                val current = pendingSnapperDispatch
                if (selectionState != SELECTION_DISPATCHED || current == null ||
                    current.generation != generation) {
                    return
                }
                pending = current
                pendingSnapperDispatch = null
                selectionState = SELECTION_IDLE
                selectionStateDeadline = 0L
            }
            logInfo("Snapper surface acknowledgement timed out; taking stock screenshot")
            invokeStockScreenshot(pending.method, pending.receiver, pending.arguments,
                "First-chord stock fallback failed")
        }

        private fun acknowledgeSnapperDispatchLocked() {
            val pending = checkNotNull(pendingSnapperDispatch)
            pending.handler.removeCallbacks(pending.watchdog)
            pendingSnapperDispatch = null
        }

        private fun cancelPendingSnapperDispatchLocked() {
            acknowledgeSnapperDispatchLocked()
            snapperDispatchGeneration++
            selectionState = SELECTION_IDLE
            selectionStateDeadline = 0L
        }

        private fun rollbackSnapperDispatchLocked(pending: PendingSnapperDispatch) {
            pending.handler.removeCallbacks(pending.watchdog)
            pendingSnapperDispatch = null
            snapperDispatchGeneration++
            selectionState = SELECTION_IDLE
            selectionStateDeadline = 0L
        }

        private fun prepareAndScheduleNativeScreenshot(
            context: Context,
            handler: Handler,
            param: XC_MethodHook.MethodHookParam,
        ): Boolean {
            if (!canTakeOver(context)) {
                return false
            }
            val method = param.method ?: return false
            val receiver = param.thisObject ?: return false

            val priorState = selectionState
            val priorDeadline = selectionStateDeadline
            val generation = ++nativeScreenshotGeneration
            val watchdog = Runnable { nativeReadyWatchdog(generation) }
            val pending = PendingNativeScreenshot(
                generation,
                handler,
                method,
                receiver,
                param.args.clone(),
                screenshotDelayMs(context),
                watchdog,
            )
            pendingNativeScreenshot = pending
            selectionState = SELECTION_NATIVE_PENDING

            try {
                if (!handler.postDelayed(watchdog, NATIVE_READY_WATCHDOG_MS)) {
                    rollbackPendingNativeLocked(pending, priorState, priorDeadline)
                    return false
                }
                val prepare = Intent(ACTION_PREPARE_NATIVE_SCREENSHOT)
                    .setComponent(OVERLAY_SERVICE)
                    .putExtra(EXTRA_NATIVE_SCREENSHOT_GENERATION, generation)
                val accepted = context.startForegroundService(prepare)
                if (accepted == null) {
                    rollbackPendingNativeLocked(pending, priorState, priorDeadline)
                    return false
                }
            } catch (failure: Throwable) {
                rollbackPendingNativeLocked(pending, priorState, priorDeadline)
                logFailure("Native screenshot preparation scheduling failed", failure)
                return false
            }
            logInfo("waiting for crop-close acknowledgement")
            return true
        }

        private fun nativeScreenshotReady(generation: Long) {
            var invokeWithoutHandler = false
            synchronized(DISPATCH_LOCK) {
                val pending = pendingNativeScreenshot
                if (selectionState != SELECTION_NATIVE_PENDING || pending == null ||
                    pending.generation != generation ||
                    pending.phase != NATIVE_WAITING_FOR_READY) {
                    return
                }
                pending.handler.removeCallbacks(pending.readyWatchdog)
                pending.phase = NATIVE_WAITING_FOR_DELAY
                val delayed = Runnable { invokeQueuedNativeScreenshot(generation) }
                pending.delayedScreenshot = delayed
                if (!pending.handler.postDelayed(delayed, pending.delayMs.toLong())) {
                    pending.delayedScreenshot = null
                    invokeWithoutHandler = true
                } else {
                    logInfo("crop closed; native screenshot queued in " +
                        pending.delayMs + "ms")
                }
            }
            if (invokeWithoutHandler) {
                invokeQueuedNativeScreenshot(generation)
            }
        }

        private fun nativeReadyWatchdog(generation: Long) {
            synchronized(DISPATCH_LOCK) {
                val pending = pendingNativeScreenshot
                if (selectionState != SELECTION_NATIVE_PENDING || pending == null ||
                    pending.generation != generation ||
                    pending.phase != NATIVE_WAITING_FOR_READY) {
                    return
                }
                pending.phase = NATIVE_WAITING_FOR_DELAY
            }
            logInfo("crop-close acknowledgement timed out; taking stock screenshot")
            invokeQueuedNativeScreenshot(generation)
        }

        private fun invokeQueuedNativeScreenshot(generation: Long) {
            val pending: PendingNativeScreenshot
            synchronized(DISPATCH_LOCK) {
                val current = pendingNativeScreenshot
                if (selectionState != SELECTION_NATIVE_PENDING || current == null ||
                    current.generation != generation ||
                    current.phase != NATIVE_WAITING_FOR_DELAY) {
                    return
                }
                pending = current
                pendingNativeScreenshot = null
                selectionState = SELECTION_IDLE
                selectionStateDeadline = 0L
            }
            invokeStockScreenshot(pending.method, pending.receiver, pending.arguments,
                "Queued native screenshot failed")
        }

        private fun invokeStockScreenshot(
            method: Member,
            receiver: Any,
            arguments: Array<Any?>,
            failureMessage: String,
        ) {
            try {
                XposedBridge.invokeOriginalMethod(method, receiver, arguments)
            } catch (failure: Throwable) {
                logFailure(failureMessage, failure)
            }
        }

        private fun cancelPendingNativeScreenshotLocked() {
            val pending = checkNotNull(pendingNativeScreenshot)
            if (pending.phase == NATIVE_WAITING_FOR_READY) {
                pending.handler.removeCallbacks(pending.readyWatchdog)
            } else {
                val delayed = pending.delayedScreenshot
                if (delayed != null) {
                    pending.handler.removeCallbacks(delayed)
                }
            }
            pendingNativeScreenshot = null
            nativeScreenshotGeneration++
            selectionState = SELECTION_IDLE
            selectionStateDeadline = 0L
        }

        private fun rollbackPendingNativeLocked(
            pending: PendingNativeScreenshot,
            priorState: Int,
            priorDeadline: Long,
        ) {
            pending.handler.removeCallbacks(pending.readyWatchdog)
            pendingNativeScreenshot = null
            nativeScreenshotGeneration++
            selectionState = priorState
            selectionStateDeadline = priorDeadline
        }

        private fun ensureStateReceiver(context: Context) {
            if (stateReceiverRegistered) {
                return
            }
            val filter = IntentFilter(ACTION_SELECTION_STATE)
            filter.addAction(ACTION_NATIVE_SCREENSHOT_READY)
            context.registerReceiver(
                SELECTION_STATE_RECEIVER,
                filter,
                BRIDGE_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED)
            stateReceiverRegistered = true
        }

        private fun registerStateReceiverFrom(phoneWindowManager: Any?) {
            try {
                val context = contextFrom(phoneWindowManager) ?: return
                synchronized(DISPATCH_LOCK) {
                    ensureStateReceiver(context)
                }
            } catch (failure: Throwable) {
                logFailure("Selection-state bridge registration failed", failure)
            }
        }

        private fun contextFrom(phoneWindowManager: Any?): Context? {
            if (phoneWindowManager == null) {
                return null
            }
            val context = XposedHelpers.getObjectField(phoneWindowManager, "mContext")
            return context as? Context
        }

        private fun canTakeOver(context: Context): Boolean {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (keyguard != null && (keyguard.isDeviceLocked || keyguard.isKeyguardLocked)) {
                return false
            }

            val policies = context.getSystemService(DevicePolicyManager::class.java)
            if (policies != null && policies.getScreenCaptureDisabled(null)) {
                return false
            }

            val packages = context.packageManager
            val application: ApplicationInfo
            try {
                application = packages.getApplicationInfo(APP_PACKAGE, 0)
            } catch (missing: PackageManager.NameNotFoundException) {
                return false
            }
            if (!application.enabled ||
                (application.flags and ApplicationInfo.FLAG_STOPPED) != 0) {
                return false
            }

            val trigger = Intent(ACTION_NORMAL).setComponent(OVERLAY_SERVICE)
            val resolved = packages.resolveService(trigger, 0)
            if (resolved == null || resolved.serviceInfo == null ||
                !resolved.serviceInfo.enabled) {
                return false
            }

            val appOps = context.getSystemService(AppOpsManager::class.java)
            return appOps != null && appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                application.uid,
                APP_PACKAGE) == AppOpsManager.MODE_ALLOWED
        }

        private fun actionForHardwareMode(context: Context): String? {
            try {
                val result = context.contentResolver.call(
                    HARDWARE_SETTINGS_URI, METHOD_GET_HARDWARE_MODE, null, null)
                var mode = result?.getInt(RESULT_HARDWARE_MODE, HARDWARE_MODE_NATIVE)
                    ?: HARDWARE_MODE_NATIVE
                if (mode < HARDWARE_MODE_NATIVE || mode > HARDWARE_MODE_FREEZE) {
                    mode = HARDWARE_MODE_NATIVE
                }
                if (mode != lastLoggedHardwareMode) {
                    lastLoggedHardwareMode = mode
                    logInfo("physical screenshot mode: " + hardwareModeName(mode))
                }
                return when (mode) {
                    HARDWARE_MODE_NORMAL -> ACTION_NORMAL
                    HARDWARE_MODE_INSTANT -> ACTION_INSTANT
                    HARDWARE_MODE_FREEZE -> ACTION_FREEZE
                    else -> null
                }
            } catch (failure: Throwable) {
                if (!hardwareSettingsFailureLogged) {
                    hardwareSettingsFailureLogged = true
                    logFailure("Hardware screenshot mode unavailable", failure)
                }
                return null
            }
        }

        private fun screenshotDelayMs(context: Context): Int {
            try {
                val result = context.contentResolver.call(
                    HARDWARE_SETTINGS_URI, METHOD_GET_HARDWARE_MODE, null, null)
                val delayMs = result?.getInt(RESULT_SCREENSHOT_DELAY_MS,
                    DEFAULT_SCREENSHOT_DELAY_MS) ?: DEFAULT_SCREENSHOT_DELAY_MS
                return delayMs.coerceIn(0, MAX_SCREENSHOT_DELAY_MS)
            } catch (failure: Throwable) {
                if (!screenshotDelayFailureLogged) {
                    screenshotDelayFailureLogged = true
                    logFailure("Native screenshot delay unavailable; using 50ms", failure)
                }
                return DEFAULT_SCREENSHOT_DELAY_MS
            }
        }

        private fun hardwareModeName(mode: Int): String =
            when (mode) {
                HARDWARE_MODE_NORMAL -> "Normal"
                HARDWARE_MODE_INSTANT -> "Instant"
                HARDWARE_MODE_FREEZE -> "Freeze"
                else -> "Native"
            }

        private fun logFailure(message: String, failure: Throwable) {
            XposedBridge.log("Snapper: $message (${failure.javaClass.simpleName})")
        }

        private fun logInfo(message: String) {
            XposedBridge.log("Snapper: $message")
        }
    }
}
