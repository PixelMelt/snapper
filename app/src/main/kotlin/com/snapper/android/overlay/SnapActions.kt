package com.snapper.android.overlay

import android.content.Context
import android.content.Intent
import com.snapper.android.types.SnapperIpcContract

object SnapActions {
    const val ACTION_NORMAL = SnapperIpcContract.ACTION_NORMAL
    const val ACTION_FREEZE = SnapperIpcContract.ACTION_FREEZE
    const val ACTION_INSTANT = SnapperIpcContract.ACTION_INSTANT
    const val ACTION_OPEN_LAST = "com.snapper.android.action.OPEN_LAST"
    const val ACTION_HISTORY = "com.snapper.android.action.HISTORY"
    internal const val ACTION_OPEN_FILE = "com.snapper.android.action.OPEN_FILE"
    const val ACTION_HIDE_ALL = "com.snapper.android.action.HIDE_ALL"
    const val ACTION_SHOW_ALL = "com.snapper.android.action.SHOW_ALL"
    const val ACTION_CLOSE_ALL = "com.snapper.android.action.CLOSE_ALL"
    internal const val ACTION_STOP = "com.snapper.android.action.STOP"
    internal const val EXTRA_PATH = "path"

    fun send(context: Context, action: String) {
        val intent = Intent(context, OverlayService::class.java).setAction(action)
        context.startForegroundService(intent)
    }

    fun openFile(context: Context, path: String) {
        val intent = Intent(context, OverlayService::class.java)
            .setAction(ACTION_OPEN_FILE)
            .putExtra(EXTRA_PATH, path)
        context.startForegroundService(intent)
    }

    internal fun reportSelectionState(context: Context, active: Boolean) {
        context.sendBroadcast(
            Intent(SnapperIpcContract.ACTION_SELECTION_STATE)
                .setPackage(SnapperIpcContract.SYSTEM_PACKAGE)
                .putExtra(SnapperIpcContract.EXTRA_SELECTION_ACTIVE, active),
        )
    }

    internal fun reportNativeScreenshotReady(context: Context, generation: Long) {
        context.sendBroadcast(
            Intent(SnapperIpcContract.ACTION_NATIVE_SCREENSHOT_READY)
                .setPackage(SnapperIpcContract.SYSTEM_PACKAGE)
                .putExtra(SnapperIpcContract.EXTRA_NATIVE_SCREENSHOT_GENERATION, generation),
        )
    }
}
