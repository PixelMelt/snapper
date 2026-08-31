package com.snapper.fixture

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import java.security.MessageDigest
import java.util.Locale

class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = inspect(intent)
        val status = TextView(this)
        status.text = result
        status.contentDescription = result
        status.setTextColor(if (result.startsWith("PASS")) Color.rgb(0, 95, 45) else Color.RED)
        status.textSize = 18f
        status.gravity = Gravity.CENTER
        val padding = Math.round(24f * resources.displayMetrics.density)
        status.setPadding(padding, padding, padding, padding)
        setContentView(status)
        Log.i(TAG, result.replace('\n', ' '))
    }

    private fun inspect(intent: Intent): String {
        val data = intent.data
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        val clip = intent.clipData
        val clipped = if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).uri
        val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        val source = intent.getStringExtra(EXTRA_SOURCE)
        val version = intent.getIntExtra(EXTRA_VERSION, -1)
        val envelopeValid = intent.action == ACTION_PROCESS_IMAGE &&
            intent.type == "image/png" &&
            version == 1 &&
            providerId != null && providerId.startsWith("com.snapper.fixture.") &&
            (source == "crop" || source == "pin") &&
            data != null && data == stream && data == clipped &&
            (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        if (!envelopeValid) {
            return String.format(
                Locale.ROOT,
                "FAIL\nenvelope action=%s type=%s version=%d provider=%s source=%s" +
                    " data=%s stream=%s clip=%s flags=0x%x",
                intent.action, intent.type, version, providerId, source,
                data, stream, clipped, intent.flags,
            )
        }

        return try {
            val input = contentResolver.openInputStream(data!!)
                ?: return "FAIL\nContentResolver returned no stream"
            input.use {
                val digest = MessageDigest.getInstance("SHA-256")
                val first = ByteArray(PNG_SIGNATURE.size)
                var firstLength = 0
                while (firstLength < first.size) {
                    val count = it.read(first, firstLength, first.size - firstLength)
                    if (count < 0) break
                    firstLength += count
                }
                digest.update(first, 0, firstLength)
                var total = firstLength.toLong()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    total += count
                }
                val png = firstLength == first.size && first.contentEquals(PNG_SIGNATURE)
                if (!png || total <= PNG_SIGNATURE.size) {
                    return "FAIL\nread grant worked, but payload was not a non-empty PNG"
                }
                String.format(
                    Locale.ROOT,
                    "PASS\nprovider=%s\nsource=%s\nversion=%d\nbytes=%d\nsha256=%s",
                    providerId, source, version, total, hex(digest.digest()),
                )
            }
        } catch (failure: Exception) {
            "FAIL\nread grant: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    private companion object {
        const val TAG = "SnapperProviderFixture"
        const val ACTION_PROCESS_IMAGE = "com.snapper.android.action.PROCESS_IMAGE"
        const val EXTRA_VERSION = "com.snapper.android.extra.ACTION_CONTRACT_VERSION"
        const val EXTRA_PROVIDER_ID = "com.snapper.android.extra.ACTION_PROVIDER_ID"
        const val EXTRA_SOURCE = "com.snapper.android.extra.ACTION_SOURCE"
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
