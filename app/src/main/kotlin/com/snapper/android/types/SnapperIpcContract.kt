package com.snapper.android.types

object SnapperIpcContract {
    const val APP_PACKAGE = "com.snapper.android"
    const val SYSTEM_PACKAGE = "android"
    const val OVERLAY_SERVICE_CLASS = "com.snapper.android.overlay.OverlayService"

    const val ACTION_NORMAL = "com.snapper.android.action.NORMAL"
    const val ACTION_INSTANT = "com.snapper.android.action.INSTANT"
    const val ACTION_FREEZE = "com.snapper.android.action.FREEZE"
    const val ACTION_PREPARE_NATIVE_SCREENSHOT =
        "com.snapper.android.action.PREPARE_NATIVE_SCREENSHOT"
    const val ACTION_NATIVE_SCREENSHOT_READY =
        "com.snapper.android.action.NATIVE_SCREENSHOT_READY"
    const val ACTION_SELECTION_STATE = "com.snapper.android.action.SELECTION_STATE"

    const val EXTRA_SELECTION_ACTIVE = "selection_active"
    const val EXTRA_NATIVE_SCREENSHOT_GENERATION = "native_screenshot_generation"

    const val REPORT_SELECTION_STATE_PERMISSION =
        "com.snapper.android.permission.REPORT_SELECTION_STATE"

    const val HARDWARE_SETTINGS_AUTHORITY = "com.snapper.android.hardware"
    const val HARDWARE_SETTINGS_METHOD_GET_MODE = "get_hardware_mode"
    const val HARDWARE_SETTINGS_RESULT_MODE = "hardware_mode"
    const val HARDWARE_SETTINGS_RESULT_SCREENSHOT_DELAY_MS = "screenshot_delay_ms"

    const val HARDWARE_MODE_NATIVE = 0
    const val HARDWARE_MODE_NORMAL = 1
    const val HARDWARE_MODE_INSTANT = 2
    const val HARDWARE_MODE_FREEZE = 3
    const val DEFAULT_SCREENSHOT_DELAY_MS = 50
}
