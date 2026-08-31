package com.snapper.android.scan

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns

enum class QrDelivery {
    OPENED_LINK,
    COPIED_LINK_WITHOUT_BROWSER,
    COPIED_TEXT,
    EMPTY,
}

fun deliverQrText(context: Context, text: String): QrDelivery {
    val decoded = text.trim()
    if (decoded.isEmpty()) {
        return QrDelivery.EMPTY
    }
    val webUri = webUri(decoded)
    if (webUri != null) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, webUri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return QrDelivery.OPENED_LINK
        } catch (noBrowser: ActivityNotFoundException) {
            copyQrText(context, decoded)
            return QrDelivery.COPIED_LINK_WITHOUT_BROWSER
        }
    }
    copyQrText(context, decoded)
    return QrDelivery.COPIED_TEXT
}

private fun copyQrText(context: Context, decoded: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("QR code", decoded))
}

private fun webUri(candidate: String): Uri? {
    if (candidate.any { it.isWhitespace() || it.isISOControl() }) {
        return null
    }
    var uri = Uri.parse(candidate)
    var scheme = uri.scheme
    if (scheme == null) {
        if (!Patterns.WEB_URL.matcher(candidate).matches()) {
            return null
        }
        uri = Uri.parse("https://$candidate")
        scheme = uri.scheme
    }
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
        return null
    }
    return if (uri.host.isNullOrEmpty()) null else uri
}
