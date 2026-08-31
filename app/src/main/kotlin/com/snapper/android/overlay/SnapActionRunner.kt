package com.snapper.android.overlay

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.snapper.android.R
import com.snapper.android.actions.SnapperActionRegistry
import com.snapper.android.scan.ImgurUploader
import com.snapper.android.scan.OcrScanner
import com.snapper.android.scan.QrDelivery
import com.snapper.android.scan.QrScanner
import com.snapper.android.scan.deliverQrText
import com.snapper.android.settings.SnapSettings
import com.snapper.android.storage.snapShareChooser
import com.snapper.android.types.OcrLanguage
import com.snapper.android.types.OcrScript
import com.snapper.android.types.OcrSelection
import com.snapper.android.types.QrScanResult
import com.snapper.android.types.SelectionAction
import java.io.File
import java.io.IOException

internal class SnapActionRunner(private val service: OverlayService) {
    private val work get() = service.work
    private val repository get() = service.repository
    private var ocrScriptChooser: OcrScriptChooserView? = null

    val ocrChooserShowing: Boolean
        get() = ocrScriptChooser != null

    fun performCropAction(file: File, action: SelectionCaptureAction) {
        when (action) {
            is SelectionCaptureAction.Ocr -> scanOcr(file, action.script, false)
            is SelectionCaptureAction.Standard -> when (action.code) {
                SelectionAction.COPY -> copy(file, R.string.feedback_selection_copied)
                SelectionAction.SAVE -> save(file)
                SelectionAction.SHARE -> share(file)
                SelectionAction.QR -> scanQr(file, true)
                SelectionAction.IMGUR -> uploadImgur(file)
                SelectionAction.URL_SCHEME -> runUrlScheme(file)
                else -> {
                    val provider = SnapperActionRegistry.actionForSelectionCode(action.code)
                    if (provider != null && provider.isExternal) {
                        launchExternal(file, provider, SnapperActionRegistry.ExternalSource.CROP)
                    }
                }
            }
        }
    }

    fun copy(
        file: File,
        successMessageResource: Int,
        completion: () -> Unit = {},
        failure: () -> Unit = {},
    ) {
        val generation = work.generation
        work.execute {
            try {
                val staged = repository.stageClipboard(file)
                work.postToMain(generation, {
                    clipboard().setPrimaryClip(
                        ClipData.newUri(service.contentResolver, "Snapper screenshot", repository.uriFor(staged)),
                    )
                    service.feedback(service.getString(successMessageResource))
                    completion()
                })
            } catch (error: IOException) {
                work.postToMain(generation, {
                    service.toast("Could not copy the snap")
                    failure()
                })
            }
        }
    }

    fun copyFullScreenshot(file: File) {
        clipboard().setPrimaryClip(
            ClipData.newUri(service.contentResolver, "Snapper full screenshot", repository.uriFor(file)),
        )
        service.feedback(service.getString(R.string.feedback_screenshot_clipboard))
    }

    fun save(file: File, completion: () -> Unit = {}, failure: () -> Unit = {}) {
        val generation = work.generation
        work.execute {
            try {
                repository.copyToGallery(file)
                work.postToMain(generation, {
                    service.feedback("Saved to Pictures/Snapper")
                    completion()
                })
            } catch (error: IOException) {
                work.postToMain(generation, {
                    service.toast("Save failed: ${error.message}")
                    failure()
                })
            }
        }
    }

    fun share(file: File) {
        service.startActivity(
            snapShareChooser(repository.uriFor(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun scanQr(file: File, fromCrop: Boolean, completion: () -> Unit = {}, failure: () -> Unit = {}) {
        val generation = work.generation
        work.execute {
            val result = QrScanner.scanFile(file)
            work.postToMain(generation, {
                if (handleQrResult(result, fromCrop)) completion() else failure()
            })
        }
    }

    private fun handleQrResult(result: QrScanResult, fromCrop: Boolean): Boolean {
        val text = when (result) {
            is QrScanResult.Failure -> {
                service.toast(result.message)
                return false
            }
            is QrScanResult.Success -> result.text
        }
        return when (deliverQrText(service, text)) {
            QrDelivery.OPENED_LINK -> {
                service.feedback(
                    service.getString(
                        if (fromCrop) R.string.feedback_qr_url_opening_crop
                        else R.string.feedback_qr_url_opening_pin,
                    ),
                )
                true
            }
            QrDelivery.COPIED_LINK_WITHOUT_BROWSER -> {
                service.feedback("No browser found; QR link copied")
                true
            }
            QrDelivery.COPIED_TEXT -> {
                service.feedback(service.getString(R.string.feedback_qr_text_copied))
                true
            }
            QrDelivery.EMPTY -> {
                service.toast("The QR code contains no text")
                false
            }
        }
    }

    fun scanOcr(
        file: File,
        script: OcrScript,
        allowAutomaticUrl: Boolean,
        completion: () -> Unit = {},
        failure: () -> Unit = {},
    ) {
        val generation = work.generation
        work.execute {
            val bitmap = repository.decode(file)
            if (bitmap == null) {
                work.postToMain(generation, {
                    service.toast("Snap could not be decoded for OCR")
                    failure()
                })
                return@execute
            }
            val result = try {
                OcrScanner.scan(bitmap, script)
            } finally {
                bitmap.recycle()
            }
            work.postToMain(generation, {
                handleOcrResult(result, script, allowAutomaticUrl, completion, failure)
            })
        }
    }

    private fun handleOcrResult(
        result: OcrScanner.ScanResult,
        script: OcrScript,
        allowAutomaticUrl: Boolean,
        completion: () -> Unit,
        failure: () -> Unit,
    ) {
        val text = when (result) {
            is OcrScanner.ScanResult.Failure -> {
                Log.i(OCR_LOG_TAG, "script=${script.label} status=FAILURE characters=0")
                service.toast(result.message)
                failure()
                return
            }
            is OcrScanner.ScanResult.Success -> {
                Log.i(OCR_LOG_TAG, "script=${script.label} status=SUCCESS characters=${result.text.length}")
                result.text.trim()
            }
        }
        clipboard().setPrimaryClip(ClipData.newPlainText("Recognized text", text))
        Log.i(OCR_LOG_TAG, "clipboardSetCharacters=${text.length}")

        val openUri = if (allowAutomaticUrl) OcrScanner.asValidWebUri(text) else null
        if (openUri == null) {
            service.feedback(service.getString(R.string.feedback_ocr_copied))
            completion()
            return
        }
        service.feedback("Recognized link copied; opening shortly")
        work.postDelayed(2_500L) {
            try {
                service.startActivity(browseIntent(openUri))
                completion()
            } catch (noHandler: ActivityNotFoundException) {
                service.toast("No app could open the recognized link; text remains copied")
                failure()
            }
        }
    }

    fun uploadImgur(file: File, completion: () -> Unit = {}, failure: () -> Unit = {}) {
        val clientId = SnapSettings.imgurClientId(service)
        val legacyKey = SnapSettings.imgurClientSecret(service)
        if (clientId.isBlank()) {
            service.toast("Set an Imgur Client ID in Snapper Settings first")
            failure()
            return
        }
        service.feedback("Uploading to Imgur…")
        val generation = work.generation
        repository.reserveLivePin(file.name)
        work.execute {
            try {
                val bitmap = repository.decode(file)
                if (bitmap == null) {
                    work.postToMain(generation, {
                        service.toast("Snap could not be decoded for Imgur")
                        failure()
                    })
                    return@execute
                }
                val result = try {
                    ImgurUploader.upload(bitmap, clientId, legacyKey)
                } finally {
                    bitmap.recycle()
                }
                work.postToMain(generation, {
                    when (result) {
                        is ImgurUploader.UploadResult.Failure -> {
                            service.toast(result.message)
                            failure()
                        }
                        is ImgurUploader.UploadResult.Success -> {
                            clipboard().setPrimaryClip(ClipData.newPlainText("Imgur link", result.link))
                            service.feedback(service.getString(R.string.feedback_upload_link_copied))
                            completion()
                        }
                    }
                })
            } finally {
                repository.releaseLivePin(file.name)
            }
        }
    }

    fun runUrlScheme(file: File, completion: () -> Unit = {}, failure: () -> Unit = {}) {
        val destination = Uri.parse(SnapSettings.urlSchemeForPlugin(service))
        if (destination.scheme.isNullOrEmpty()) {
            service.toast("Set a valid outbound URL in Snapper Settings first")
            failure()
            return
        }
        val generation = work.generation
        repository.reserveLivePin(file.name)
        work.execute {
            try {
                repository.copyToGallery(file)
                work.postToMain(generation, {
                    work.postDelayed(1_000L) {
                        try {
                            service.startActivity(browseIntent(destination))
                            completion()
                        } catch (noHandler: ActivityNotFoundException) {
                            service.toast("No app could open the configured URL; the image was saved")
                            failure()
                        }
                    }
                })
            } catch (error: IOException) {
                work.postToMain(generation, {
                    service.toast("Could not save the snap before opening its URL")
                    failure()
                })
            } finally {
                repository.releaseLivePin(file.name)
            }
        }
    }

    fun launchExternal(
        file: File,
        provider: SnapperActionRegistry.Action,
        source: SnapperActionRegistry.ExternalSource,
        completion: () -> Unit = {},
        failure: () -> Unit = {},
    ) {
        val providerTitle = provider.title(service)
        if (!SnapperActionRegistry.isExternalProviderAvailable(service, provider)) {
            service.toast("“$providerTitle” is no longer available")
            failure()
            return
        }
        val launch = SnapperActionRegistry.externalActionIntent(provider, repository.uriFor(file), source)
        try {
            service.startActivity(launch)
        } catch (unavailable: ActivityNotFoundException) {
            service.toast("Could not open “$providerTitle”; the snap was kept")
            failure()
            return
        } catch (denied: SecurityException) {
            service.toast("Could not open “$providerTitle”; the snap was kept")
            failure()
            return
        }
        service.feedback("Opened $providerTitle")
        completion()
    }

    fun requestOcrScript(onSelected: (OcrScript) -> Unit, onCancelled: () -> Unit = {}) {
        when (val selection = SnapSettings.ocrSelection(service)) {
            OcrSelection.AskEveryTime -> showOcrScriptChooser(onSelected, onCancelled)
            is OcrSelection.Chosen -> onSelected(selection.language.script)
        }
    }

    private fun showOcrScriptChooser(onSelected: (OcrScript) -> Unit, onCancelled: () -> Unit) {
        if (ocrScriptChooser != null) {
            service.toast("Choose or cancel the current OCR language first")
            return
        }
        val generation = work.generation
        lateinit var chooser: OcrScriptChooserView
        chooser = OcrScriptChooserView(
            service,
            object : OcrScriptChooserView.Listener {
                override fun onLanguageSelected(language: OcrLanguage) {
                    if (!dismissOcrChooser(chooser)) return
                    if (work.isCurrent(generation)) onSelected(language.script)
                }

                override fun onCancelled() {
                    if (!dismissOcrChooser(chooser)) return
                    if (work.isCurrent(generation)) onCancelled()
                }
            },
        )
        val params = service.overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        if (!service.attachOverlay(chooser, params)) {
            service.toast(
                if (Settings.canDrawOverlays(service)) "Could not show the OCR language chooser"
                else "‘Display over other apps’ permission was removed",
            )
            onCancelled()
            return
        }
        ocrScriptChooser = chooser
    }

    fun dismissOcrChooser(expected: OcrScriptChooserView? = null): Boolean {
        val chooser = ocrScriptChooser ?: return false
        if (expected != null && chooser !== expected) {
            return false
        }
        ocrScriptChooser = null
        service.windows.removeViewImmediate(chooser)
        return true
    }

    private fun clipboard(): ClipboardManager = service.getSystemService(ClipboardManager::class.java)
}

private const val OCR_LOG_TAG = "SnapperOCR"

internal fun browseIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri)
    .addCategory(Intent.CATEGORY_BROWSABLE)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
