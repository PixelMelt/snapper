package com.snapper.android.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.snapper.android.R
import com.snapper.android.actions.SnapperActionRegistry
import com.snapper.android.ui.CardDivider
import com.snapper.android.ui.PreferenceRowText
import kotlin.math.abs

@Composable
internal fun ActionOrderList() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var version by remember { mutableIntStateOf(0) }
    val actions = remember(version) { SnapperActionRegistry.orderedAll(context) }
    val rowHeights = remember { mutableMapOf<String, Int>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun move(action: SnapperActionRegistry.Action, direction: Int) {
        SnapperActionRegistry.move(context, action, direction)
        version++
    }

    fun followDrag(action: SnapperActionRegistry.Action) {
        while (true) {
            val ordered = SnapperActionRegistry.orderedAll(context)
            val index = ordered.indexOfFirst { it.id == action.id }
            val direction = if (dragOffset > 0f) 1 else -1
            val neighbour = ordered.getOrNull(index + direction) ?: return
            val neighbourHeight = rowHeights.getValue(neighbour.id)
            if (abs(dragOffset) < neighbourHeight / 2f) return
            if (!SnapperActionRegistry.canMove(context, action, direction)) return
            move(action, direction)
            dragOffset -= direction * neighbourHeight
        }
    }

    actions.forEachIndexed { index, action ->
        key(action.id) {
            val title = action.title(context)
            val enabled = SnapperActionRegistry.isEnabled(context, action)
            val dragging = draggingId == action.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .onSizeChanged { rowHeights[action.id] = it.height }
                    .defaultMinSize(minHeight = 84.dp)
                    .semantics(mergeDescendants = true) {
                        val state = when {
                            !action.available -> "Unavailable"
                            enabled -> "Enabled"
                            else -> context.getString(R.string.state_disabled)
                        }
                        contentDescription = "$title. ${action.detail}. $state"
                        customActions = listOf(-1 to "Move up", 1 to "Move down")
                            .filter { (direction, _) -> SnapperActionRegistry.canMove(context, action, direction) }
                            .map { (direction, label) ->
                                CustomAccessibilityAction(label) {
                                    move(action, direction)
                                    true
                                }
                            }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(48.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    draggingId = action.id
                                    dragOffset = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffset = 0f
                                },
                            ) { change, delta ->
                                change.consume()
                                dragOffset += delta.y
                                followDrag(action)
                            }
                        }
                        .padding(12.dp),
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = action.available) {
                            SnapperActionRegistry.setEnabled(context, action, !enabled)
                            version++
                        }
                        .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
                        .defaultMinSize(minHeight = 68.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val providerIcon = action.providerIcon
                    if (action.isExternal && providerIcon != null) {
                        Image(
                            bitmap = providerIcon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(40.dp),
                        )
                    }
                    PreferenceRowText(title, action.detail, Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = null,
                        enabled = action.available,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (index + 1 < actions.size) {
                CardDivider()
            }
        }
    }
}
