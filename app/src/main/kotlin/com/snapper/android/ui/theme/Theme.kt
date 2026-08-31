package com.snapper.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class SnapperStatusColors(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarningContainer: Color,
    val warningContainer: Color,
)

private val lightStatusColors = SnapperStatusColors(
    success = Color(0xFF2E7D32),
    onSuccessContainer = Color(0xFF1B5E20),
    successContainer = Color(0xFFDCEDC8),
    warning = Color(0xFFB26A00),
    onWarningContainer = Color(0xFF7A4F01),
    warningContainer = Color(0xFFFFECB3),
)

private val darkStatusColors = SnapperStatusColors(
    success = Color(0xFF81C784),
    onSuccessContainer = Color(0xFFC8E6C9),
    successContainer = Color(0xFF2E4B2F),
    warning = Color(0xFFFFB74D),
    onWarningContainer = Color(0xFFFFE0B2),
    warningContainer = Color(0xFF4E3A12),
)

val LocalSnapperStatusColors = staticCompositionLocalOf<SnapperStatusColors> {
    error("Snapper status colors were not provided")
}

@Composable
fun SnapperTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = if (dark) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSnapperStatusColors provides if (dark) darkStatusColors else lightStatusColors,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
