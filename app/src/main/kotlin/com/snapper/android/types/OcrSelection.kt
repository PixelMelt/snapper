package com.snapper.android.types

sealed interface OcrSelection {
    data object AskEveryTime : OcrSelection

    data class Chosen internal constructor(val language: OcrLanguage) : OcrSelection
}
