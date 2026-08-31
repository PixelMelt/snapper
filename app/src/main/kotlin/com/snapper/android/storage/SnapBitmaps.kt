package com.snapper.android.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

fun decodeBitmapBounds(file: File): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    return bounds
}

fun decodeSampledBitmap(file: File, maximumPixels: Int): Bitmap? {
    val bounds = decodeBitmapBounds(file)
    var sample = 1
    while (bounds.outWidth / sample > maximumPixels * 2 ||
        bounds.outHeight / sample > maximumPixels * 2
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
