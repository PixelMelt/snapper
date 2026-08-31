package com.snapper.fixture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.Normalizer
import java.util.Locale

class OcrFixtureActivity : Activity(), ClipboardManager.OnPrimaryClipChangedListener {
    private lateinit var clipboard: ClipboardManager
    private lateinit var status: TextView
    private lateinit var script: String
    private lateinit var expected: String
    private var armed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        script = canonicalScript(intent.getStringExtra("script"))
        expected = targetFor(script)
        clipboard = getSystemService(ClipboardManager::class.java)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setBackgroundColor(Color.rgb(250, 250, 250))
        val padding = dp(28)
        root.setPadding(padding, dp(42), padding, dp(42))

        val heading = text(
            "Snapper OCR fixture · ${displayScript(script)}", 18f, Color.DKGRAY, Typeface.BOLD,
        )
        heading.gravity = Gravity.CENTER
        root.addView(
            heading,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)),
        )

        val target = text(expected, 44f, Color.BLACK, Typeface.NORMAL)
        target.gravity = Gravity.CENTER
        target.maxLines = 2
        target.ellipsize = null
        target.contentDescription = "OCR target: $expected"
        root.addView(
            target,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        status = text(
            "WAITING\nCapture only the large center text, then choose OCR.", 16f,
            Color.rgb(85, 85, 85), Typeface.NORMAL,
        )
        status.gravity = Gravity.CENTER
        status.contentDescription = "OCR fixture waiting"
        root.addView(
            status,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(132)),
        )
        setContentView(root)
    }

    override fun onDestroy() {
        if (armed) {
            clipboard.removePrimaryClipChangedListener(this)
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (intent.getBooleanExtra("clear_clipboard", false)) {
            clipboard.clearPrimaryClip()
            Log.i(TAG, "CLEARED test clipboard")
            finish()
            return
        }
        if (!armed) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Snapper OCR fixture", PENDING_CLIP))
            clipboard.addPrimaryClipChangedListener(this)
            armed = true
            Log.i(TAG, "ARMED script=$script expected=$expected")
            return
        }
        inspectClipboard()
    }

    override fun onPrimaryClipChanged() {
        if (hasWindowFocus()) inspectClipboard()
    }

    private fun inspectClipboard() {
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) return
        val actual = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (actual.isEmpty() || actual == PENDING_CLIP) return
        val passed = normalize(actual).contains(normalize(expected))
        val result = (if (passed) "PASS" else "FAIL") +
            "\nscript=${displayScript(script)}" +
            "\nexpected=$expected" +
            "\nactual=$actual"
        status.text = result
        status.contentDescription = result
        status.setTextColor(if (passed) Color.rgb(0, 105, 55) else Color.rgb(175, 0, 25))
        Log.i(TAG, result.replace('\n', ' '))
    }

    private fun text(value: String, sizeSp: Float, color: Int, style: Int): TextView {
        val view = TextView(this)
        view.text = value
        view.textSize = sizeSp
        view.setTextColor(color)
        view.setTypeface(Typeface.DEFAULT, style)
        view.includeFontPadding = true
        return view
    }

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private companion object {
        const val TAG = "SnapperOcrFixture"
        const val PENDING_CLIP = "SNAPPER_OCR_FIXTURE_PENDING"

        fun canonicalScript(value: String?): String =
            when (val lower = value?.lowercase(Locale.ROOT)) {
                "chinese", "devanagari", "japanese", "korean" -> lower
                else -> "latin"
            }

        fun displayScript(value: String): String =
            value.replaceFirstChar { it.uppercaseChar() }

        fun targetFor(value: String): String = when (value) {
            "chinese" -> "中国汉字 4827"
            "devanagari" -> "नमस्ते भारत 4827"
            "japanese" -> "日本語テスト 4827"
            "korean" -> "한국어 테스트 4827"
            else -> "SNAPPER TEST 4827"
        }

        fun normalize(value: String): String {
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            val compact = StringBuilder(normalized.length)
            normalized.codePoints()
                .filter { codePoint ->
                    Character.isLetterOrDigit(codePoint) ||
                        Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() ||
                        Character.getType(codePoint) == Character.COMBINING_SPACING_MARK.toInt()
                }
                .forEach { compact.appendCodePoint(it) }
            return compact.toString()
        }
    }
}
