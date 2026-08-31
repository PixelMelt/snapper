package com.snapper.android.types

class OcrLanguage internal constructor(
    val code: String,
    val displayName: String,
    val script: OcrScript,
) {
    val modelDetail: String
        get() = "$code · ${script.label} model"
}
