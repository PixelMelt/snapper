package com.snapper.android.scan

import android.graphics.Bitmap
import android.util.Base64
import android.util.Base64OutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONException
import org.json.JSONObject

object ImgurUploader {
    private const val UPLOAD_ENDPOINT = "https://api.imgur.com/3/image"
    private val imageField = "image=".toByteArray(StandardCharsets.US_ASCII)
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val STREAM_BUFFER_BYTES = 32 * 1024
    private const val MAX_RESPONSE_BYTES = 1024 * 1024

    sealed interface UploadResult {
        data class Success(val link: String) : UploadResult

        data class Failure(val message: String) : UploadResult
    }

    private enum class NetworkStage {
        CONNECTING,
        UPLOADING,
        READING,
    }

    fun upload(bitmap: Bitmap, clientId: String, mashapeKey: String? = null): UploadResult {
        val normalizedClientId = normalizeHeaderValue(clientId)
            ?: return failure("Imgur Client ID is missing or invalid")
        val normalizedMashapeKey = if (mashapeKey.isNullOrBlank()) {
            null
        } else {
            normalizeHeaderValue(mashapeKey)
                ?: return failure("Legacy Imgur API key is invalid")
        }

        var connection: HttpURLConnection? = null
        var stage = NetworkStage.CONNECTING
        try {
            connection = URL(UPLOAD_ENDPOINT).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setChunkedStreamingMode(STREAM_BUFFER_BYTES)
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Client-ID $normalizedClientId")
            if (normalizedMashapeKey != null) {
                connection.setRequestProperty("X-Mashape-Key", normalizedMashapeKey)
            }

            var encoded: Boolean
            val socket = connection.outputStream
            stage = NetworkStage.UPLOADING
            BufferedOutputStream(socket, STREAM_BUFFER_BYTES).use { network ->
                network.write(imageField)
                FormPercentEncodingOutputStream(network).use { percentEncoded ->
                    Base64OutputStream(percentEncoded, Base64.NO_WRAP).use { base64 ->
                        encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, base64)
                    }
                }
            }
            if (!encoded) {
                return failure("Image could not be JPEG encoded")
            }

            stage = NetworkStage.READING
            val httpStatus = connection.responseCode
            val responseStream = if (httpStatus in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response: ByteArray = try {
                readResponse(responseStream)
            } catch (tooLarge: ResponseTooLargeException) {
                return failure("Imgur returned an unexpectedly large response")
            }

            if (httpStatus !in 200..299) {
                return failure(httpFailureMessage(httpStatus))
            }

            val root: JSONObject = try {
                JSONObject(String(response, StandardCharsets.UTF_8))
            } catch (malformed: JSONException) {
                return failure("Imgur returned malformed JSON")
            }

            val succeeded = try {
                root.get("success")
            } catch (malformed: JSONException) {
                return failure("Imgur response did not include a valid success flag")
            }
            if (succeeded !is Boolean) {
                return failure("Imgur response did not include a valid success flag")
            }
            if (!succeeded) {
                return failure("Imgur rejected the upload")
            }

            val linkValue = try {
                root.getJSONObject("data").get("link")
            } catch (malformed: JSONException) {
                return failure("Imgur response did not include an image link")
            }
            if (linkValue !is String) {
                return failure("Imgur response did not include a valid image link")
            }
            val link = linkValue.trim()
            if (link.isEmpty()) {
                return failure("Imgur response included an empty image link")
            }
            if (!isValidImageLink(link)) {
                return failure("Imgur response included an invalid image link")
            }
            return UploadResult.Success(link)
        } catch (timeout: SocketTimeoutException) {
            return failure(timeoutMessage(stage))
        } catch (networkFailure: IOException) {
            return failure(networkFailureMessage(networkFailure))
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalizeHeaderValue(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.any { Character.isISOControl(it) }) {
            return null
        }
        return normalized
    }

    private fun readResponse(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        BufferedInputStream(stream).use { input ->
            ByteArrayOutputStream(4096).use { output ->
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw ResponseTooLargeException()
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        }
    }

    private fun timeoutMessage(stage: NetworkStage): String {
        return when (stage) {
            NetworkStage.CONNECTING -> "Timed out connecting to Imgur"
            NetworkStage.UPLOADING -> "Timed out uploading to Imgur"
            NetworkStage.READING -> "Timed out waiting for Imgur"
        }
    }

    private fun httpFailureMessage(httpStatus: Int): String {
        return if (httpStatus > 0) {
            "Imgur request failed (HTTP $httpStatus)"
        } else {
            "Imgur returned an invalid response"
        }
    }

    private fun isValidImageLink(link: String): Boolean {
        val parsed = try {
            URL(link)
        } catch (malformed: MalformedURLException) {
            return false
        }
        return parsed.host.isNotEmpty() &&
            (parsed.protocol == "https" || parsed.protocol == "http")
    }

    private fun networkFailureMessage(failure: Throwable): String {
        val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
        return "Could not reach Imgur: $detail"
    }

    private fun failure(message: String): UploadResult = UploadResult.Failure(message)

    private class FormPercentEncodingOutputStream(output: OutputStream) :
        FilterOutputStream(output) {
        override fun write(value: Int) {
            val octet = value and 0xff
            if ((octet >= 'a'.code && octet <= 'z'.code) ||
                (octet >= 'A'.code && octet <= 'Z'.code) ||
                (octet >= '0'.code && octet <= '9'.code) || octet == '-'.code ||
                octet == '_'.code || octet == '.'.code || octet == '*'.code
            ) {
                out.write(octet)
            } else {
                out.write('%'.code)
                out.write(hex[octet ushr 4].toInt())
                out.write(hex[octet and 0x0f].toInt())
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            for (index in offset until offset + length) {
                write(buffer[index].toInt())
            }
        }

        companion object {
            private val hex = "0123456789ABCDEF".toByteArray(StandardCharsets.US_ASCII)
        }
    }

    private class ResponseTooLargeException : Exception()
}
