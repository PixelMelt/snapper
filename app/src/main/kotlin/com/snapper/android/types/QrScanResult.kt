package com.snapper.android.types

sealed interface QrScanResult {
    data class Success(val text: String) : QrScanResult

    data class Failure(val reason: Reason, val message: String) : QrScanResult

    enum class Reason {
        NO_QR_FOUND,
        DECODE_ERROR,
    }
}
