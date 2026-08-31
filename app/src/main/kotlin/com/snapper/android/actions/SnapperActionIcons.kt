package com.snapper.android.actions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.snapper.android.R

internal object SnapperActionIcons {
    const val SNAP = 0
    const val SHARE = 1
    const val COPY = 2
    const val SAVE = 3
    const val QR = 4
    const val OCR = 5
    const val IMGUR = 6
    const val SCREENSHOT = 7
    const val CLOSE = 8
    const val URL_SCHEME = 9
    const val BUILT_IN_COUNT = URL_SCHEME + 1

    private val resources = intArrayOf(
        R.drawable.snapper_action_snap,
        R.drawable.snapper_action_share,
        R.drawable.snapper_action_copy,
        R.drawable.snapper_action_save,
        R.drawable.snapper_action_qr,
        R.drawable.snapper_action_ocr,
        R.drawable.snapper_action_imgur,
        R.drawable.snapper_action_screenshot,
        R.drawable.snapper_action_close,
        R.drawable.snapper_action_url_scheme,
    )

    private var builtIn: Array<Bitmap>? = null

    fun decode(context: Context): Array<Bitmap> {
        val icons = builtIn ?: Array(resources.size) { index ->
            decodeBitmapResource(context, resources[index])
        }.also { builtIn = it }
        val external = SnapperActionRegistry.externalIcons
        return if (external.isEmpty()) icons else icons + external
    }
}

internal fun decodeBitmapResource(context: Context, resource: Int): Bitmap =
    requireNotNull(BitmapFactory.decodeResource(context.resources, resource))
