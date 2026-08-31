package com.snapper.android.capture

import android.content.Context
import com.snapper.android.types.SnapSourceMetadata

internal object ForegroundAppDetector {
    fun fromWindowDump(
        context: Context,
        output: String,
        capturedAtMillis: Long,
    ): SnapSourceMetadata {
        val packageName = parseFocusedPackage(output, context.packageName)
        return SnapSourceMetadata.forPackage(context, packageName, capturedAtMillis)
    }

    private fun parseFocusedPackage(output: String, ownPackage: String): String? {
        var focusedApp: String? = null
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("mCurrentFocus=")) {
                val candidate = packageFromWindowLine(line)
                if (acceptable(candidate, ownPackage)) {
                    return candidate
                }
            } else if (focusedApp == null && line.startsWith("mFocusedApp=")) {
                val candidate = packageFromWindowLine(line)
                if (acceptable(candidate, ownPackage)) {
                    focusedApp = candidate
                }
            }
        }
        return focusedApp
    }

    private fun packageFromWindowLine(line: String): String? {
        var userMarker = line.indexOf(" u")
        while (userMarker >= 0) {
            var cursor = userMarker + 2
            while (cursor < line.length && Character.isDigit(line[cursor])) {
                cursor++
            }
            if (cursor < line.length && line[cursor] == ' ') {
                val tokenStart = cursor + 1
                val slash = line.indexOf('/', tokenStart)
                if (slash > tokenStart) {
                    val candidate = line.substring(tokenStart, slash)
                    return candidate.takeIf { SnapSourceMetadata.isValidPackageName(it) }
                }
            }
            userMarker = line.indexOf(" u", userMarker + 2)
        }
        return null
    }

    private fun acceptable(candidate: String?, ownPackage: String): Boolean {
        return candidate != null &&
            candidate != ownPackage &&
            candidate != "com.android.systemui"
    }
}
