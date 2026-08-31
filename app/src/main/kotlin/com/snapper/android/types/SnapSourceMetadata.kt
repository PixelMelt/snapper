package com.snapper.android.types

import android.content.Context
import android.content.pm.PackageManager

class SnapSourceMetadata private constructor(
    internal val packageName: String,
    val appLabel: String,
    val capturedAtMillis: Long,
) {
    internal val sourceKey: String
        get() = if (packageName.isNotEmpty()) packageName else FALLBACK_SOURCE_KEY

    override fun equals(other: Any?): Boolean {
        return this === other || other is SnapSourceMetadata &&
            capturedAtMillis == other.capturedAtMillis &&
            packageName == other.packageName &&
            appLabel == other.appLabel
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + appLabel.hashCode()
        result = 31 * result + capturedAtMillis.hashCode()
        return result
    }

    companion object {
        private const val FALLBACK_SOURCE_KEY = "@snapper"
        private const val FALLBACK_LABEL = "Snapper"
        private const val MAX_PACKAGE_LENGTH = 255
        private const val MAX_LABEL_LENGTH = 256

        fun forPackage(
            context: Context,
            packageName: String?,
            capturedAtMillis: Long,
        ): SnapSourceMetadata {
            val normalizedPackage = normalizedPackage(packageName)
            if (normalizedPackage.isEmpty()) {
                return fallback(capturedAtMillis)
            }

            val label = try {
                val manager = context.applicationContext.packageManager
                val info = manager.getApplicationInfo(normalizedPackage, 0)
                normalizedLabel(manager.getApplicationLabel(info).toString())
                    .ifEmpty { normalizedPackage }
            } catch (ignored: PackageManager.NameNotFoundException) {
                normalizedPackage
            }
            return SnapSourceMetadata(
                normalizedPackage, label, validatedTimestamp(capturedAtMillis),
            )
        }

        internal fun fromStored(
            packageName: String,
            appLabel: String,
            capturedAtMillis: Long,
        ): SnapSourceMetadata {
            val isFallback = packageName.isEmpty() && appLabel == FALLBACK_LABEL
            val isSource = packageName.isNotEmpty() &&
                normalizedPackage(packageName) == packageName &&
                normalizedLabel(appLabel) == appLabel
            require(isFallback || isSource)
            return SnapSourceMetadata(
                packageName, appLabel, validatedTimestamp(capturedAtMillis),
            )
        }

        fun fallback(capturedAtMillis: Long): SnapSourceMetadata =
            SnapSourceMetadata("", FALLBACK_LABEL, validatedTimestamp(capturedAtMillis))

        internal fun isValidPackageName(value: String?): Boolean {
            if (value.isNullOrEmpty() || value.length > MAX_PACKAGE_LENGTH) {
                return false
            }
            var sawDot = false
            var segmentStart = true
            for (character in value) {
                if (character == '.') {
                    if (segmentStart) {
                        return false
                    }
                    sawDot = true
                    segmentStart = true
                    continue
                }
                if (!(Character.isLetterOrDigit(character) || character == '_')) {
                    return false
                }
                if (segmentStart && Character.isDigit(character)) {
                    return false
                }
                segmentStart = false
            }
            return sawDot && !segmentStart
        }

        private fun normalizedPackage(value: String?): String {
            if (value == null) {
                return ""
            }
            val normalized = value.trim()
            return if (isValidPackageName(normalized)) normalized else ""
        }

        private fun normalizedLabel(value: String?): String {
            if (value == null) {
                return ""
            }
            val normalized = value.trim()
            if (normalized.isEmpty() || normalized.length > MAX_LABEL_LENGTH) {
                return ""
            }
            for (character in normalized) {
                if (Character.isISOControl(character)) {
                    return ""
                }
            }
            return normalized
        }

        private fun validatedTimestamp(value: Long): Long {
            require(value >= 0L)
            return value
        }
    }
}
