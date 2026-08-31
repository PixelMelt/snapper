package com.snapper.android.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.snapper.android.types.SnapperIpcContract

class HardwareSettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, argument: String?, extras: Bundle?): Bundle {
        require(SnapperIpcContract.HARDWARE_SETTINGS_METHOD_GET_MODE == method) {
            "Unknown hardware settings method"
        }
        val context = requireContext()
        return Bundle().apply {
            putInt(SnapperIpcContract.HARDWARE_SETTINGS_RESULT_MODE, SnapSettings.hardwareMode(context))
            putInt(
                SnapperIpcContract.HARDWARE_SETTINGS_RESULT_SCREENSHOT_DELAY_MS,
                SnapSettings.screenshotDelayMs(context),
            )
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        throw UnsupportedOperationException("call-only provider")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        throw UnsupportedOperationException("read-only provider")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("read-only provider")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        throw UnsupportedOperationException("read-only provider")
    }
}
