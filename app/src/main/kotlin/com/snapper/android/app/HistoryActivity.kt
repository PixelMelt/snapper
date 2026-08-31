package com.snapper.android.app

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.snapper.android.R
import com.snapper.android.overlay.SnapActions
import com.snapper.android.scan.QrDelivery
import com.snapper.android.scan.QrScanner
import com.snapper.android.scan.deliverQrText
import com.snapper.android.storage.SnapRepository
import com.snapper.android.storage.snapShareChooser
import com.snapper.android.types.QrScanResult
import com.snapper.android.types.SnapSourceMetadata
import com.snapper.android.ui.RadioListDialog
import com.snapper.android.ui.theme.SnapperTheme
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SnapperTheme {
                HistoryScreen(
                    onClose = { finish() },
                    onPinned = {
                        moveTaskToBack(true)
                        finish()
                    },
                )
            }
        }
    }
}

internal data class HistoryCapture(val file: File, val source: SnapSourceMetadata)

private data class SourceGroup(val key: String, val label: String, val count: Int)

private data class HistorySnapshot(
    val captures: List<HistoryCapture>,
    val sourceGroups: List<SourceGroup>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(onClose: () -> Unit, onPinned: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SnapRepository(context) }
    val dateFormat = remember { SimpleDateFormat("dd MMMM yyyy, H:mm:ss", Locale.getDefault()) }

    var snapshot by remember { mutableStateOf<HistorySnapshot?>(null) }
    var selectedSourceKey by remember { mutableStateOf<String?>(null) }
    var mutationInFlight by remember { mutableStateOf(false) }
    var showSourceChooser by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<File?>(null) }
    var deleteConfirmFor by remember { mutableStateOf<File?>(null) }

    fun humanDate(capture: HistoryCapture): String {
        val timestamp = capture.source.capturedAtMillis.takeIf { it > 0L } ?: capture.file.lastModified()
        return dateFormat.format(Date(timestamp))
    }

    fun applySnapshot(loaded: HistorySnapshot) {
        snapshot = loaded
        if (selectedSourceKey != null && loaded.sourceGroups.none { it.key == selectedSourceKey }) {
            selectedSourceKey = null
        }
    }

    fun refresh() {
        scope.launch { applySnapshot(withContext(Dispatchers.IO) { loadSnapshot(repository) }) }
    }

    fun deleteCapture(file: File) {
        if (mutationInFlight) {
            return
        }
        mutationInFlight = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.delete(file) }
            mutationInFlight = false
            refresh()
            val message = when (outcome) {
                SnapRepository.DeleteOutcome.DELETED -> "Capture deleted"
                SnapRepository.DeleteOutcome.LIVE_PIN -> "Close this pin before deleting its capture"
                SnapRepository.DeleteOutcome.FAILED -> "Couldn't delete capture"
            }
            val length = if (outcome == SnapRepository.DeleteOutcome.DELETED) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            Toast.makeText(context, message, length).show()
        }
    }

    fun clearHistory() {
        if (mutationInFlight) {
            return
        }
        mutationInFlight = true
        val expected = requireNotNull(snapshot).captures.size
        scope.launch {
            val result = withContext(Dispatchers.IO) { repository.deleteAll() }
            val refreshed = withContext(Dispatchers.IO) { loadSnapshot(repository) }
            mutationInFlight = false
            applySnapshot(refreshed)
            showClearHistoryResult(context, expected, refreshed.captures.isEmpty(), result)
        }
    }

    fun pin(file: File) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Allow Snapper to display over other apps first", Toast.LENGTH_LONG).show()
            return
        }
        SnapActions.openFile(context, file.absolutePath)
        onPinned()
    }

    LaunchedEffect(Unit) { refresh() }

    val allCaptures = snapshot?.captures.orEmpty()
    val sourceGroups = snapshot?.sourceGroups.orEmpty()
    val selectedGroup = sourceGroups.firstOrNull { it.key == selectedSourceKey }
    val captures = if (selectedSourceKey == null) {
        allCaptures
    } else {
        allCaptures.filter { it.source.sourceKey == selectedSourceKey }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close history" },
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showClearConfirm = true },
                        enabled = allCaptures.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = "Clear history" },
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_tile_close),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(
                                if (allCaptures.isEmpty()) {
                                    MaterialTheme.colorScheme.outlineVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedSourceKey == null,
                    onClick = { selectedSourceKey = null },
                    enabled = allCaptures.isNotEmpty(),
                    label = { Text(stringResource(R.string.history_filter_all)) },
                )
                FilterChip(
                    selected = selectedSourceKey != null,
                    onClick = { showSourceChooser = true },
                    enabled = sourceGroups.isNotEmpty(),
                    label = { Text(selectedGroup?.label ?: "Apps") },
                    modifier = Modifier.semantics {
                        contentDescription = if (selectedGroup == null) {
                            "Filter captures by source app"
                        } else {
                            "Showing captures from ${selectedGroup.label}. Tap to choose another app."
                        }
                    },
                )
            }

            if (captures.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selectedGroup == null) "No captures" else "No captures from ${selectedGroup.label}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(captures, key = { it.file.absolutePath }) { capture ->
                        HistoryItem(
                            file = capture.file,
                            appLabel = capture.source.appLabel,
                            date = humanDate(capture),
                            onPin = { pin(capture.file) },
                            onLongPress = { actionsFor = capture.file },
                            onDelete = { deleteCapture(capture.file) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (showSourceChooser && sourceGroups.isNotEmpty()) {
        RadioListDialog(
            title = "Captures by app",
            labels = sourceGroups.map { "${it.label} (${it.count})" },
            selectedIndex = sourceGroups.indexOfFirst { it.key == selectedSourceKey },
            onSelect = { index ->
                selectedSourceKey = sourceGroups[index].key
                showSourceChooser = false
            },
            onDismiss = { showSourceChooser = false },
            cancelLabel = stringResource(R.string.common_cancel),
        )
    }

    if (showClearConfirm) {
        val count = allCaptures.size
        val subject = if (count == 1) "this capture" else "all $count captures"
        ConfirmDialog(
            title = "Clear history?",
            text = "Permanently delete $subject from Snapper history? Open pins and copies saved to Pictures won't be removed.",
            confirmLabel = "Clear",
            onConfirm = ::clearHistory,
            onDismiss = { showClearConfirm = false },
        )
    }

    actionsFor?.let { file ->
        val capture = allCaptures.first { it.file == file }
        HistoryActionsDialog(
            title = humanDate(capture),
            onDismiss = { actionsFor = null },
            onPin = { pin(file) },
            onScanQr = { scanQr(context, scope, file) },
            onSave = { save(context, scope, repository, file) },
            onShare = { context.startActivity(snapShareChooser(repository.uriFor(file))) },
            onDelete = { deleteConfirmFor = file },
        )
    }

    deleteConfirmFor?.let { file ->
        ConfirmDialog(
            title = "Delete capture?",
            text = "This removes it from Snapper history.",
            confirmLabel = "Delete",
            onConfirm = { deleteCapture(file) },
            onDismiss = { deleteConfirmFor = null },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private fun loadSnapshot(repository: SnapRepository): HistorySnapshot {
    val captures = repository.listEntries().map { HistoryCapture(it.file, it.source) }
    val groups = captures
        .groupBy { it.source.sourceKey }
        .map { (key, group) -> SourceGroup(key, group.last().source.appLabel, group.size) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    return HistorySnapshot(captures, groups)
}

private fun showClearHistoryResult(
    context: Context,
    expected: Int,
    nowEmpty: Boolean,
    result: SnapRepository.DeleteSummary,
) {
    val message = if (result.deleted == expected && nowEmpty) {
        "History cleared"
    } else if (result.livePins > 0) {
        val pins = if (result.livePins == 1) "1 open pin" else "${result.livePins} open pins"
        val kept = if (result.deleted > 0) {
            "Deleted ${result.deleted} capture" + (if (result.deleted == 1) "" else "s") + "; kept $pins"
        } else {
            "Kept $pins; close " + (if (result.livePins == 1) "it" else "them") + " before clearing history"
        }
        if (result.failed > 0) "$kept; ${result.failed} could not be deleted" else kept
    } else if (result.deleted > 0) {
        "Deleted ${result.deleted} of $expected captures"
    } else {
        "Couldn't clear history"
    }
    val length = if (message == "History cleared") Toast.LENGTH_SHORT else Toast.LENGTH_LONG
    Toast.makeText(context, message, length).show()
}

private fun save(context: Context, scope: CoroutineScope, repository: SnapRepository, file: File) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { repository.copyToGallery(file) }
            Toast.makeText(context, "Saved to Pictures/Snapper", Toast.LENGTH_LONG).show()
        } catch (error: IOException) {
            Toast.makeText(context, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }
}

private fun scanQr(context: Context, scope: CoroutineScope, file: File) {
    Toast.makeText(context, "Scanning for a QR code…", Toast.LENGTH_SHORT).show()
    scope.launch {
        val message = when (val result = withContext(Dispatchers.IO) { QrScanner.scanFile(file) }) {
            is QrScanResult.Failure -> result.message
            is QrScanResult.Success -> when (deliverQrText(context, result.text)) {
                QrDelivery.OPENED_LINK -> return@launch
                QrDelivery.COPIED_LINK_WITHOUT_BROWSER -> "No browser could open the link; QR text copied"
                QrDelivery.COPIED_TEXT -> context.getString(R.string.feedback_qr_text_copied)
                QrDelivery.EMPTY -> "The QR code contains no text"
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
