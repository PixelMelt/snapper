package com.snapper.android.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.snapper.android.R
import com.snapper.android.scan.OcrLanguageCatalog
import com.snapper.android.types.OcrLanguage

internal class OcrScriptChooserView(
    context: Context,
    private val listener: Listener,
) : FrameLayout(context) {
    interface Listener {
        fun onLanguageSelected(language: OcrLanguage)

        fun onCancelled()
    }

    private val rippleColor = context.getColor(R.color.snapper_ripple)
    private val surfaceColor = context.getColor(R.color.snapper_chooser_surface)
    private val borderColor = context.getColor(R.color.snapper_chooser_border)
    private val primaryTextColor = context.getColor(R.color.snapper_chooser_text_primary)
    private val secondaryTextColor = context.getColor(R.color.snapper_chooser_text_secondary)
    private val dividerColor = context.getColor(R.color.snapper_chooser_divider)
    private val accentColor = context.getColor(R.color.snapper_chooser_accent)
    private val scrimColor = context.getColor(R.color.snapper_chooser_scrim)
    private var completed = false

    init {
        setBackgroundColor(scrimColor)
        isClickable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        scroll.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        val viewport = FrameLayout(context)
        viewport.setOnClickListener { cancel() }
        scroll.addView(
            viewport,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(8f), dp(10f), dp(8f), dp(8f))
        card.background = roundRect(surfaceColor, 20f, borderColor)
        card.isClickable = true

        val title = text("OCR language", 20f, primaryTextColor, Typeface.BOLD)
        title.setPadding(dp(12f), dp(8f), dp(12f), 0)
        card.addView(title)
        val detail = text(
            "Choose the language shown in this image. Recognition stays on-device.",
            13f, secondaryTextColor, Typeface.NORMAL,
        )
        detail.setPadding(dp(12f), dp(5f), dp(12f), dp(10f))
        card.addView(detail)

        val languages = OcrLanguageCatalog.supportedLanguages
        for (index in languages.indices) {
            val language = languages[index]
            if (index > 0) card.addView(divider())
            card.addView(languageRow(language))
        }
        card.addView(divider())

        val cancel = text("Cancel", 15f, accentColor, Typeface.BOLD)
        cancel.gravity = Gravity.CENTER
        cancel.background = ripple(Color.TRANSPARENT, 13f, Color.TRANSPARENT)
        cancel.setOnClickListener { cancel() }
        cancel.contentDescription = "Cancel OCR language selection"
        card.addView(
            cancel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)),
        )

        val cardParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        viewport.addView(card, cardParams)

        val scrollParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        scrollParams.setMargins(dp(20f), dp(20f), dp(20f), dp(20f))
        addView(scroll, scrollParams)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            requestFocus()
            announceForAccessibility("Choose OCR language")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) cancel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun languageRow(language: OcrLanguage): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.VERTICAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        row.minimumHeight = dp(58f)
        row.background = ripple(Color.TRANSPARENT, 13f, Color.TRANSPARENT)

        val title = text(
            language.displayName, 15f, primaryTextColor, Typeface.BOLD,
        )
        row.addView(title)
        val detail = text(
            language.modelDetail, 12f, secondaryTextColor, Typeface.NORMAL,
        )
        detail.setPadding(0, dp(2f), 0, 0)
        row.addView(detail)

        row.contentDescription = language.displayName + ". " + language.modelDetail
        row.setOnClickListener { select(language) }
        return row
    }

    private fun select(language: OcrLanguage) {
        if (completed) return
        completed = true
        listener.onLanguageSelected(language)
    }

    private fun cancel() {
        if (completed) return
        completed = true
        listener.onCancelled()
    }

    private fun divider(): View {
        val divider = View(context)
        divider.setBackgroundColor(dividerColor)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        params.leftMargin = dp(12f)
        params.rightMargin = dp(12f)
        divider.layoutParams = params
        return divider
    }

    private fun text(value: String, sp: Float, color: Int, style: Int): TextView {
        val view = TextView(context)
        view.text = value
        view.textSize = sp
        view.setTextColor(color)
        view.typeface = Typeface.create("sans-serif", style)
        return view
    }

    private fun roundRect(fill: Int, radiusDp: Float, stroke: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(radiusDp).toFloat()
        drawable.setColor(fill)
        if (Color.alpha(stroke) != 0) drawable.setStroke(dp(1f), stroke)
        return drawable
    }

    private fun ripple(fill: Int, radiusDp: Float, stroke: Int): RippleDrawable {
        val content = roundRect(fill, radiusDp, stroke)
        val mask = roundRect(Color.WHITE, radiusDp, Color.TRANSPARENT)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun dp(value: Float): Int = Math.round(value * resources.displayMetrics.density)
}
