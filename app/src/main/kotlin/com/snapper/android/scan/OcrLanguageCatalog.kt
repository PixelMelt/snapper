package com.snapper.android.scan

import com.snapper.android.types.OcrLanguage
import com.snapper.android.types.OcrScript

object OcrLanguageCatalog {
    val supportedLanguages = listOf(
        OcrLanguage("en", "English", OcrScript.LATIN),
        OcrLanguage("af", "Afrikaans", OcrScript.LATIN),
        OcrLanguage("sq", "Albanian", OcrScript.LATIN),
        OcrLanguage("ca", "Catalan", OcrScript.LATIN),
        OcrLanguage("zh", "Chinese", OcrScript.CHINESE),
        OcrLanguage("hr", "Croatian", OcrScript.LATIN),
        OcrLanguage("cs", "Czech", OcrScript.LATIN),
        OcrLanguage("da", "Danish", OcrScript.LATIN),
        OcrLanguage("nl", "Dutch", OcrScript.LATIN),
        OcrLanguage("et", "Estonian", OcrScript.LATIN),
        OcrLanguage("fil", "Filipino", OcrScript.LATIN),
        OcrLanguage("fi", "Finnish", OcrScript.LATIN),
        OcrLanguage("fr", "French", OcrScript.LATIN),
        OcrLanguage("de", "German", OcrScript.LATIN),
        OcrLanguage("hi", "Hindi", OcrScript.DEVANAGARI),
        OcrLanguage("hu", "Hungarian", OcrScript.LATIN),
        OcrLanguage("is", "Icelandic", OcrScript.LATIN),
        OcrLanguage("id", "Indonesian", OcrScript.LATIN),
        OcrLanguage("it", "Italian", OcrScript.LATIN),
        OcrLanguage("ja", "Japanese", OcrScript.JAPANESE),
        OcrLanguage("ko", "Korean", OcrScript.KOREAN),
        OcrLanguage("lv", "Latvian", OcrScript.LATIN),
        OcrLanguage("lt", "Lithuanian", OcrScript.LATIN),
        OcrLanguage("ms", "Malay", OcrScript.LATIN),
        OcrLanguage("mr", "Marathi", OcrScript.DEVANAGARI),
        OcrLanguage("ne", "Nepali", OcrScript.DEVANAGARI),
        OcrLanguage("no", "Norwegian", OcrScript.LATIN),
        OcrLanguage("pl", "Polish", OcrScript.LATIN),
        OcrLanguage("pt", "Portuguese", OcrScript.LATIN),
        OcrLanguage("ro", "Romanian", OcrScript.LATIN),
        OcrLanguage("sr-Latn", "Serbian (Latin)", OcrScript.LATIN),
        OcrLanguage("sk", "Slovak", OcrScript.LATIN),
        OcrLanguage("sl", "Slovenian", OcrScript.LATIN),
        OcrLanguage("es", "Spanish", OcrScript.LATIN),
        OcrLanguage("sv", "Swedish", OcrScript.LATIN),
        OcrLanguage("tr", "Turkish", OcrScript.LATIN),
        OcrLanguage("vi", "Vietnamese", OcrScript.LATIN),
    )

    val defaultLanguage: OcrLanguage = supportedLanguages.first()

    fun forCode(code: String): OcrLanguage? =
        supportedLanguages.firstOrNull { it.code == code }
}
