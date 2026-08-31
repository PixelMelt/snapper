package com.snapper.android.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import com.snapper.android.types.SnapSourceMetadata
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object RootCapture {
    private val sessionLock = Any()
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    private const val MAX_PNG_BYTES = 96 * 1024 * 1024
    private const val MAX_METADATA_BYTES = 64 * 1024
    private const val MAX_COMMAND_OUTPUT_BYTES = 64 * 1024
    private const val CAPTURE_TIMEOUT_SECONDS = 12L
    private const val ROOT_CHECK_TIMEOUT_SECONDS = 8L
    private const val NATIVE_SCREENSHOT_TIMEOUT_SECONDS = 3L
    private var session: RootSession? = null

    sealed interface Result {
        data class Success(
            val bitmap: Bitmap,
            val rotation: Int = Surface.ROTATION_0,
            val sourceMetadata: SnapSourceMetadata? = null,
        ) : Result

        data class Failure(val message: String) : Result
    }

    private class CapturePayload(val png: ByteArray, val diagnostics: String)

    private class CommandPayload(val resultCode: Int, val output: String)

    fun capture(cancelled: () -> Boolean): Result = capture(null, cancelled)

    fun captureWithAttribution(context: Context, cancelled: () -> Boolean): Result =
        capture(context.applicationContext, cancelled)

    private fun capture(attributionContext: Context?, cancelled: () -> Boolean): Result {
        if (cancelled()) {
            return Result.Failure("Root screenshot cancelled")
        }
        synchronized(sessionLock) {
            try {
                val capturedAtMillis = System.currentTimeMillis()
                val payload = rootSession().capture(attributionContext != null, cancelled)
                if (payload.png.isEmpty()) {
                    return Result.Failure(
                        captureMessage(
                            "Root returned no PNG", payload.diagnostics,
                            attributionContext != null,
                        ),
                    )
                }
                if (!hasPngSignature(payload.png)) {
                    return Result.Failure(
                        captureMessage(
                            "Root returned a malformed PNG", payload.diagnostics,
                            attributionContext != null,
                        ),
                    )
                }
                val bitmap = BitmapFactory.decodeByteArray(payload.png, 0, payload.png.size)
                    ?: return Result.Failure("Android could not decode the root screenshot")
                val sourceMetadata = if (attributionContext == null) {
                    null
                } else {
                    ForegroundAppDetector.fromWindowDump(
                        attributionContext, payload.diagnostics, capturedAtMillis,
                    )
                }
                return Result.Success(bitmap, sourceMetadata = sourceMetadata)
            } catch (cancelledCapture: CaptureCancelledException) {
                invalidateSession()
                return Result.Failure("Root screenshot cancelled")
            } catch (timeout: TimeoutException) {
                invalidateSession()
                return Result.Failure("Root screenshot timed out")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                invalidateSession()
                return Result.Failure("Root screenshot cancelled")
            } catch (exhausted: OutOfMemoryError) {
                invalidateSession()
                return Result.Failure("Root screenshot failed: not enough image memory")
            } catch (failure: IOException) {
                invalidateSession()
                return Result.Failure("Root screenshot failed: ${failureDetail(failure)}")
            } catch (failure: SecurityException) {
                invalidateSession()
                return Result.Failure("Root screenshot failed: ${failureDetail(failure)}")
            }
        }
    }

    fun checkRoot(): String? {
        synchronized(sessionLock) {
            try {
                val identity = rootSession().identity()
                if (identity.contains("uid=0")) {
                    return null
                }
                invalidateSession()
                return "Root access was not granted: $identity"
            } catch (timeout: TimeoutException) {
                invalidateSession()
                return "Root request timed out"
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                invalidateSession()
                return "Root request was interrupted"
            } catch (failure: IOException) {
                invalidateSession()
                return "Unable to run su: ${failureDetail(failure)}"
            } catch (failure: SecurityException) {
                invalidateSession()
                return "Unable to run su: ${failureDetail(failure)}"
            }
        }
    }

    fun requestNativeScreenshot(): String? {
        synchronized(sessionLock) {
            try {
                val payload = rootSession().requestNativeScreenshot()
                if (payload.resultCode == 0) {
                    return null
                }
                return payload.output.ifEmpty {
                    "Android rejected the native screenshot request"
                }
            } catch (timeout: TimeoutException) {
                invalidateSession()
                return "Native screenshot request timed out"
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                invalidateSession()
                return "Native screenshot request was interrupted"
            } catch (failure: IOException) {
                invalidateSession()
                return "Could not request a native screenshot: ${failureDetail(failure)}"
            } catch (failure: SecurityException) {
                invalidateSession()
                return "Could not request a native screenshot: ${failureDetail(failure)}"
            }
        }
    }

    private fun rootSession(): RootSession {
        var current = session
        if (current == null || !current.isAlive) {
            invalidateSession()
            current = RootSession()
            session = current
        }
        return current
    }

    private fun invalidateSession() {
        session?.close()
        session = null
    }

    private fun hasPngSignature(png: ByteArray): Boolean =
        png.size >= pngSignature.size && pngSignature.indices.all { png[it] == pngSignature[it] }

    private fun failureDetail(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName

    private fun captureMessage(summary: String, diagnostics: String, attributed: Boolean): String {
        val detail = StringBuilder()
        for (rawLine in diagnostics.lineSequence()) {
            val line = rawLine.trim()
            if (attributed && (
                    line.startsWith("mCurrentFocus=") ||
                        line.startsWith("mFocusedApp=")
                    )
            ) {
                continue
            }
            if (line.isNotEmpty()) {
                if (detail.isNotEmpty()) detail.append('\n')
                detail.append(line)
            }
        }
        return if (detail.isEmpty()) summary else "$summary: $detail"
    }

    private class RootSession {
        companion object {
            private const val CAPTURE_HEADER = "SNAPPER_CAPTURE "
            private const val CAPTURE_END = "SNAPPER_END"
            private const val ROOT_HEADER = "SNAPPER_ROOT "
            private const val NATIVE_SCREENSHOT_HEADER = "SNAPPER_NATIVE "
            private const val NATIVE_SCREENSHOT_END = "SNAPPER_NATIVE_END"
            private const val STALE_WORKSPACE_COMMAND =
                "for snapper_stale in /data/local/tmp/com.snapper.android.capture.*;do " +
                    "[ -d \"\$snapper_stale\" ]||continue;" +
                    "rm -f \"\$snapper_stale/capture.png\"" +
                    " \"\$snapper_stale/metadata.txt\";" +
                    "rmdir \"\$snapper_stale\" 2>/dev/null;done\n"
            private const val CAPTURE_COMMAND_PREFIX =
                "umask 077;" +
                    "snapper_tmp=\$(mktemp -d -p /data/local/tmp" +
                    " com.snapper.android.capture.XXXXXXXXXX 2>/dev/null);" +
                    "if [ -z \"\$snapper_tmp\" ];then " +
                    "snapper_error='Could not create private capture workspace';" +
                    "snapper_error_len=\$(printf '%s' \"\$snapper_error\"|wc -c);" +
                    "printf 'SNAPPER_CAPTURE 1 0 %s\\n' \"\$snapper_error_len\";" +
                    "printf '%s' \"\$snapper_error\";printf 'SNAPPER_END\\n';" +
                    "else " +
                    "snapper_png=\"\$snapper_tmp/capture.png\";" +
                    "snapper_meta=\"\$snapper_tmp/metadata.txt\";" +
                    ": >\"\$snapper_png\";: >\"\$snapper_meta\";" +
                    "trap 'rm -f \"\$snapper_png\" \"\$snapper_meta\";" +
                    " rmdir \"\$snapper_tmp\" 2>/dev/null' EXIT;" +
                    "trap 'exit 1' HUP INT TERM;"
            private const val ATTRIBUTION_COMMAND =
                "dumpsys window 2>/dev/null" +
                    " | grep -E '^[[:space:]]*m(CurrentFocus|FocusedApp)='" +
                    " >\"\$snapper_meta\";"
            private const val SCREENSHOT_COMMAND =
                "screencap -p >\"\$snapper_png\" 2>>\"\$snapper_meta\";" +
                    "snapper_rc=\$?;" +
                    "snapper_png_len=\$(wc -c <\"\$snapper_png\");" +
                    "snapper_meta_len=\$(wc -c <\"\$snapper_meta\");" +
                    "printf 'SNAPPER_CAPTURE %s %s %s\\n'" +
                    " \"\$snapper_rc\" \"\$snapper_png_len\" \"\$snapper_meta_len\";" +
                    "cat \"\$snapper_png\";cat \"\$snapper_meta\";" +
                    "printf 'SNAPPER_END\\n';" +
                    "rm -f \"\$snapper_png\" \"\$snapper_meta\";" +
                    "rmdir \"\$snapper_tmp\" 2>/dev/null;" +
                    "trap - EXIT HUP INT TERM;fi"
            private const val NATIVE_SCREENSHOT_COMMAND =
                "snapper_native_output=\$(input keyevent 120 2>&1);" +
                    "snapper_rc=\$?;" +
                    "snapper_native_len=\$(printf '%s'" +
                    " \"\$snapper_native_output\"|wc -c);" +
                    "printf 'SNAPPER_NATIVE %s %s\\n'" +
                    " \"\$snapper_rc\" \"\$snapper_native_len\";" +
                    "printf '%s' \"\$snapper_native_output\";" +
                    "printf 'SNAPPER_NATIVE_END\\n'"
        }

        private val process: Process = ProcessBuilder("su").start()
        private val output: InputStream = process.inputStream
        private val input: OutputStream = process.outputStream
        private val errors: ErrorPump = ErrorPump(process.errorStream)

        init {
            errors.start()
            write(STALE_WORKSPACE_COMMAND)
        }

        val isAlive: Boolean
            get() = process.isAlive

        fun capture(attributed: Boolean, cancelled: () -> Boolean): CapturePayload {
            val deadline = System.nanoTime() +
                TimeUnit.SECONDS.toNanos(CAPTURE_TIMEOUT_SECONDS)
            val command = CAPTURE_COMMAND_PREFIX +
                (if (attributed) ATTRIBUTION_COMMAND else "") +
                SCREENSHOT_COMMAND + '\n'
            write(command)

            val header = readUntilPrefix(CAPTURE_HEADER, deadline, cancelled)
            val fields = header.substring(CAPTURE_HEADER.length).trim().split(Regex("\\s+"))
            if (fields.size != 3) {
                throw IOException("Invalid root screenshot header")
            }
            val resultCode = parseBoundedLength(fields[0], Int.MAX_VALUE, "result code")
            val pngLength = parseBoundedLength(fields[1], MAX_PNG_BYTES, "PNG length")
            val metadataLength = parseBoundedLength(
                fields[2], MAX_METADATA_BYTES, "metadata length",
            )
            val png = readBytes(pngLength, deadline, cancelled)
            val metadata = readBytes(metadataLength, deadline, cancelled)
            val end = readLine(deadline, cancelled)
            if (CAPTURE_END != end) {
                throw IOException("Root screenshot framing was interrupted")
            }
            val diagnostics = String(metadata, StandardCharsets.UTF_8)
            if (resultCode != 0) {
                throw IOException(captureMessage("screencap failed", diagnostics, attributed))
            }
            return CapturePayload(png, diagnostics)
        }

        fun identity(): String {
            val deadline = System.nanoTime() +
                TimeUnit.SECONDS.toNanos(ROOT_CHECK_TIMEOUT_SECONDS)
            write("printf 'SNAPPER_ROOT ';id\n")
            val identity = readUntilPrefix(ROOT_HEADER, deadline) { false }
                .substring(ROOT_HEADER.length).trim()
            if (identity.isEmpty()) {
                throw IOException("Root shell returned an empty identity")
            }
            return identity
        }

        fun requestNativeScreenshot(): CommandPayload {
            val deadline = System.nanoTime() +
                TimeUnit.SECONDS.toNanos(NATIVE_SCREENSHOT_TIMEOUT_SECONDS)
            write(NATIVE_SCREENSHOT_COMMAND + '\n')

            val header = readUntilPrefix(NATIVE_SCREENSHOT_HEADER, deadline) { false }
            val fields = header.substring(NATIVE_SCREENSHOT_HEADER.length)
                .trim().split(Regex("\\s+"))
            if (fields.size != 2) {
                throw IOException("Invalid native screenshot header")
            }
            val resultCode = parseBoundedLength(
                fields[0], Int.MAX_VALUE, "native screenshot result code",
            )
            val outputLength = parseBoundedLength(
                fields[1], MAX_COMMAND_OUTPUT_BYTES, "native screenshot output length",
            )
            val commandOutput = String(
                readBytes(outputLength, deadline) { false }, StandardCharsets.UTF_8,
            ).trim()
            val end = readLine(deadline) { false }
            if (NATIVE_SCREENSHOT_END != end) {
                throw IOException("Native screenshot framing was interrupted")
            }
            return CommandPayload(resultCode, commandOutput)
        }

        private fun write(command: String) {
            if (!process.isAlive) {
                throw IOException(processFailure())
            }
            input.write(command.toByteArray(StandardCharsets.UTF_8))
            input.flush()
        }

        private fun readUntilPrefix(
            prefix: String,
            deadline: Long,
            cancelled: () -> Boolean,
        ): String {
            repeat(16) {
                val line = readLine(deadline, cancelled)
                if (line.startsWith(prefix)) {
                    return line
                }
                if (line.startsWith("SNAPPER_")) {
                    throw IOException("Unexpected root shell header: $line")
                }
            }
            throw IOException("Unexpected output from root shell")
        }

        private fun readLine(deadline: Long, cancelled: () -> Boolean): String {
            val line = ByteArrayOutputStream(96)
            while (line.size() < 4_096) {
                val value = readByte(deadline, cancelled)
                if (value == '\n'.code) {
                    return line.toString(StandardCharsets.UTF_8).removeSuffix("\r")
                }
                line.write(value)
            }
            throw IOException("Root shell returned an oversized line")
        }

        private fun readBytes(length: Int, deadline: Long, cancelled: () -> Boolean): ByteArray {
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                checkWaitState(deadline, cancelled)
                val available = output.available()
                if (available == 0) {
                    Thread.sleep(8L)
                    continue
                }
                val read = output.read(result, offset, minOf(available, length - offset))
                if (read < 0) {
                    throw IOException(processFailure())
                }
                offset += read
            }
            return result
        }

        private fun readByte(deadline: Long, cancelled: () -> Boolean): Int {
            while (true) {
                checkWaitState(deadline, cancelled)
                if (output.available() > 0) {
                    val value = output.read()
                    if (value >= 0) return value
                    throw IOException(processFailure())
                }
                Thread.sleep(8L)
            }
        }

        private fun checkWaitState(deadline: Long, cancelled: () -> Boolean) {
            if (cancelled()) {
                throw CaptureCancelledException()
            }
            if (System.nanoTime() >= deadline) {
                throw TimeoutException()
            }
            if (!process.isAlive && output.available() == 0) {
                throw IOException(processFailure())
            }
        }

        private fun parseBoundedLength(value: String, maximum: Int, label: String): Int {
            val parsed = value.toLongOrNull()
            if (parsed == null || parsed < 0L || parsed > maximum) {
                throw IOException("Invalid root $label")
            }
            return parsed.toInt()
        }

        private fun processFailure(): String {
            val detail = errors.text.trim()
            return detail.ifEmpty { "Root shell exited" }
        }

        fun close() {
            try {
                input.close()
            } catch (ignored: IOException) {
            }
            if (process.isAlive) {
                process.destroy()
                try {
                    if (!process.waitFor(200L, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    process.destroyForcibly()
                }
            }
            try {
                output.close()
            } catch (ignored: IOException) {
            }
            errors.closeSource()
        }
    }

    private class CaptureCancelledException : Exception()

    private class ErrorPump(private val source: InputStream) : Thread("snapper-root-errors") {
        companion object {
            private const val MAX_ERROR_BYTES = 64 * 1024
        }

        private val sink = ByteArrayOutputStream(256)

        init {
            isDaemon = true
        }

        fun closeSource() {
            try {
                source.close()
            } catch (ignored: IOException) {
            }
        }

        override fun run() {
            val buffer = ByteArray(1_024)
            try {
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) {
                        break
                    }
                    synchronized(sink) {
                        val remaining = MAX_ERROR_BYTES - sink.size()
                        if (remaining > 0) {
                            sink.write(buffer, 0, minOf(read, remaining))
                        }
                    }
                }
            } catch (ignored: IOException) {
            }
        }

        val text: String
            get() = synchronized(sink) { sink.toString(StandardCharsets.UTF_8) }
    }
}
