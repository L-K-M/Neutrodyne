// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.AddToGroupsKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.GroupsManageKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import ch.lkmc.neutrodyne.core.ui.navigation.rememberEntryIsCurrent
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.library.resources.read_failed
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import ch.lkmc.neutrodyne.feature.library.resources.Res as LibraryRes

/**
 * The Library destination (08 Library): the [LibraryViewModel] holds tiles/sort/titles; the tile
 * menu's "Podcast settings" navigates at the route, "Unsubscribe…" opens the confirmation with
 * the downloaded-episode count the dialog names.
 */
@Composable
internal fun LibraryRoute(
    viewModel: LibraryViewModel = metroViewModel(),
    isCurrentEntry: () -> Boolean = rememberEntryIsCurrent(LibraryKey),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    // 08: dialogs keep their pending state across recreation. Only the ids and count are
    // saveable primitives; the dialogs' tiles re-derive from the emitted tiles, so a
    // restored dialog can never show a stale display title (94-2).
    var pendingUnsubscribeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingUnsubscribeDownloads by rememberSaveable { mutableIntStateOf(0) }
    var pendingMarkAll by rememberSaveable { mutableStateOf<Long?>(null) }
    val pending =
        state.tiles
            .firstOrNull { it.podcastId == pendingUnsubscribeId }
            ?.let { PendingUnsubscribe(it, pendingUnsubscribeDownloads) }

    // Navigation retains saved entries; only the current entry may retain its selection.
    var selectedIds by rememberSaveable(
        saver =
            listSaver<MutableState<List<Long>>, Long>(
                save = { if (isCurrentEntry()) it.value.toList() else emptyList() },
                restore = { mutableStateOf(it.toList()) },
            ),
    ) { mutableStateOf(emptyList<Long>()) }
    var anchor by rememberSaveable(
        saver =
            listSaver<MutableState<Long?>, Long>(
                save = { if (isCurrentEntry()) listOfNotNull(it.value) else emptyList() },
                restore = { mutableStateOf(it.firstOrNull()) },
            ),
    ) { mutableStateOf<Long?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<SelectionAction?>(null) }
    var pendingIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var pendingCount by rememberSaveable { mutableIntStateOf(0) }
    val visibleIds = state.tiles.map { it.podcastId }
    val selection = selectedIds.filter { it in visibleIds }

    fun select(
        id: Long,
        gesture: SelectionGesture,
    ) {
        selectedIds =
            if (gesture == SelectionGesture.RANGE) {
                extendSelection(selection, anchor, id, visibleIds)
            } else {
                anchor = id
                if (id in selection) selection - id else selection + id
            }
    }

    fun exitSelection() {
        selectedIds = emptyList()
        anchor = null
    }

    LaunchedEffect(visibleIds, state.loaded) {
        if (state.loaded) selectedIds = selection
    }
    val currentEntry = isCurrentEntry()
    LaunchedEffect(currentEntry) {
        if (!currentEntry) exitSelection()
    }
    if (LocalNavigationEventDispatcherOwner.current != null) {
        val events = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(events, isBackEnabled = selection.isNotEmpty(), onBackCompleted = ::exitSelection)
    }

    LibraryScreen(
        segment = state.segment,
        selectedIds = selection,
        onSegment = {
            viewModel.setSegment(it)
            exitSelection()
        },
        onGroupFilter = {
            exitSelection()
            viewModel.setGroupFilter(it)
        },
        onNewGroup = { navigator.push(GroupEditKey(null)) },
        onEditGroup = { navigator.push(GroupEditKey(it)) },
        onManageGroups = { navigator.push(GroupsManageKey) },
        onExportGroup = { navigator.push(ExportKey(it)) },
        onExportSubscriptions = { navigator.push(ExportKey(null)) },
        onOpenGroup = { id ->
            scope.launch {
                if (viewModel.selectGroup(id)) {
                    navigator.resetTab(FeedsKey)
                    navigator.selectTab(FeedsKey)
                }
            }
        },
        onSelect = ::select,
        onExitSelection = ::exitSelection,
        onSelectionAction = { action ->
            when (action) {
                SelectionAction.SELECT_ALL -> {
                    selectedIds = visibleIds
                }

                SelectionAction.ADD_TO_GROUP -> {
                    navigator.push(AddToGroupsKey(selection))
                    exitSelection()
                }

                SelectionAction.REFRESH -> {
                    viewModel.refreshSelection(selection)
                    exitSelection()
                }

                SelectionAction.MARK_PLAYED -> {
                    pendingIds = selection
                    pendingCount = selection.size
                    pendingAction = action
                }

                SelectionAction.UNSUBSCRIBE -> {
                    // Freeze the selection before the suspending count read.
                    val ids = selection
                    scope.launch {
                        val count = suspendRunCatching { viewModel.downloadedCount(ids) }.getOrNull()
                        if (count == null) {
                            snackbar.showSnackbar(getString(LibraryRes.string.read_failed))
                            return@launch
                        }
                        pendingIds = ids
                        pendingCount = count
                        pendingAction = action
                    }
                }
            }
        },
        pendingSelection = pendingAction?.let { PendingSelectionAction(it, pendingIds, pendingCount) },
        onDismissSelection = { pendingAction = null },
        onConfirmSelection = {
            val ids = pendingIds
            when (pendingAction) {
                SelectionAction.UNSUBSCRIBE -> viewModel.unsubscribeSelection(ids)
                SelectionAction.MARK_PLAYED -> viewModel.markSelectionPlayed(ids)
                else -> Unit
            }
            pendingAction = null
            exitSelection()
        },
        state = state,
        pendingUnsubscribe = pending,
        pendingMarkAll = state.tiles.firstOrNull { it.podcastId == pendingMarkAll },
        onSort = viewModel::setSort,
        onToggleTitles = viewModel::setShowTitles,
        onOpenPodcast = { navigator.pushDetail(PodcastKey(it)) },
        onTileAction = { podcastId, action ->
            when (action) {
                TileAction.ADD_TO_GROUP -> {
                    navigator.push(AddToGroupsKey(listOf(podcastId)))
                }

                TileAction.SELECT -> {
                    select(podcastId, SelectionGesture.TOGGLE)
                }

                TileAction.SETTINGS -> {
                    navigator.pushDetail(PodcastSettingsKey(podcastId))
                }

                TileAction.REFRESH -> {
                    viewModel.refreshPodcast(podcastId)
                }

                TileAction.MARK_PLAYED -> {
                    pendingMarkAll = podcastId
                }

                TileAction.UNSUBSCRIBE -> {
                    scope.launch {
                        val tile = state.tiles.firstOrNull { it.podcastId == podcastId } ?: return@launch
                        // A failed count read must not crash — and the dialog can't show without it.
                        val count =
                            suspendRunCatching { viewModel.downloadedCount(podcastId) }.getOrNull() ?: run {
                                snackbar.showSnackbar(getString(LibraryRes.string.read_failed))
                                return@launch
                            }
                        pendingUnsubscribeId = tile.podcastId
                        pendingUnsubscribeDownloads = count
                    }
                }
            }
        },
        onConfirmUnsubscribe = {
            pendingUnsubscribeId = null
            viewModel.unsubscribe(it.podcastId)
        },
        onDismissUnsubscribe = { pendingUnsubscribeId = null },
        onConfirmMarkAll = {
            pendingMarkAll = null
            viewModel.markAllPlayed(it)
        },
        onDismissMarkAll = { pendingMarkAll = null },
        onAddPodcast = { navigator.push(AddPodcastKey(null)) },
        onSearchPodcasts = { navigator.selectTab(DiscoverKey) },
    )

    ShowUserMessages(state.messages, viewModel::onMessageShown)
}
