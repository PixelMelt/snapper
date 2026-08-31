package com.snapper.android.storage

import android.content.ClipData
import android.content.Intent
import android.net.Uri

fun snapShareChooser(uri: Uri): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("Snapper screenshot", uri)
    return Intent.createChooser(send, "Share snap").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
