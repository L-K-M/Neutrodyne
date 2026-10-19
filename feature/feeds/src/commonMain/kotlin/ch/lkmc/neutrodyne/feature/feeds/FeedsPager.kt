// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTabRow
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeSelectionController
import ch.lkmc.neutrodyne.core.ui.GroupTabLabel
import ch.lkmc.neutrodyne.core.ui.LocalDrawnReporter
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.SelectionTopBar
import ch.lkmc.neutrodyne.core.ui.episodeSelectionKeys
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_all
import ch.lkmc.neutrodyne.core.ui.resources.feeds_all_groups
import ch.lkmc.neutrodyne.core.ui.resources.feeds_next_group
import ch.lkmc.neutrodyne.core.ui.resources.feeds_previous_group
import ch.lkmc.neutrodyne.core.ui.resources.feeds_refresh
import ch.lkmc.neutrodyne.core.ui.resources.feeds_ungrouped
import ch.lkmc.neutrodyne.core.ui.resources.group_hidden
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Shared pager chrome; pages own saved list state independently of cached paging flows. */
@Suppress("ktlint:compose:state-param-check", "ktlint:compose:parameter-naming")
@Composable
internal fun FeedsScreen(
    state: FeedsUiState,
    pagingFor: (FeedSource) -> Flow<PagingData<FeedItem>>,
    onRefresh: (FeedSource) -> Unit,
    onPlay: (FeedSource) -> Unit,
    onFiltersChange: (FeedSource, FeedFilters) -> Unit,
    onAction: (EpisodeAction) -> Unit,
    onMarkAllPlayedClick: (FeedSource) -> Unit,
    onAddPodcast: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onDownloadAll: (FeedSource) -> Unit = {},
    onImport: () -> Unit = {},
    prefsFor: (FeedSource) -> Flow<FeedPrefs> = { flowOf(FeedPrefs(filters = state.filters)) },
    pageState: SaveableStateHolder = rememberSaveableStateHolder(),
    live: State<Map<Long, RowLive>> = rememberUpdatedState(emptyMap()),
    onVisibleIds: (FeedSourceRef, Set<Long>) -> Unit = { _, _ -> },
    onPageSettled: (FeedSourceRef) -> Unit = {},
    onVisitStart: (FeedSource) -> Unit = {},
    onVisitEnd: (FeedSource) -> Unit = {},
    onAllGroups: () -> Unit = {},
    suggestedGroups: @Composable () -> Unit = {},
    selection: EpisodeSelectionController? = null,
    onPlayEpisode: ((FeedSource, Long) -> Unit)? = null,
    onPlaySelectionNext: (() -> Unit)? = null,
    onPlaySelectionLast: (() -> Unit)? = null,
) {
    val selectedIds =
        selection
            ?.ids
            ?.collectAsStateWithLifecycle()
            ?.value
            .orEmpty()
    val loadedPages = remember { mutableMapOf<FeedSource, LazyPagingItems<FeedItem>>() }
    val tabs = state.tabs
    // A zero-page first measure would clamp restored pager selection before tabs arrive.
    if (tabs.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            NdTopAppBar(title = stringResource(Res.string.nav_feeds))
            CircularProgressIndicator()
        }
        return
    }

    val refs = tabs.map { it.ref }
    val pager = rememberPagerState(initialPage = refs.indexOf(state.selected).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    val settledCallback by rememberUpdatedState(onPageSettled)
    val selectedIndex = refs.indexOf(state.selected).coerceAtLeast(0)

    var appliedSelection by remember { mutableStateOf<FeedSourceRef?>(null) }
    var settledRef by remember { mutableStateOf(state.selected) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Sync routes and tab mutations before observing settles. Two independent effects could
    // persist a neighbour at the old index while a deletion is moving the pager back to All.
    LaunchedEffect(state.selected, refs, pager) {
        val animate = appliedSelection != null && appliedSelection != state.selected
        appliedSelection = state.selected
        if (refs.getOrNull(pager.currentPage) != state.selected) {
            if (animate) pager.animateScrollToPage(selectedIndex) else pager.scrollToPage(selectedIndex)
        }
        snapshotFlow { if (pager.isScrollInProgress) null else refs.getOrNull(pager.settledPage) }
            .distinctUntilChanged()
            .collect { ref ->
                if (ref == null) return@collect
                if (ref != settledRef) selection?.clear()
                settledRef = ref
                if (ref != state.selected) settledCallback(ref)
            }
    }
    val previousRefs = remember { mutableSetOf<String>() }
    LaunchedEffect(refs) {
        val keys = refs.map { it.encode() }.toSet()
        (previousRefs - keys).forEach(pageState::removeState)
        previousRefs.clear()
        previousRefs.addAll(keys)
    }

    val startVisit by rememberUpdatedState(onVisitStart)
    val endVisit by rememberUpdatedState(onVisitEnd)
    // Visits follow identity so a reorder cannot end a visit at the old numeric index.
    val settledSource = tabs.find { it.ref == settledRef }?.tab?.source
    val selectedSource = settledSource ?: FeedSource.All
    if (settledSource != null) {
        LifecycleResumeEffect(settledSource) {
            startVisit(settledSource)
            onPauseOrDispose { endVisit(settledSource) }
        }
    }

    fun movePage(delta: Int): Boolean {
        val target = pager.currentPage + delta
        if (target !in tabs.indices) return false
        scope.launch { pager.animateScrollToPage(target) }
        return true
    }

    val nextLabel = stringResource(Res.string.feeds_next_group)
    val previousLabel = stringResource(Res.string.feeds_previous_group)
    val drawnReporter = LocalDrawnReporter.current
    val selectAll = {
        selection?.selectAll(
            loadedPages[selectedSource]
                ?.itemSnapshotList
                ?.items
                .orEmpty()
                .episodeIds(),
        )
        Unit
    }
    Column(
        modifier
            .fillMaxSize()
            .episodeSelectionKeys(selection, selectAll)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed || event.isMetaPressed
                when {
                    ctrl && event.key == Key.Tab -> {
                        movePage(if (event.isShiftPressed) -1 else 1)
                    }

                    (ctrl && event.key == Key.R) || event.key == Key.F5 -> {
                        onRefresh(selectedSource)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }.focusable(),
    ) {
        if (selectedIds.isNotEmpty() && selection != null) {
            SelectionTopBar(
                selection,
                selectedIds.size,
                selectAll,
                onPlayNext = onPlaySelectionNext,
                onPlayLast = onPlaySelectionLast,
            )
        } else {
            NdTopAppBar(
                title = stringResource(Res.string.nav_feeds),
                actions = {
                    if (LocalPlatformKind.current == PlatformKind.DESKTOP) {
                        NdTooltipIconButton(
                            onClick = { onRefresh(selectedSource) },
                            icon = NdIcons.Refresh,
                            tooltip = stringResource(Res.string.feeds_refresh),
                        )
                    }
                    SettingsGearButton()
                },
            )
        }
        // The picker must remain reachable when every group is hidden from tabs.
        if (tabs.size > 1 || state.hasGroups) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NdTabRow(pager.currentPage.coerceIn(tabs.indices), Modifier.weight(1f)) {
                    tabs.forEachIndexed { index, entry ->
                        Tab(
                            modifier = Modifier.testTag("feed-tab:${entry.ref.encode()}"),
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GroupTabLabel(
                                        name =
                                            when (entry.tab.source) {
                                                FeedSource.All -> stringResource(Res.string.feeds_all)
                                                FeedSource.Ungrouped -> stringResource(Res.string.feeds_ungrouped)
                                                else -> entry.tab.title
                                            },
                                        colorArgb = entry.tab.colorArgb,
                                        iconKey = entry.tab.iconKey,
                                        unplayedCount = entry.unplayed,
                                        newCount = entry.newCount,
                                        selected = pager.currentPage == index,
                                    )
                                    if (entry.transient) {
                                        Icon(NdIcons.VisibilityOff, stringResource(Res.string.group_hidden))
                                    }
                                }
                            },
                        )
                    }
                }
                NdIconButton(
                    onClick = onAllGroups,
                    icon = NdIcons.GridView,
                    contentDescription = stringResource(Res.string.feeds_all_groups),
                )
            }
        }
        suggestedGroups()
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { tabs[it].ref.encode() },
            userScrollEnabled = !state.rowSwipe,
            modifier =
                Modifier.weight(1f).testTag("feeds-pager").semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(nextLabel) { movePage(1) },
                            CustomAccessibilityAction(previousLabel) { movePage(-1) },
                        )
                },
        ) { index ->
            val entry = tabs[index]
            // A fling may retain more than three Compose pages. Only current ± 1 collect;
            // re-entering this window reacquires any flow cancelled by the ViewModel's LRU.
            if (index in (pager.currentPage - 1)..(pager.currentPage + 1)) {
                pageState.SaveableStateProvider(entry.ref.encode()) {
                    val source = entry.tab.source
                    val flow = remember(source, pager.currentPage) { pagingFor(source) }
                    val items = flow.collectAsLazyPagingItems()
                    SideEffect { loadedPages[source] = items }
                    DisposableEffect(source, items) {
                        onDispose { if (loadedPages[source] === items) loadedPages.remove(source) }
                    }
                    val prefsFlow = remember(source) { prefsFor(source) }
                    val prefs by prefsFlow.collectAsStateWithLifecycle(FeedPrefs())
                    // A prefetched neighbour must not report the selected page as drawn.
                    CompositionLocalProvider(
                        LocalDrawnReporter provides drawnReporter.takeIf { entry.ref == settledRef },
                    ) {
                        FeedPage(
                            state = state.copy(filters = prefs.filters.copy(minSortDate = null)),
                            selection = selection,
                            modifier = Modifier.testTag("feed-page:${entry.ref.encode()}"),
                            items = items,
                            live = live,
                            onVisibleIds = { onVisibleIds(entry.ref, it) },
                            visitBaseline = state.visitBaselines[source],
                            onRefresh = { onRefresh(source) },
                            onPlay = { onPlay(source) },
                            onFiltersChange = { onFiltersChange(source, it) },
                            onAction = { action ->
                                if (action is EpisodeAction.PlayToggle && onPlayEpisode != null) {
                                    onPlayEpisode(source, action.episodeId)
                                } else {
                                    onAction(action)
                                }
                            },
                            onMarkAllPlayedClick = { onMarkAllPlayedClick(source) },
                            onDownloadAll = if (source is FeedSource.Group) ({ onDownloadAll(source) }) else null,
                            onAddPodcast = onAddPodcast,
                            onSearch = onSearch,
                            onImport = onImport,
                        )
                    }
                }
            }
        }
    }
}
