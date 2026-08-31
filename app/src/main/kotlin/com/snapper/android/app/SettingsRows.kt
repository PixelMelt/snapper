package com.snapper.android.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.snapper.android.R
import com.snapper.android.scan.OcrLanguageCatalog
import com.snapper.android.settings.SnapSettings
import com.snapper.android.types.OcrSelection
import com.snapper.android.ui.ChoiceRow
import com.snapper.android.ui.PreferenceRowText
import com.snapper.android.ui.RadioListDialog
import com.snapper.android.ui.ToggleRow
import java.util.Locale

internal class IntChoice(
    val title: String,
    val detail: String?,
    val setting: SnapSettings.Setting,
    val read: (Context) -> Int,
    val write: (Context, Int) -> Unit,
    val values: IntArray,
    val labels: List<String>,
    val afterWrite: () -> Unit = {},
) {
    init {
        require(values.size == labels.size) { "Choice values and labels must have equal sizes" }
    }

    fun indexOf(value: Int): Int {
        val index = values.indexOf(value)
        check(index >= 0) { "Unsupported stored choice value: $value" }
        return index
    }
}

@Composable
internal fun IntChoiceRow(choice: IntChoice, onOpen: (IntChoice) -> Unit) {
    val context = LocalContext.current
    val stored by rememberSetting(context, choice.setting) { choice.read(context) }
    ChoiceRow(
        title = choice.title,
        detail = choice.detail,
        valueLabel = choice.labels[choice.indexOf(stored)],
        onClick = { onOpen(choice) },
    )
}

@Composable
internal fun IntChoiceDialog(choice: IntChoice, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val stored = remember(choice) { choice.read(context) }
    RadioListDialog(
        title = choice.title,
        labels = choice.labels,
        selectedIndex = choice.indexOf(stored),
        onSelect = { index ->
            choice.write(context, choice.values[index])
            choice.afterWrite()
            onDismiss()
        },
        onDismiss = onDismiss,
        cancelLabel = stringResource(R.string.common_cancel),
    )
}

@Composable
internal fun BooleanRow(
    title: String,
    detail: String?,
    setting: SnapSettings.Setting,
    read: (Context) -> Boolean,
    write: (Context, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var checked by rememberSetting(context, setting) { read(context) }
    ToggleRow(title = title, detail = detail, checked = checked) { value ->
        checked = value
        write(context, value)
    }
}

@Composable
internal fun TextInputRow(
    title: String,
    detail: String? = null,
    setting: SnapSettings.Setting,
    read: (Context) -> String,
    write: (Context, String) -> Unit,
    secret: Boolean,
    uri: Boolean,
) {
    val context = LocalContext.current
    var value by rememberSetting(context, setting) { read(context) }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        PreferenceRowText(title, detail)
        OutlinedTextField(
            value = value,
            onValueChange = { updated ->
                value = updated
                write(context, updated)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            singleLine = true,
            placeholder = { Text(if (uri) "scheme://destination" else "Not set") },
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    secret -> KeyboardType.Password
                    uri -> KeyboardType.Uri
                    else -> KeyboardType.Text
                },
            ),
            shape = RoundedCornerShape(10.dp),
        )
    }
}

@Composable
internal fun ColorSchemeRow() {
    val context = LocalContext.current
    val initialValue = remember { SnapSettings.colorScheme(context) }
    var value by remember { mutableStateOf(initialValue) }
    var valid by remember { mutableStateOf(true) }
    var savedValue by remember { mutableStateOf(initialValue) }

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        PreferenceRowText(
            stringResource(R.string.control_tint_title),
            stringResource(R.string.control_tint_summary),
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { raw ->
                    val filtered = raw.uppercase(Locale.ROOT)
                        .filter { it.isDigit() || it in 'A'..'F' }
                        .take(6)
                    value = filtered
                    valid = filtered.length == 6
                    if (valid) {
                        SnapSettings.setColorScheme(context, filtered)
                        savedValue = filtered
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { state ->
                        if (!state.isFocused && value.length != 6) {
                            value = savedValue
                            valid = true
                        }
                    }
                    .semantics {
                        contentDescription = if (valid) {
                            "Control tint, #$value"
                        } else {
                            "Control tint. Enter exactly six hexadecimal digits"
                        }
                    },
                singleLine = true,
                prefix = { Text("#") },
                placeholder = { Text(SnapSettings.DEFAULT_COLOR_SCHEME) },
                isError = !valid,
                supportingText = {
                    Text(if (valid) "Saved as #$savedValue" else "Enter all 6 hexadecimal digits")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(10.dp),
            )
            val swatchValue = if (valid && value.length == 6) value else savedValue
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(40.dp)
                    .background(Color(0xFF000000.toInt() or swatchValue.toInt(16)), RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "#$swatchValue control tint preview" },
            )
        }
    }
}

@Composable
internal fun OcrLanguageRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val current by rememberSetting(context, SnapSettings.Setting.OCR_LANGUAGE) {
        SnapSettings.ocrSelection(context)
    }
    ChoiceRow(
        title = stringResource(R.string.ocr_language_title),
        detail = null,
        valueLabel = ocrLanguageLabel(context, current),
        onClick = onClick,
    )
}

@Composable
internal fun OcrLanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val languages = OcrLanguageCatalog.supportedLanguages
    val selections = remember { listOf(OcrSelection.AskEveryTime) + languages.map(OcrSelection::Chosen) }
    val labels = listOf(stringResource(R.string.ocr_ask_every_time)) + languages.map { it.displayName }
    val current = remember { SnapSettings.ocrSelection(context) }
    val selectedIndex = selections.indexOf(current)
    check(selectedIndex >= 0)
    RadioListDialog(
        title = stringResource(R.string.ocr_language_title),
        labels = labels,
        selectedIndex = selectedIndex,
        onSelect = { index ->
            SnapSettings.setOcrSelection(context, selections[index])
            onDismiss()
        },
        onDismiss = onDismiss,
        cancelLabel = stringResource(R.string.common_cancel),
    )
}

private fun ocrLanguageLabel(context: Context, selection: OcrSelection): String = when (selection) {
    OcrSelection.AskEveryTime -> context.getString(R.string.ocr_ask_every_time)
    is OcrSelection.Chosen -> selection.language.displayName
}

@Composable
private fun <T> rememberSetting(
    context: Context,
    setting: SnapSettings.Setting,
    read: () -> T,
): MutableState<T> {
    val currentRead by rememberUpdatedState(read)
    val state = remember(context, setting) { mutableStateOf(read()) }
    DisposableEffect(context, setting) {
        val observation = SnapSettings.observe(context, setting) {
            state.value = currentRead()
        }
        onDispose { observation.close() }
    }
    return state
}
