package com.snapper.android.app

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.snapper.android.R
import com.snapper.android.capture.RootCapture
import com.snapper.android.overlay.SnapActions
import com.snapper.android.settings.SnapSettings
import com.snapper.android.ui.CardDivider
import com.snapper.android.ui.NavigationCard
import com.snapper.android.ui.SectionHeader
import com.snapper.android.ui.SettingsCard
import com.snapper.android.ui.StatusBadge
import com.snapper.android.ui.StatusDot
import com.snapper.android.ui.theme.LocalSnapperStatusColors
import com.snapper.android.ui.theme.SnapperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SnapperTheme {
                MainScreen()
            }
        }
    }
}

private sealed interface RootState {
    data object Unknown : RootState
    data object Checking : RootState
    data object Verified : RootState
    data class Failed(val error: String) : RootState
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = LocalSnapperStatusColors.current

    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var rootState by remember {
        mutableStateOf<RootState>(
            if (SnapSettings.rootVerified(context)) RootState.Verified else RootState.Unknown,
        )
    }
    var showHardwareHelp by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayAllowed = Settings.canDrawOverlays(context)
                if (rootState !is RootState.Checking && SnapSettings.rootVerified(context)) {
                    rootState = RootState.Verified
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun checkRoot() {
        rootState = RootState.Checking
        scope.launch {
            val error = withContext(Dispatchers.IO) { RootCapture.checkRoot() }
            SnapSettings.setRootVerified(context, error == null)
            rootState = if (error == null) RootState.Verified else RootState.Failed(error)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 40.dp),
        ) {
            BrandRow(onOpenSettings = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            })


            val rootReady = rootState is RootState.Verified
            val readyCount = (if (overlayAllowed) 1 else 0) + (if (rootReady) 1 else 0)
            SectionHeader(title = "Setup") {
                if (readyCount == 2) {
                    StatusBadge("READY", status.successContainer, status.onSuccessContainer)
                } else {
                    StatusBadge(
                        "$readyCount OF 2 READY",
                        status.warningContainer,
                        status.onWarningContainer,
                    )
                }
            }
            SettingsCard {
                SetupRow(
                    dotColor = if (overlayAllowed) status.success else status.warning,
                    title = "Display over apps",
                    detail = if (overlayAllowed) {
                        "Allowed"
                    } else {
                        "Not allowed"
                    },
                    detailColor = if (overlayAllowed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        status.warning
                    },
                    actionLabel = if (overlayAllowed) "Manage" else "Allow",
                    actionEnabled = true,
                ) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
                CardDivider()
                SetupRow(
                    dotColor = when (rootState) {
                        is RootState.Verified -> status.success
                        is RootState.Checking -> status.warning
                        is RootState.Failed -> MaterialTheme.colorScheme.error
                        is RootState.Unknown -> MaterialTheme.colorScheme.outline
                    },
                    title = "Root access",
                    detail = when (val state = rootState) {
                        is RootState.Verified -> "Granted"
                        is RootState.Checking -> "Checking…"
                        is RootState.Failed -> state.error
                        is RootState.Unknown -> "Not verified"
                    },
                    detailColor = if (rootState is RootState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    actionLabel = when (rootState) {
                        is RootState.Verified -> "Recheck"
                        is RootState.Checking -> "…"
                        is RootState.Failed -> "Retry"
                        is RootState.Unknown -> "Check"
                    },
                    actionEnabled = rootState !is RootState.Checking,
                    onAction = ::checkRoot,
                )
            }

            SectionHeader(title = "Screenshot button")
            NavigationCard(
                iconResource = R.drawable.ic_tile_crop,
                title = "Power + Volume Down",
                onClick = { showHardwareHelp = true },
            )

            NavigationCard(
                iconResource = R.drawable.ic_tile_history,
                title = stringResource(R.string.history_open),
                modifier = Modifier.padding(top = 12.dp),
                onClick = {
                    context.startActivity(Intent(context, HistoryActivity::class.java))
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { SnapActions.send(context, SnapActions.ACTION_HIDE_ALL) },
                    modifier = Modifier.weight(1f),
                ) { Text("Hide all pins") }
                OutlinedButton(
                    onClick = { SnapActions.send(context, SnapActions.ACTION_SHOW_ALL) },
                    modifier = Modifier.weight(1f),
                ) { Text("Show all pins") }
            }
            TextButton(
                onClick = { SnapActions.send(context, SnapActions.ACTION_CLOSE_ALL) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_tile_close),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error),
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Close all pinned snaps",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SectionHeader(title = "Quick Settings")
            TilePairRow(
                left = TileRequest(R.drawable.ic_tile_crop, "Normal") {
                    requestTile(context, NormalTileService::class.java, "Snapper", R.drawable.ic_tile_crop)
                },
                right = TileRequest(R.drawable.ic_tile_freeze, "Freeze") {
                    requestTile(context, FreezeTileService::class.java, "Snapper Freeze", R.drawable.ic_tile_freeze)
                },
            )
            TilePairRow(
                left = TileRequest(R.drawable.ic_tile_instant, "Instant") {
                    requestTile(context, InstantTileService::class.java, "Snapper Instant", R.drawable.ic_tile_instant)
                },
                right = TileRequest(R.drawable.ic_tile_open_last, "Open last") {
                    requestTile(context, OpenLastTileService::class.java, "Snapper Last", R.drawable.ic_tile_open_last)
                },
            )
            TilePairRow(
                left = TileRequest(R.drawable.ic_tile_history, "History") {
                    requestTile(context, HistoryTileService::class.java, "Snapper History", R.drawable.ic_tile_history)
                },
                right = TileRequest(R.drawable.ic_tile_close, "Close all") {
                    requestTile(context, CloseAllTileService::class.java, "Close Snaps", R.drawable.ic_tile_close)
                },
            )

            NavigationCard(
                iconResource = R.drawable.ic_more_vertical,
                title = "About Snapper",
                modifier = Modifier.padding(top = 12.dp),
                onClick = {
                    context.startActivity(Intent(context, AboutActivity::class.java))
                },
            )

        }
    }

    if (showHardwareHelp) {
        HardwareButtonHelpDialog(onDismiss = { showHardwareHelp = false })
    }
}

@Composable
private fun BrandRow(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_tile_crop),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = "SNAPPER",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        FilledTonalButton(
            onClick = onOpenSettings,
            modifier = Modifier.semantics { contentDescription = "Open Snapper settings" },
        ) {
            Text(stringResource(R.string.settings_title))
        }
    }
}

@Composable
private fun SetupRow(
    dotColor: Color,
    title: String,
    detail: String,
    detailColor: Color,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dotColor)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = detailColor,
                modifier = Modifier.padding(top = 3.dp, end = 5.dp),
            )
        }
        FilledTonalButton(onClick = onAction, enabled = actionEnabled) {
            Text(actionLabel)
        }
    }
}


private class TileRequest(
    val iconResource: Int,
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun TilePairRow(left: TileRequest, right: TileRequest) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TileButton(left, Modifier.weight(1f))
        TileButton(right, Modifier.weight(1f))
    }
}

@Composable
private fun RowScope.TileButton(request: TileRequest, modifier: Modifier) {
    OutlinedButton(
        onClick = request.onClick,
        modifier = modifier
            .height(52.dp)
            .semantics { contentDescription = "Add ${request.label} Quick Settings tile" },
        shape = RoundedCornerShape(14.dp),
    ) {
        Image(
            painter = painterResource(request.iconResource),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "${request.label}   +",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun HardwareButtonHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use the screenshot buttons") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HardwareStep("1", "Verify Snapper setup",
                    "Both access rows must show ready; choose the button mode in Settings.")
                HardwareStep("2", "Open LSPosed", "Find Snapper in the module list.")
                HardwareStep("3", "Enable Snapper",
                    "Activation is optional and must be done manually.")
                HardwareStep("4", "Set its scope", "Select System Framework (android) only.")
                HardwareStep("5", "Restart the framework",
                    "Use soft reboot, or reboot the phone.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun HardwareStep(number: String, title: String, detail: String) {
    Row(modifier = Modifier.padding(vertical = 7.dp)) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun requestTile(
    context: Context,
    serviceClass: Class<*>,
    label: String,
    iconResource: Int,
) {
    val statusBar = context.getSystemService(StatusBarManager::class.java)
    statusBar.requestAddTileService(
        ComponentName(context, serviceClass),
        label,
        Icon.createWithResource(context, iconResource),
        context.mainExecutor,
    ) { result ->
        Toast.makeText(
            context,
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                "$label tile added"
            } else {
                "Tile request result: $result"
            },
            Toast.LENGTH_SHORT,
        ).show()
    }
}
