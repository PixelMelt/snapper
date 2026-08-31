package com.snapper.android.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.snapper.android.storage.decodeBitmapBounds
import com.snapper.android.storage.decodeSampledBitmap
import com.snapper.android.types.QrScanResult
import java.io.File

object QrScanner {
    fun scanFile(file: File): QrScanResult {
        val bounds = decodeBitmapBounds(file)
        val bitmap = decodeSampledBitmap(file, 1024)
            ?: return failure(QrScanResult.Reason.DECODE_ERROR, "Couldn't read this capture for QR scanning")
        val sampled = bounds.outWidth > bitmap.width || bounds.outHeight > bitmap.height
        val result = try {
            scan(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (!sampled || result is QrScanResult.Success) {
            return result
        }
        val full = BitmapFactory.decodeFile(file.absolutePath) ?: return result
        return try {
            scan(full)
        } finally {
            full.recycle()
        }
    }

    fun scan(bitmap: Bitmap): QrScanResult {
        return try {
            val luminance = BitmapLuminanceSource(bitmap)
            val decoded: Result = try {
                decodeOnce(luminance)
            } catch (normalFailure: ReaderException) {
                luminance.invertInPlace()
                try {
                    decodeOnce(luminance)
                } catch (invertedFailure: ReaderException) {
                    return decodeFailure(normalFailure, invertedFailure)
                }
            }
            QrScanResult.Success(decoded.text)
        } catch (exhausted: OutOfMemoryError) {
            failure(QrScanResult.Reason.DECODE_ERROR, "Not enough image memory to scan QR code")
        }
    }

    private fun decodeOnce(luminance: LuminanceSource): Result {
        val binary = BinaryBitmap(HybridBinarizer(luminance))
        return QRCodeReader().decode(binary)
    }

    private fun decodeFailure(
        normalFailure: ReaderException,
        invertedFailure: ReaderException,
    ): QrScanResult {
        val concreteFailure =
            if (normalFailure is NotFoundException) invertedFailure else normalFailure
        if (concreteFailure is ChecksumException) {
            return failure(QrScanResult.Reason.DECODE_ERROR, "QR checksum failed")
        }
        if (concreteFailure is FormatException) {
            return failure(QrScanResult.Reason.DECODE_ERROR, "QR payload is malformed")
        }
        return failure(QrScanResult.Reason.NO_QR_FOUND, "No QR code found")
    }

    private class BitmapLuminanceSource(private val bitmap: Bitmap) :
        LuminanceSource(bitmap.width, bitmap.height) {
        private var rowPixels: IntArray? = null
        private var matrix: ByteArray? = null
        private var inverted = false

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val width = width
            require(y in 0 until height) { "Requested row is outside the image: $y" }
            var target = row
            if (target == null || target.size < width) {
                target = ByteArray(width)
            }
            val cachedMatrix = matrix
            if (cachedMatrix != null) {
                System.arraycopy(cachedMatrix, y * width, target, 0, width)
                return target
            }
            var pixels = rowPixels
            if (pixels == null || pixels.size < width) {
                pixels = IntArray(width)
                rowPixels = pixels
            }
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            writeLuminance(pixels, target, 0, width, inverted)
            return target
        }

        override fun getMatrix(): ByteArray {
            matrix?.let { return it }
            val width = width
            val height = height
            val luminance = ByteArray(Math.multiplyExact(width, height))
            val cachedPixels = rowPixels
            val pixels = if (cachedPixels == null || cachedPixels.size < width) {
                IntArray(width)
            } else {
                cachedPixels
            }
            for (y in 0 until height) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                writeLuminance(pixels, luminance, y * width, width, inverted)
            }
            rowPixels = null
            matrix = luminance
            return luminance
        }

        fun invertInPlace() {
            inverted = !inverted
            val cachedMatrix = matrix ?: return
            for (index in cachedMatrix.indices) {
                cachedMatrix[index] = (255 - (cachedMatrix[index].toInt() and 0xff)).toByte()
            }
        }

        companion object {
            private fun writeLuminance(
                pixels: IntArray,
                target: ByteArray,
                offset: Int,
                width: Int,
                inverted: Boolean,
            ) {
                for (x in 0 until width) {
                    val pixel = pixels[x]
                    val red = (pixel ushr 16) and 0xff
                    val greenTwice = (pixel ushr 7) and 0x1fe
                    val blue = pixel and 0xff
                    val luminance = (red + greenTwice + blue) / 4
                    target[offset + x] = (if (inverted) 255 - luminance else luminance).toByte()
                }
            }
        }
    }

    private fun failure(reason: QrScanResult.Reason, message: String): QrScanResult =
        QrScanResult.Failure(reason, message)
}
