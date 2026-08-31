package com.snapper.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snapper.android.R
import com.snapper.android.actions.SnapperActionRegistry
import com.snapper.android.settings.SnapSettings
import com.snapper.android.storage.SnapRepository
import com.snapper.android.ui.CardDivider
import com.snapper.android.ui.ScreenHeader
import com.snapper.android.ui.SectionHeader
import com.snapper.android.ui.SettingsCard
import com.snapper.android.ui.theme.SnapperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SnapperActionRegistry.refreshExternalActions(this)
        setContent {
            SnapperTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

private val HISTORY_VALUES = intArrayOf(
    SnapSettings.HISTORY_KEEP_LATEST, 10, 20, 50, SnapSettings.HISTORY_UNLIMITED,
)
private val TAP_VALUES = intArrayOf(
    SnapSettings.TAP_ACTION_OFF,
    SnapSettings.TAP_ACTION_DISMISS,
    SnapSettings.TAP_ACTION_TOGGLE_SHADOW,
)
private val SHADOW_VALUES = intArrayOf(
    SnapSettings.SHADOW_BLACK, SnapSettings.SHADOW_WHITE, SnapSettings.SHADOW_NONE,
)
private val HARDWARE_MODE_VALUES = intArrayOf(
    SnapSettings.HARDWARE_MODE_NATIVE,
    SnapSettings.HARDWARE_MODE_NORMAL,
    SnapSettings.HARDWARE_MODE_INSTANT,
    SnapSettings.HARDWARE_MODE_FREEZE,
)
private val HARDWARE_MODE_LABELS = listOf("Native", "Normal", "Instant", "Freeze")
private val SCREENSHOT_DELAY_VALUES = intArrayOf(0, 50, 100, 150, 250, 500, 750, 1_000)

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openChoice by remember { mutableStateOf<IntChoice?>(null) }
    var showOcrDialog by remember { mutableStateOf(false) }
    val openChoiceDialog: (IntChoice) -> Unit = { openChoice = it }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 40.dp),
        ) {
            ScreenHeader(title = stringResource(R.string.settings_title), onBack = onBack)

            SectionHeader(title = "Capture")
            SettingsCard {
                IntChoiceRow(
                    IntChoice(
                        "Screenshot buttons",
                        "Power + Volume Down",
                        SnapSettings.Setting.HARDWARE_MODE,
                        SnapSettings::hardwareMode,
                        SnapSettings::setHardwareMode,
                        HARDWARE_MODE_VALUES,
                        HARDWARE_MODE_LABELS,
                    ),
                    openChoiceDialog,
                )
                CardDivider()
                IntChoiceRow(
                    IntChoice(
                        stringResource(R.string.screenshot_delay_title),
                        stringResource(R.string.screenshot_delay_summary),
                        SnapSettings.Setting.SCREENSHOT_DELAY_MS,
                        SnapSettings::screenshotDelayMs,
                        SnapSettings::setScreenshotDelayMs,
                        SCREENSHOT_DELAY_VALUES,
                        listOf(
                            stringResource(R.string.choice_no_delay), "50 ms", "100 ms",
                            "150 ms", "250 ms", "500 ms", "750 ms", "1 second",
                        ),
                    ),
                    openChoiceDialog,
                )
                CardDivider()
                BooleanRow(
                    stringResource(R.string.screenshot_clipboard_title),
                    "Make the camera action copy the full screen instead of opening Android's screenshot UI",
                    SnapSettings.Setting.SCREENSHOT_TO_CLIPBOARD,
                    SnapSettings::screenshotToClipboard,
                    SnapSettings::setScreenshotToClipboard,
                )
                CardDivider()
                BooleanRow(
                    stringResource(R.string.keep_selection_title),
                    null,
                    SnapSettings.Setting.KEEP_SELECTION,
                    SnapSettings::keepSelection,
                    SnapSettings::setKeepSelection,
                )
            }

            SectionHeader(title = stringResource(R.string.settings_section_actions))
            SettingsCard { ActionOrderList() }

            SectionHeader(title = stringResource(R.string.ocr_settings_title))
            SettingsCard { OcrLanguageRow(onClick = { showOcrDialog = true }) }

            SectionHeader(title = stringResource(R.string.imgur_settings_title))
            SettingsCard {
                TextInputRow(
                    title = "Client ID",
                    setting = SnapSettings.Setting.IMGUR_CLIENT_ID,
                    read = SnapSettings::imgurClientId,
                    write = SnapSettings::setImgurClientId,
                    secret = false,
                    uri = false,
                )
                CardDivider()
                TextInputRow(
                    title = "Legacy API key",
                    detail = "X-Mashape-Key",
                    setting = SnapSettings.Setting.IMGUR_CLIENT_SECRET,
                    read = SnapSettings::imgurClientSecret,
                    write = SnapSettings::setImgurClientSecret,
                    secret = true,
                    uri = false,
                )
            }

            SectionHeader(title = "URL scheme")
            SettingsCard {
                TextInputRow(
                    title = "Outbound URL",
                    detail = "After saving to Pictures/Snapper, wait one second and open this raw URL",
                    setting = SnapSettings.Setting.URL_SCHEME_FOR_PLUGIN,
                    read = SnapSettings::urlSchemeForPlugin,
                    write = SnapSettings::setUrlSchemeForPlugin,
                    secret = false,
                    uri = true,
                )
            }

            SectionHeader(title = stringResource(R.string.history_title))
            SettingsCard {
                IntChoiceRow(
                    IntChoice(
                        stringResource(R.string.history_limit_title),
                        null,
                        SnapSettings.Setting.HISTORY_LIMIT,
                        SnapSettings::historyLimit,
                        SnapSettings::setHistoryLimit,
                        HISTORY_VALUES,
                        listOf(
                            "Keep latest only", "10 captures", "20 captures", "50 captures",
                            stringResource(R.string.choice_unlimited),
                        ),
                        afterWrite = { scope.launch(Dispatchers.IO) { SnapRepository(context).prune() } },
                    ),
                    openChoiceDialog,
                )
                CardDivider()
                BooleanRow(
                    "Anonymous source",
                    "Do not record the source app for new captures",
                    SnapSettings.Setting.ANONYMOUS_APP_NAME,
                    SnapSettings::anonymousAppNameEnabled,
                    SnapSettings::setAnonymousAppNameEnabled,
                )
            }

            SectionHeader(title = "Pin gestures")
            SettingsCard {
                BooleanRow(
                    "Fast tap menu",
                    "On: tap for instant actions. Off: long press for actions and enable tap shortcuts",
                    SnapSettings.Setting.FAST_TAP_MENU,
                    SnapSettings::fastTapMenu,
                    SnapSettings::setFastTapMenu,
                )
                CardDivider()
                val tapLabels = listOf(
                    stringResource(R.string.state_off),
                    stringResource(R.string.tap_action_dismiss),
                    stringResource(R.string.tap_action_toggle_shadow),
                )
                IntChoiceRow(
                    IntChoice(
                        stringResource(R.string.double_tap_title),
                        "Shortcut used when Fast tap menu is off",
                        SnapSettings.Setting.DOUBLE_TAP_ACTION,
                        SnapSettings::doubleTapAction,
                        SnapSettings::setDoubleTapAction,
                        TAP_VALUES,
                        tapLabels,
                    ),
                    openChoiceDialog,
                )
                CardDivider()
                IntChoiceRow(
                    IntChoice(
                        stringResource(R.string.triple_tap_title),
                        "Shortcut used when Fast tap menu is off",
                        SnapSettings.Setting.TRIPLE_TAP_ACTION,
                        SnapSettings::tripleTapAction,
                        SnapSettings::setTripleTapAction,
                        TAP_VALUES,
                        tapLabels,
                    ),
                    openChoiceDialog,
                )
            }

            SectionHeader(title = "Appearance")
            SettingsCard {
                IntChoiceRow(
                    IntChoice(
                        stringResource(R.string.shadow_title),
                        null,
                        SnapSettings.Setting.SHADOW_COLOR,
                        SnapSettings::shadowColor,
                        SnapSettings::setShadowColor,
                        SHADOW_VALUES,
                        listOf(
                            stringResource(R.string.shadow_black),
                            stringResource(R.string.shadow_white),
                            stringResource(R.string.shadow_none),
                        ),
                    ),
                    openChoiceDialog,
                )
                CardDivider()
                ColorSchemeRow()
                CardDivider()
                BooleanRow(
                    stringResource(R.string.reduce_animations_title),
                    null,
                    SnapSettings.Setting.REDUCE_ANIMATIONS,
                    SnapSettings::reduceAnimations,
                    SnapSettings::setReduceAnimations,
                )
            }

            SectionHeader(title = "Feedback")
            SettingsCard {
                BooleanRow(
                    stringResource(R.string.feedback_title),
                    null,
                    SnapSettings.Setting.TOAST_MESSAGES,
                    SnapSettings::toastMessages,
                    SnapSettings::setToastMessages,
                )
            }
        }
    }

    openChoice?.let { choice ->
        IntChoiceDialog(choice, onDismiss = { openChoice = null })
    }

    if (showOcrDialog) {
        OcrLanguageDialog(onDismiss = { showOcrDialog = false })
    }
}
