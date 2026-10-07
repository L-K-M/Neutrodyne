// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdBanner
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdPullToRefresh
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeRow
import ch.lkmc.neutrodyne.core.ui.EpisodeRowStyle
import ch.lkmc.neutrodyne.core.ui.FeedFilterChips
import ch.lkmc.neutrodyne.core.ui.LocalDrawnReporter
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.RowCaps
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.offlineBannerItem
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.add_title
import ch.lkmc.neutrodyne.core.ui.resources.feeds_caught_up
import ch.lkmc.neutrodyne.core.ui.resources.feeds_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.feeds_load_error
import ch.lkmc.neutrodyne.core.ui.resources.feeds_mark_all_played
import ch.lkmc.neutrodyne.core.ui.resources.feeds_onboarding_title
import ch.lkmc.neutrodyne.core.ui.resources.feeds_refresh
import ch.lkmc.neutrodyne.core.ui.resources.feeds_search
import ch.lkmc.neutrodyne.core.ui.resources.feeds_show_played
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import org.jetbrains.compose.resources.stringResource

private const val SKELETON_ROWS = 6
private const val CONTENT_DAY = 0
private const val CONTENT_RSS = 1
private const val CONTENT_YOUTUBE = 2

/**
 * The All feed (08 Feeds, M1a): one page — the tab row and pager arrive with M2's groups.
 * [FeedsScreen] stays free of ViewModel and navigator types so desktop UI tests can drive it
 * with fakes; [FeedsRoute] owns the Metro lookup and the action routing split (navigation and
 * URL opens at the route, repository writes in [FeedsViewModel]).
 *
 * The page header's "Play" button waits for M4's `PlaybackController` — deviation recorded in
 * 08 (2026-10-07).
 */
@Composable
internal fun FeedsScreen(
    state: FeedsUiState,
    items: LazyPagingItems<FeedItem>,
    onRefresh: () -> Unit,
    onFiltersChange: (FeedFilters) -> Unit,
    onAction: (EpisodeAction) -> Unit,
    onMarkAllPlayed: () -> Unit,
    onAddPodcast: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmMarkAll by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_feeds),
            actions = {
                // A mouse cannot pull (08 Pull to refresh): the desktop gets a Refresh button.
                if (LocalPlatformKind.current == PlatformKind.DESKTOP) {
                    NdTooltipIconButton(
                        onClick = onRefresh,
                        icon = NdIcons.Refresh,
                        tooltip = stringResource(Res.string.feeds_refresh),
                    )
                }
                SettingsGearButton()
            },
        )

        NdPullToRefresh(isRefreshing = state.refreshing, onRefresh = onRefresh) {
            if (!state.hasSubscriptions) {
                EmptyState(
                    icon = NdIcons.DynamicFeed,
                    title = stringResource(Res.string.feeds_onboarding_title),
                    body = stringResource(Res.string.feeds_empty_body),
                    actionLabel = stringResource(Res.string.add_title),
                    onAction = onAddPodcast,
                    secondaryLabel = stringResource(Res.string.feeds_search),
                    onSecondary = onSearch,
                )
            } else {
                FeedList(
                    state = state,
                    items = items,
                    onRefresh = onRefresh,
                    onFiltersChange = onFiltersChange,
                    onAction = onAction,
                    onMarkAllPlayed = { confirmMarkAll = true },
                )
            }
        }
    }

    if (confirmMarkAll) {
        NdDialog(
            onDismissRequest = { confirmMarkAll = false },
            icon = NdIcons.DoneAll,
            title = stringResource(Res.string.feeds_mark_all_played),
            confirm =
                NdDialogAction(stringResource(Res.string.feeds_mark_all_played)) {
                    confirmMarkAll = false
                    onMarkAllPlayed()
                },
            dismiss =
                NdDialogAction(stringResource(Res.string.action_cancel)) { confirmMarkAll = false },
        )
    }
}

@Composable
private fun FeedList(
    state: FeedsUiState,
    items: LazyPagingItems<FeedItem>,
    onRefresh: () -> Unit,
    onFiltersChange: (FeedFilters) -> Unit,
    onAction: (EpisodeAction) -> Unit,
    onMarkAllPlayed: () -> Unit,
) {
    val listState = rememberLazyListState()
    val refreshState = items.loadState.refresh

    // 08 Startup metric: ReportDrawnWhen once the first load settles (no-op off Android).
    LocalDrawnReporter.current?.reportWhen { refreshState !is LoadState.Loading }

    if (refreshState is LoadState.Loading && items.itemCount == 0) {
        Column(Modifier.fillMaxSize()) {
            FeedHeader(state.filters, onFiltersChange, onRefresh, onMarkAllPlayed)
            repeat(SKELETON_ROWS) { SkeletonRow() }
        }
        return
    }
    if (refreshState is LoadState.Error && items.itemCount == 0) {
        EmptyState(
            icon = NdIcons.Error,
            title = stringResource(Res.string.feeds_load_error),
            body = "",
            actionLabel = stringResource(Res.string.action_retry),
            onAction = items::retry,
        )
        return
    }
    if (items.itemCount == 0 && refreshState is LoadState.NotLoading) {
        EmptyState(
            icon = NdIcons.CheckCircle,
            title = stringResource(Res.string.feeds_caught_up),
            body = "",
            actionLabel =
                if (state.filters != FeedFilters()) {
                    stringResource(Res.string.feeds_show_played)
                } else {
                    null
                },
            onAction =
                if (state.filters != FeedFilters()) {
                    { onFiltersChange(FeedFilters()) }
                } else {
                    null
                },
        )
        return
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        offlineBannerItem(state.offline)
        item(key = "header", contentType = "header") {
            FeedHeader(state.filters, onFiltersChange, onRefresh, onMarkAllPlayed)
        }
        items(
            count = items.itemCount,
            key =
                items.itemKey { item ->
                    when (item) {
                        is FeedItem.Day -> item.key
                        is FeedItem.Episode -> item.row.id
                    }
                },
            contentType =
                items.itemContentType { item ->
                    when (item) {
                        is FeedItem.Day -> CONTENT_DAY
                        is FeedItem.Episode ->
                            if (item.row.sourceType == SourceType.RSS) CONTENT_RSS else CONTENT_YOUTUBE
                    }
                },
        ) { index ->
            when (val item = items[index]) {
                is FeedItem.Day ->
                    Text(
                        item.label.asString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = DAY_PADDING,
                                    end = DAY_PADDING,
                                    top = DAY_TOP,
                                    bottom = DAY_BOTTOM,
                                ),
                    )
                is FeedItem.Episode ->
                    EpisodeRow(
                        row = item.row,
                        live = null,
                        style = EpisodeRowStyle.FEED,
                        caps = RowCaps.FULL.copy(offline = state.offline),
                        highlightNew = true,
                        selected = null,
                        onAction = onAction,
                    )
                null -> SkeletonRow()
            }
        }
        when (items.loadState.append) {
            is LoadState.Loading ->
                item(key = "appendLoading", contentType = "status") {
                    Box(
                        Modifier.fillMaxWidth().padding(FOOTER_PAD),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(FOOTER_SPINNER))
                    }
                }
            is LoadState.Error ->
                item(key = "appendError", contentType = "status") {
                    NdBanner(
                        message = stringResource(Res.string.feeds_load_error),
                        icon = NdIcons.Error,
                        primary =
                            NdDialogAction(
                                stringResource(Res.string.action_retry),
                                items::retry,
                            ),
                    )
                }
            else -> Unit
        }
    }
}

/**
 * The scrolling header item (08 Header, chips and actions): the filter chips and the page
 * overflow — Refresh and "Mark all as played…" (the All page's M1a overflow; "Hide older
 * than…" is M2's). The header's Play button is omitted until M4 (deviation in 08).
 */
@Composable
private fun FeedHeader(
    filters: FeedFilters,
    onFiltersChange: (FeedFilters) -> Unit,
    onRefresh: () -> Unit,
    onMarkAllPlayed: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FeedFilterChips(
            filters = filters,
            onFiltersChange = onFiltersChange,
            modifier = Modifier.weight(1f),
        )
        Box {
            NdIconButton(
                onClick = { menuOpen = true },
                icon = NdIcons.MoreVert,
                contentDescription = stringResource(Res.string.action_more),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.feeds_refresh)) },
                    onClick = {
                        menuOpen = false
                        onRefresh()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.feeds_mark_all_played)) },
                    onClick = {
                        menuOpen = false
                        onMarkAllPlayed()
                    },
                )
            }
        }
    }
}

/** One placeholder row of the loading state: cover box and two bars in `surfaceContainer`. */
@Composable
private fun SkeletonRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(NeutrodyneShapes.Tile).skeletonBlock())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(4.dp)).skeletonBlock())
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(4.dp)).skeletonBlock())
        }
    }
}

@Composable
private fun Modifier.skeletonBlock(): Modifier =
    background(MaterialTheme.colorScheme.surfaceContainer)

private val ROW_HEIGHT = 72.dp
private val DAY_PADDING = 16.dp
private val DAY_TOP = 16.dp
private val DAY_BOTTOM = 4.dp
private val FOOTER_PAD = 16.dp
private val FOOTER_SPINNER = 32.dp
