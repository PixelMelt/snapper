package com.snapper.android.settings

import android.content.Context
import android.content.SharedPreferences
import com.snapper.android.scan.OcrLanguageCatalog
import com.snapper.android.types.OcrSelection
import com.snapper.android.types.SnapperIpcContract
import java.util.Locale

object SnapSettings {
    enum class Setting {
        KEEP_SELECTION,
        HISTORY_LIMIT,
        FAST_TAP_MENU,
        DOUBLE_TAP_ACTION,
        TRIPLE_TAP_ACTION,
        SHADOW_COLOR,
        REDUCE_ANIMATIONS,
        COLOR_SCHEME,
        TOAST_MESSAGES,
        ROOT_VERIFIED,
        HARDWARE_MODE,
        SCREENSHOT_DELAY_MS,
        SCREENSHOT_TO_CLIPBOARD,
        OCR_LANGUAGE,
        IMGUR_CLIENT_ID,
        IMGUR_CLIENT_SECRET,
        URL_SCHEME_FOR_PLUGIN,
        ANONYMOUS_APP_NAME,
    }

    private const val PREFS_NAME = "settings"
    private const val KEY_KEEP_SELECTION = "keep_selection"
    private const val KEY_HISTORY_LIMIT = "history_limit"
    private const val KEY_FAST_TAP_MENU = "fast_tap_menu"
    private const val KEY_DOUBLE_TAP_ACTION = "double_tap_action"
    private const val KEY_TRIPLE_TAP_ACTION = "triple_tap_action"
    private const val KEY_SHADOW_COLOR = "shadow_color"
    private const val KEY_REDUCE_ANIMATIONS = "reduce_animations"
    private const val KEY_COLOR_SCHEME = "colorScheme"
    private const val KEY_TOAST_MESSAGES = "toast_messages"
    private const val KEY_ROOT_VERIFIED = "root_verified"
    private const val KEY_HARDWARE_MODE = SnapperIpcContract.HARDWARE_SETTINGS_RESULT_MODE
    private const val KEY_SCREENSHOT_DELAY_MS =
        SnapperIpcContract.HARDWARE_SETTINGS_RESULT_SCREENSHOT_DELAY_MS
    private const val KEY_SCREENSHOT_TO_CLIPBOARD =
        "screenshot_goes_to_clipboard_straight_away"
    private const val KEY_OCR_LANGUAGE = "ocr_language"
    private const val OCR_ASK_EVERY_TIME = "ask_everytime"
    private const val KEY_IMGUR_CLIENT_ID = "imgurClientID"
    private const val KEY_IMGUR_CLIENT_SECRET = "imgurClientSecret"
    private const val KEY_URL_SCHEME_FOR_PLUGIN = "urlSchemeForPlugin"
    private const val KEY_ENABLE_ANONYMOUS_APP_NAME = "enableAnonymousAppName"

    private const val DEFAULT_KEEP_SELECTION = false
    private const val DEFAULT_HISTORY_LIMIT = 10
    private const val DEFAULT_FAST_TAP_MENU = true
    private const val DEFAULT_DOUBLE_TAP_ACTION = 1
    private const val DEFAULT_TRIPLE_TAP_ACTION = 2
    private const val DEFAULT_SHADOW_COLOR = 0
    private const val DEFAULT_REDUCE_ANIMATIONS = false
    const val DEFAULT_COLOR_SCHEME = "000000"
    private const val DEFAULT_TOAST_MESSAGES = true
    private const val DEFAULT_SCREENSHOT_TO_CLIPBOARD = false
    private const val DEFAULT_ENABLE_ANONYMOUS_APP_NAME = false

    const val HARDWARE_MODE_NATIVE = SnapperIpcContract.HARDWARE_MODE_NATIVE
    const val HARDWARE_MODE_NORMAL = SnapperIpcContract.HARDWARE_MODE_NORMAL
    const val HARDWARE_MODE_INSTANT = SnapperIpcContract.HARDWARE_MODE_INSTANT
    const val HARDWARE_MODE_FREEZE = SnapperIpcContract.HARDWARE_MODE_FREEZE
    private const val DEFAULT_HARDWARE_MODE = HARDWARE_MODE_NORMAL
    const val DEFAULT_SCREENSHOT_DELAY_MS = SnapperIpcContract.DEFAULT_SCREENSHOT_DELAY_MS

    const val HISTORY_KEEP_LATEST = 1
    const val HISTORY_UNLIMITED = -1

    const val TAP_ACTION_OFF = 0
    const val TAP_ACTION_DISMISS = 1
    const val TAP_ACTION_TOGGLE_SHADOW = 2

    const val SHADOW_BLACK = 0
    const val SHADOW_WHITE = 1
    const val SHADOW_NONE = 2

    fun keepSelection(context: Context): Boolean =
        preferences(context).getBoolean(KEY_KEEP_SELECTION, DEFAULT_KEEP_SELECTION)

    fun setKeepSelection(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(KEY_KEEP_SELECTION, value).apply()
    }

    fun historyLimit(context: Context): Int =
        preferences(context).getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)

    fun setHistoryLimit(context: Context, value: Int) {
        require(value == HISTORY_UNLIMITED || value >= HISTORY_KEEP_LATEST)
        preferences(context).edit().putInt(KEY_HISTORY_LIMIT, value).apply()
    }

    fun fastTapMenu(context: Context): Boolean =
        preferences(context).getBoolean(KEY_FAST_TAP_MENU, DEFAULT_FAST_TAP_MENU)

    fun setFastTapMenu(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(KEY_FAST_TAP_MENU, value).apply()
    }

    fun doubleTapAction(context: Context): Int =
        preferences(context).getInt(KEY_DOUBLE_TAP_ACTION, DEFAULT_DOUBLE_TAP_ACTION)

    fun setDoubleTapAction(context: Context, value: Int) {
        require(value in TAP_ACTION_OFF..TAP_ACTION_TOGGLE_SHADOW)
        preferences(context).edit().putInt(KEY_DOUBLE_TAP_ACTION, value).apply()
    }

    fun tripleTapAction(context: Context): Int =
        preferences(context).getInt(KEY_TRIPLE_TAP_ACTION, DEFAULT_TRIPLE_TAP_ACTION)

    fun setTripleTapAction(context: Context, value: Int) {
        require(value in TAP_ACTION_OFF..TAP_ACTION_TOGGLE_SHADOW)
        preferences(context).edit().putInt(KEY_TRIPLE_TAP_ACTION, value).apply()
    }

    fun shadowColor(context: Context): Int =
        preferences(context).getInt(KEY_SHADOW_COLOR, DEFAULT_SHADOW_COLOR)

    fun setShadowColor(context: Context, value: Int) {
        require(value in SHADOW_BLACK..SHADOW_NONE)
        preferences(context).edit().putInt(KEY_SHADOW_COLOR, value).apply()
    }

    fun reduceAnimations(context: Context): Boolean =
        preferences(context).getBoolean(KEY_REDUCE_ANIMATIONS, DEFAULT_REDUCE_ANIMATIONS)

    fun setReduceAnimations(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(KEY_REDUCE_ANIMATIONS, value).apply()
    }

    fun colorScheme(context: Context): String =
        checkNotNull(preferences(context).getString(KEY_COLOR_SCHEME, DEFAULT_COLOR_SCHEME))

    fun colorSchemeColor(context: Context): Int = -0x1000000 or colorScheme(context).toInt(16)

    fun setColorScheme(context: Context, value: String) {
        require(value.length == 6 && value.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' })
        preferences(context).edit()
            .putString(KEY_COLOR_SCHEME, value.uppercase(Locale.ROOT))
            .apply()
    }

    fun toastMessages(context: Context): Boolean =
        preferences(context).getBoolean(KEY_TOAST_MESSAGES, DEFAULT_TOAST_MESSAGES)

    fun setToastMessages(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(KEY_TOAST_MESSAGES, value).apply()
    }

    fun rootVerified(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ROOT_VERIFIED, false)

    fun setRootVerified(context: Context, verified: Boolean) {
        preferences(context).edit().putBoolean(KEY_ROOT_VERIFIED, verified).apply()
    }

    fun hardwareMode(context: Context): Int =
        preferences(context).getInt(KEY_HARDWARE_MODE, DEFAULT_HARDWARE_MODE)

    fun setHardwareMode(context: Context, value: Int) {
        require(value in HARDWARE_MODE_NATIVE..HARDWARE_MODE_FREEZE)
        preferences(context).edit().putInt(KEY_HARDWARE_MODE, value).apply()
    }

    fun screenshotDelayMs(context: Context): Int =
        preferences(context).getInt(KEY_SCREENSHOT_DELAY_MS, DEFAULT_SCREENSHOT_DELAY_MS)

    fun setScreenshotDelayMs(context: Context, value: Int) {
        require(value in 0..1_000)
        preferences(context).edit().putInt(KEY_SCREENSHOT_DELAY_MS, value).apply()
    }

    fun screenshotToClipboard(context: Context): Boolean =
        preferences(context).getBoolean(
            KEY_SCREENSHOT_TO_CLIPBOARD,
            DEFAULT_SCREENSHOT_TO_CLIPBOARD,
        )

    fun setScreenshotToClipboard(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(KEY_SCREENSHOT_TO_CLIPBOARD, value).apply()
    }

    fun ocrSelection(context: Context): OcrSelection {
        val stored = checkNotNull(
            preferences(context).getString(
                KEY_OCR_LANGUAGE,
                OcrLanguageCatalog.defaultLanguage.code,
            ),
        )
        return if (stored == OCR_ASK_EVERY_TIME) {
            OcrSelection.AskEveryTime
        } else {
            OcrSelection.Chosen(checkNotNull(OcrLanguageCatalog.forCode(stored)) {
                "Unsupported stored OCR language: $stored"
            })
        }
    }

    fun setOcrSelection(context: Context, selection: OcrSelection) {
        val stored = when (selection) {
            OcrSelection.AskEveryTime -> OCR_ASK_EVERY_TIME
            is OcrSelection.Chosen -> {
                require(OcrLanguageCatalog.forCode(selection.language.code) === selection.language)
                selection.language.code
            }
        }
        preferences(context).edit().putString(KEY_OCR_LANGUAGE, stored).apply()
    }

    fun imgurClientId(context: Context): String =
        checkNotNull(preferences(context).getString(KEY_IMGUR_CLIENT_ID, ""))

    fun setImgurClientId(context: Context, value: String) {
        preferences(context).edit().putString(KEY_IMGUR_CLIENT_ID, value).apply()
    }

    fun imgurClientSecret(context: Context): String =
        checkNotNull(preferences(context).getString(KEY_IMGUR_CLIENT_SECRET, ""))

    fun setImgurClientSecret(context: Context, value: String) {
        preferences(context).edit().putString(KEY_IMGUR_CLIENT_SECRET, value).apply()
    }

    fun urlSchemeForPlugin(context: Context): String =
        checkNotNull(preferences(context).getString(KEY_URL_SCHEME_FOR_PLUGIN, ""))

    fun setUrlSchemeForPlugin(context: Context, value: String) {
        preferences(context).edit().putString(KEY_URL_SCHEME_FOR_PLUGIN, value).apply()
    }

    fun anonymousAppNameEnabled(context: Context): Boolean =
        preferences(context).getBoolean(
            KEY_ENABLE_ANONYMOUS_APP_NAME,
            DEFAULT_ENABLE_ANONYMOUS_APP_NAME,
        )

    fun setAnonymousAppNameEnabled(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLE_ANONYMOUS_APP_NAME, value).apply()
    }

    fun observe(context: Context, setting: Setting, onChange: () -> Unit): AutoCloseable {
        val preferences = preferences(context)
        val key = key(setting)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                onChange()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(setting: Setting): String = when (setting) {
        Setting.KEEP_SELECTION -> KEY_KEEP_SELECTION
        Setting.HISTORY_LIMIT -> KEY_HISTORY_LIMIT
        Setting.FAST_TAP_MENU -> KEY_FAST_TAP_MENU
        Setting.DOUBLE_TAP_ACTION -> KEY_DOUBLE_TAP_ACTION
        Setting.TRIPLE_TAP_ACTION -> KEY_TRIPLE_TAP_ACTION
        Setting.SHADOW_COLOR -> KEY_SHADOW_COLOR
        Setting.REDUCE_ANIMATIONS -> KEY_REDUCE_ANIMATIONS
        Setting.COLOR_SCHEME -> KEY_COLOR_SCHEME
        Setting.TOAST_MESSAGES -> KEY_TOAST_MESSAGES
        Setting.ROOT_VERIFIED -> KEY_ROOT_VERIFIED
        Setting.HARDWARE_MODE -> KEY_HARDWARE_MODE
        Setting.SCREENSHOT_DELAY_MS -> KEY_SCREENSHOT_DELAY_MS
        Setting.SCREENSHOT_TO_CLIPBOARD -> KEY_SCREENSHOT_TO_CLIPBOARD
        Setting.OCR_LANGUAGE -> KEY_OCR_LANGUAGE
        Setting.IMGUR_CLIENT_ID -> KEY_IMGUR_CLIENT_ID
        Setting.IMGUR_CLIENT_SECRET -> KEY_IMGUR_CLIENT_SECRET
        Setting.URL_SCHEME_FOR_PLUGIN -> KEY_URL_SCHEME_FOR_PLUGIN
        Setting.ANONYMOUS_APP_NAME -> KEY_ENABLE_ANONYMOUS_APP_NAME
    }
}
