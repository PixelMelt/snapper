package com.snapper.android.scan

import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.snapper.android.types.OcrScript
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object OcrScanner {
    private const val RECOGNITION_TIMEOUT_SECONDS = 35L
    private const val MODEL_NOT_READY_ERROR = 17
    private const val SERVICE_UNAVAILABLE_ERROR = 14

    sealed interface ScanResult {
        data class Success(val text: String) : ScanResult

        data class Failure(val message: String) : ScanResult
    }

    fun scan(bitmap: Bitmap, script: OcrScript): ScanResult {
        return try {
            val aggregate = createRecognizer(script).use { recognizer ->
                val input = InputImage.fromBitmap(bitmap, 0)
                val result = Tasks.await(
                    recognizer.process(input), RECOGNITION_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                )
                aggregate(result)
            }
            if (aggregate.isEmpty()) {
                ScanResult.Failure("No text found in this capture")
            } else {
                ScanResult.Success(aggregate)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            ScanResult.Failure("OCR was interrupted")
        } catch (timeout: TimeoutException) {
            ScanResult.Failure("Text recognition timed out")
        } catch (execution: ExecutionException) {
            fromFailure(execution.cause, script)
        }
    }

    private fun createRecognizer(script: OcrScript): TextRecognizer {
        return when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.CHINESE -> TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )
            OcrScript.DEVANAGARI -> TextRecognition.getClient(
                DevanagariTextRecognizerOptions.Builder().build(),
            )
            OcrScript.JAPANESE -> TextRecognition.getClient(
                JapaneseTextRecognizerOptions.Builder().build(),
            )
            OcrScript.KOREAN -> TextRecognition.getClient(
                KoreanTextRecognizerOptions.Builder().build(),
            )
        }
    }

    fun asValidWebUri(aggregate: String): Uri? {
        val value = aggregate.trim()
        if (value.isEmpty()) return null
        for (character in value) {
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                return null
            }
        }

        val parsed = Uri.parse(value)
        val scheme = parsed.scheme
        if (scheme != null) {
            if (!("http".equals(scheme, ignoreCase = true) ||
                    "https".equals(scheme, ignoreCase = true)) ||
                !parsed.isHierarchical || parsed.host.isNullOrEmpty()
            ) {
                return null
            }
            return parsed.buildUpon().scheme(scheme.lowercase(Locale.ROOT)).build()
        }
        if (value.indexOf('.') < 0) return null
        val https = Uri.parse("https://$value")
        return if (https.isHierarchical && !https.host.isNullOrEmpty()) https else null
    }

    private fun aggregate(result: Text): String {
        val lines = mutableListOf<String>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val value = line.text.trim()
                if (value.isNotEmpty()) lines.add(value)
            }
        }
        return lines.joinToString(" ")
    }

    private fun fromFailure(failure: Throwable?, script: OcrScript): ScanResult {
        if (failure is MlKitException) {
            val code = failure.errorCode
            if (code == MODEL_NOT_READY_ERROR || code == SERVICE_UNAVAILABLE_ERROR) {
                return ScanResult.Failure(
                    script.label + " OCR model is still preparing; try again shortly",
                )
            }
        }
        val detail = failure?.message?.takeIf { it.isNotBlank() }
            ?: failure?.javaClass?.simpleName
            ?: "missing task failure cause"
        return ScanResult.Failure("Text recognition failed: $detail")
    }
}
