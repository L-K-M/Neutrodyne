// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_open
import ch.lkmc.neutrodyne.core.ui.resources.action_pause
import ch.lkmc.neutrodyne.core.ui.resources.action_resume
import ch.lkmc.neutrodyne.core.ui.resources.order_newest
import ch.lkmc.neutrodyne.core.ui.resources.order_oldest
import ch.lkmc.neutrodyne.core.ui.resources.queue_count
import ch.lkmc.neutrodyne.core.ui.resources.queue_move_down
import ch.lkmc.neutrodyne.core.ui.resources.queue_move_top
import ch.lkmc.neutrodyne.core.ui.resources.queue_move_up
import ch.lkmc.neutrodyne.core.ui.resources.queue_now_playing
import ch.lkmc.neutrodyne.core.ui.resources.queue_remove
import ch.lkmc.neutrodyne.core.ui.resources.queue_stop_after
import ch.lkmc.neutrodyne.core.ui.resources.queue_then
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

/** Display of the durable current item, even before a platform player has connected. */
public data class UpNextCurrent(
    val episodeId: Long,
    val title: String,
    val podcastTitle: String,
    val artwork: ArtworkRef,
    val isPlaying: Boolean,
)

/**
 * Shared by the destination and player tab. Dragging changes only this preview; a successful
 * release commits one move against the latest order. Cancellation, disposal and lost membership
 * discard the preview. Current and context headers never enter the reorderable items.
 */
@Composable
public fun UpNextList(
    current: UpNextCurrent?,
    upNext: List<UpNextItem>,
    context: PlayContextInfo?,
    live: Map<Long, RowLive>,
    caps: RowCaps,
    onAction: (EpisodeAction) -> Unit,
    onMove: (Long, Int) -> Unit,
    onRemove: (Long) -> Unit,
    onStopAfter: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onVisibleIds: (Set<Long>) -> Unit = {},
    emptyContent: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    var dragging by remember { mutableStateOf<Long?>(null) }
    var preview by remember { mutableStateOf<List<Long>?>(null) }
    var startOrder by remember { mutableStateOf(emptyList<Long>()) }
    val latest by rememberUpdatedState(upNext)
    val move by rememberUpdatedState(onMove)
    val ids = upNext.map { it.row.id }
    val byId = remember(upNext) { upNext.associateBy { it.row.id } }
    val displayed = preview?.mapNotNull(byId::get) ?: upNext

    fun cancelDrag() {
        dragging = null
        preview = null
    }

    val reorderState =
        rememberReorderableLazyListState(listState) { _, to ->
            val id = dragging
            val order = preview
            val target = order?.indexOf(to.key)
            if (id != null && order != null && target != null && target >= 0) {
                preview =
                    order.toMutableList().apply {
                        remove(id)
                        add(target, id)
                    }
            }
        }
    val detector =
        remember {
            DragGestureDetector { start, end, cancel, drag ->
                detectDragGestures(
                    onDragStart = start,
                    onDragEnd = {
                        end()
                        val id = dragging
                        val requested = preview.orEmpty()
                        val remaining = latest.map { it.row.id }.filter { it != id }
                        val sameMembership = requested.toSet() == latest.map { it.row.id }.toSet()
                        if (id != null && requested != startOrder && sameMembership) {
                            val next = requested.drop(requested.indexOf(id) + 1).firstOrNull { it in remaining }
                            val target = next?.let(remaining::indexOf) ?: remaining.size
                            move(id, target)
                        }
                        cancelDrag()
                    },
                    onDragCancel = {
                        cancelDrag()
                        cancel()
                    },
                    onDrag = drag,
                )
            }
        }
    LaunchedEffect(ids) {
        if (preview?.toSet()?.let { it != ids.toSet() } == true) cancelDrag()
    }
    DisposableEffect(Unit) { onDispose { cancelDrag() } }

    val headerCount = if (current == null) 1 else 2
    ReportVisibleEpisodeIds(
        listState,
        itemCount = { headerCount + displayed.size },
        idAt = { index -> displayed.getOrNull(index - headerCount)?.row?.id },
        onIds = onVisibleIds,
    )

    LazyColumn(
        state = listState,
        modifier =
            modifier.testTag("upNextList").onPreviewKeyEvent {
                if (it.key == Key.Escape && dragging != null) {
                    if (it.type == KeyEventType.KeyDown) cancelDrag()
                    true
                } else {
                    false
                }
            },
        contentPadding = contentPadding,
    ) {
        if (current != null) {
            item(key = "current") {
                QueueHeading(stringResource(Res.string.queue_now_playing))
                CurrentQueueRow(current, onAction)
            }
        }
        item(key = "upNextHeading") { QueueHeading(stringResource(Res.string.queue_count, upNext.size)) }
        itemsIndexed(displayed, key = { _, item -> item.row.id }) { index, item ->
            val id = item.row.id
            val up = stringResource(Res.string.queue_move_up)
            val down = stringResource(Res.string.queue_move_down)
            val top = stringResource(Res.string.queue_move_top)
            val remove = stringResource(Res.string.queue_remove)
            val actions =
                buildList<Pair<String, () -> Unit>> {
                    if (index > 0) {
                        add(up to { onMove(id, index - 1) })
                        add(top to { onMove(id, 0) })
                    }
                    if (index < displayed.lastIndex) add(down to { onMove(id, index + 1) })
                    add(remove to { onRemove(id) })
                }
            ReorderableItem(reorderState, key = id) {
                QueueSwipe(id, { onRemove(id) }, Modifier.animateItem()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).testTag("queueDrag$id").clearAndSetSemantics {}.draggableHandle(
                                onDragStarted = {
                                    startOrder = latest.map { it.row.id }
                                    preview = startOrder
                                    dragging = id
                                },
                                dragGestureDetector = detector,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(NdIcons.DragHandle, contentDescription = null)
                        }
                        EpisodeRow(
                            row = item.row,
                            live = live[id],
                            style = EpisodeRowStyle.QUEUE,
                            caps = caps.copy(swipe = null),
                            highlightNew = false,
                            selected = null,
                            onAction = onAction,
                            modifier =
                                Modifier.weight(1f).testTag("queueRow$id").onPreviewKeyEvent { event ->
                                    val callback =
                                        when {
                                            event.key == Key.Delete -> {
                                                ({ onRemove(id) })
                                            }

                                            event.isAltPressed && event.key == Key.DirectionUp && index > 0 -> {
                                                ({ onMove(id, index - 1) })
                                            }

                                            event.isAltPressed && event.key == Key.DirectionDown &&
                                                index < displayed.lastIndex -> {
                                                ({ onMove(id, index + 1) })
                                            }

                                            else -> {
                                                null
                                            }
                                        }
                                    if (callback == null || dragging != null) {
                                        false
                                    } else {
                                        if (event.type == KeyEventType.KeyDown) callback()
                                        true
                                    }
                                },
                            additionalActions = if (dragging == null) actions else emptyList(),
                        )
                    }
                }
            }
        }
        if (upNext.isEmpty()) item(key = "empty") { emptyContent() }
        if (context != null && context.type != ContextType.EXTERNAL) {
            item(key = "context") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    val order =
                        stringResource(
                            if (context.order == FeedOrder.NEWEST_FIRST) {
                                Res.string.order_newest
                            } else {
                                Res.string.order_oldest
                            },
                        )
                    Text(
                        stringResource(Res.string.queue_then, context.title, order),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onStopAfter) { Text(stringResource(Res.string.queue_stop_after)) }
                }
            }
        }
    }
}

@Composable
private fun QueueHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(16.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun CurrentQueueRow(
    current: UpNextCurrent,
    onAction: (EpisodeAction) -> Unit,
) {
    val play = stringResource(if (current.isPlaying) Res.string.action_pause else Res.string.action_resume)
    val open = stringResource(Res.string.action_open)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("queueCurrent")
            .semantics(mergeDescendants = true) {
                contentDescription = "${current.title}, ${current.podcastTitle}"
                customActions =
                    listOf(
                        CustomAccessibilityAction(play) {
                            onAction(EpisodeAction.PlayToggle(current.episodeId))
                            true
                        },
                        CustomAccessibilityAction(open) {
                            onAction(EpisodeAction.Open(current.episodeId))
                            true
                        },
                    )
            }.clickable { onAction(EpisodeAction.Open(current.episodeId)) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArt(current.artwork, rememberMonogram(current.podcastTitle), Modifier.size(56.dp))
        Column(Modifier.weight(1f)) {
            Text(
                current.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(current.podcastTitle, style = MaterialTheme.typography.bodySmall)
        }
        NdIconButton(
            { onAction(EpisodeAction.PlayToggle(current.episodeId)) },
            if (current.isPlaying) NdIcons.Pause else NdIcons.PlayArrow,
            play,
            Modifier.clearAndSetSemantics {},
        )
    }
}

/** Touch removal uses the row's existing 40% release threshold; desktop touchpads keep scrolling. */
@Composable
private fun QueueSwipe(
    id: Long,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val touch = LocalPlatformKind.current == PlatformKind.ANDROID
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var displacement by remember(id) { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val remove by rememberUpdatedState(onRemove)
    Box(
        modifier.onSizeChanged { width = it.width }.then(
            if (touch) {
                Modifier.pointerInput(id, rtl) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val commit = swipeCommits(displacement, width)
                            displacement = 0f
                            if (commit) remove()
                        },
                        onDragCancel = { displacement = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val next = displacement + amount
                            displacement =
                                if ((next > 0) != rtl) next.coerceIn(-width.toFloat(), width.toFloat()) else 0f
                        },
                    )
                }
            } else {
                Modifier
            },
        ),
    ) {
        if (displacement != 0f) {
            Row(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clearAndSetSemantics {}
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(NdIcons.Close, contentDescription = null)
                Text(stringResource(Res.string.queue_remove))
            }
        }
        // Pointer deltas are physical; only the revealed content follows layout start.
        Box(Modifier.absoluteOffset { IntOffset(displacement.roundToInt(), 0) }) { content() }
    }
}
