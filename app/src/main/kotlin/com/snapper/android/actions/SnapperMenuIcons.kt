package com.snapper.android.actions

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import com.snapper.android.R

internal object SnapperMenuIcons {
    private val dark = mapOf(
        SnapperActionRegistry.ID_COPY to R.drawable.snapper_menu_copy_dark,
        SnapperActionRegistry.ID_SAVE to R.drawable.snapper_menu_save_dark,
        SnapperActionRegistry.ID_SHARE to R.drawable.snapper_menu_share_dark,
        SnapperActionRegistry.ID_QR to R.drawable.snapper_menu_qr_dark,
        SnapperActionRegistry.ID_OCR to R.drawable.snapper_menu_ocr_dark,
        SnapperActionRegistry.ID_URL_SCHEME to R.drawable.snapper_menu_url_scheme_dark,
    )
    private val light = mapOf(
        SnapperActionRegistry.ID_COPY to R.drawable.snapper_menu_copy_light,
        SnapperActionRegistry.ID_SAVE to R.drawable.snapper_menu_save_light,
        SnapperActionRegistry.ID_SHARE to R.drawable.snapper_menu_share_light,
        SnapperActionRegistry.ID_QR to R.drawable.snapper_menu_qr_light,
        SnapperActionRegistry.ID_OCR to R.drawable.snapper_menu_ocr_light,
        SnapperActionRegistry.ID_URL_SCHEME to R.drawable.snapper_menu_url_scheme_light,
    )
    private val decoded = mutableMapOf<Int, Bitmap>()

    fun usesDarkArtwork(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun prewarm(context: Context) {
        (dark.values + light.values).forEach { bitmap(context, it) }
    }

    fun iconFor(context: Context, darkArtwork: Boolean, actionId: String): Bitmap? {
        val resource = (if (darkArtwork) dark else light)[actionId] ?: return null
        return bitmap(context, resource)
    }

    private fun bitmap(context: Context, resource: Int): Bitmap =
        decoded.getOrPut(resource) { decodeBitmapResource(context, resource) }
}
