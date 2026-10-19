// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import ch.lkmc.neutrodyne.core.designsystem.components.NdActionRole
import ch.lkmc.neutrodyne.core.designsystem.components.NdBanner
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdLoading
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.AVAILABLE_FEED_FILTER_CAPABILITIES
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.navigation.AddToGroupsKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.CredentialFields
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeRow
import ch.lkmc.neutrodyne.core.ui.EpisodeRowStyle
import ch.lkmc.neutrodyne.core.ui.EpisodeSelectionController
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.FeedErrorText
import ch.lkmc.neutrodyne.core.ui.FeedFilterChips
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.NavBackButton
import ch.lkmc.neutrodyne.core.ui.PodcastHeader
import ch.lkmc.neutrodyne.core.ui.ReportVisibleEpisodeIds
import ch.lkmc.neutrodyne.core.ui.RowCaps
import ch.lkmc.neutrodyne.core.ui.SelectionTopBar
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.episodeSelectionKeys
import ch.lkmc.neutrodyne.core.ui.forSource
import ch.lkmc.neutrodyne.core.ui.offlineBannerItem
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_copy
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.action_open_website
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_save
import ch.lkmc.neutrodyne.core.ui.resources.action_share
import ch.lkmc.neutrodyne.core.ui.resources.action_watch_on_youtube
import ch.lkmc.neutrodyne.core.ui.resources.add_auth_title
import ch.lkmc.neutrodyne.core.ui.resources.download_all
import ch.lkmc.neutrodyne.core.ui.resources.feeds_load_error
import ch.lkmc.neutrodyne.core.ui.resources.feeds_refresh
import ch.lkmc.neutrodyne.core.ui.resources.feeds_show_played
import ch.lkmc.neutrodyne.core.ui.resources.order_newest
import ch.lkmc.neutrodyne.core.ui.resources.order_oldest
import ch.lkmc.neutrodyne.core.ui.resources.podcast_copy_feed
import ch.lkmc.neutrodyne.core.ui.resources.podcast_copy_feed_warning
import ch.lkmc.neutrodyne.core.ui.resources.podcast_enter_password
import ch.lkmc.neutrodyne.core.ui.resources.podcast_fetching
import ch.lkmc.neutrodyne.core.ui.resources.podcast_gone
import ch.lkmc.neutrodyne.core.ui.resources.podcast_load_older
import ch.lkmc.neutrodyne.core.ui.resources.podcast_loading
import ch.lkmc.neutrodyne.core.ui.resources.podcast_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.podcast_maybe_moved
import ch.lkmc.neutrodyne.core.ui.resources.podcast_maybe_moved_never
import ch.lkmc.neutrodyne.core.ui.resources.podcast_needs_password
import ch.lkmc.neutrodyne.core.ui.resources.podcast_no_episodes
import ch.lkmc.neutrodyne.core.ui.resources.podcast_settings
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe_downloads
import ch.lkmc.neutrodyne.core.ui.resources.podcast_unsubscribe_title
import ch.lkmc.neutrodyne.core.ui.resources.ps_edit_url
import ch.lkmc.neutrodyne.core.ui.resources.ps_feed_copied
import ch.lkmc.neutrodyne.core.ui.resources.yt_empty_channel
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The unsubscribe confirmation's data: the display title plus the downloaded-episode count. */
internal data class PendingUnsubscribe(
    val title: String,
    val downloads: Int,
)

/**
 * The podcast detail screen (08 Podcast detail): the header scrolls away inside the `LazyColumn`
 * while the top bar stays (M1a keeps the bar in-flow rather than overlaid on the artwork tint —
 * the gradient and shared-element hand-off arrive with M10; deviation recorded in 08). The
 * feed-state banner sits between the header and the transient filter chips.
 *
 * 08 Live row state defers map reads to each row to limit recomposition to its own slot.
 */
@Suppress("ktlint:compose:state-param-check")
@Composable
internal fun PodcastScreen(
    state: PodcastUiState,
    items: LazyPagingItems<EpisodeRow>,
    pendingUnsubscribe: PendingUnsubscribe?,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onFiltersChange: (FeedFilters) -> Unit,
    onOrderChange: (FeedOrder) -> Unit,
    onAction: (EpisodeAction) -> Unit,
    onLoadOlder: () -> Unit,
    onRetryFeed: () -> Unit,
    onEnterCredentials: (BasicCredentials) -> Unit,
    onMarkAllPlayedClick: () -> Unit,
    onUnsubscribeRequest: () -> Unit,
    onConfirmUnsubscribe: () -> Unit,
    onDismissUnsubscribe: () -> Unit,
    onRemoveGroup: (Group) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadAll: () -> Unit = {},
    live: State<Map<Long, RowLive>> = rememberUpdatedState(emptyMap()),
    onVisibleIds: (Set<Long>) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    selection: EpisodeSelectionController? = null,
    onPlaySelectionNext: (() -> Unit)? = null,
    onPlaySelectionLast: (() -> Unit)? = null,
) {
    val selectedIds =
        selection
            ?.ids
            ?.collectAsStateWithLifecycle()
            ?.value
            .orEmpty()
    val selectAll = {
        selection?.selectAll(items.itemSnapshotList.items.map { it.id })
        Unit
    }
    val detail = state.detail
    val navigator = LocalAppNavigator.current
    // The offline banner is a real list item before the header: the title only joins the
    // top bar once the header itself scrolled off, so its index shifts when offline.
    val headerIndex = if (state.offline) 1 else 0
    val headerGone by
        remember(headerIndex) { derivedStateOf { listState.firstVisibleItemIndex > headerIndex } }
    val nowMs = LocalUiClock.current.now()

    Column(modifier = modifier.fillMaxSize().episodeSelectionKeys(selection, selectAll)) {
        if (selectedIds.isNotEmpty() && selection != null) {
            SelectionTopBar(
                selection,
                selectedIds.size,
                selectAll,
                onPlayNext =
                    onPlaySelectionNext.takeIf {
                        detail?.sourceType == SourceType.RSS ||
                            state.rowCaps.inAppPlayback
                    },
                onPlayLast =
                    onPlaySelectionLast.takeIf {
                        detail?.sourceType == SourceType.RSS ||
                            state.rowCaps.inAppPlayback
                    },
            )
        } else {
            NdTopAppBar(
                title = if (headerGone) detail?.displayTitle.orEmpty() else "",
                navigation = { NavBackButton() },
                actions = {
                    NdTooltipIconButton(
                        onClick = onRefresh,
                        icon = NdIcons.Refresh,
                        tooltip = stringResource(Res.string.feeds_refresh),
                        enabled = !state.offline,
                    )
                    NdTooltipIconButton(
                        onClick = onOpenSettings,
                        icon = NdIcons.Settings,
                        tooltip = stringResource(Res.string.podcast_settings),
                    )
                    PodcastOverflow(
                        detail = detail,
                        caps = state.rowCaps,
                        feedUrl = state.feedUrl,
                        onOpenSettings = onOpenSettings,
                        onMarkAllPlayedClick = onMarkAllPlayedClick,
                        onDownloadAll = onDownloadAll,
                        onUnsubscribe = onUnsubscribeRequest,
                    )
                },
                elevated = headerGone,
            )
        }

        when {
            !state.loaded -> {
                LoadingBody()
            }

            detail == null -> {
                Unit
            }

            // `gone`: the route pops once the snackbar shows.
            // First-page race (the Feeds screen's guard): while paging's refresh is still out the
            // header-only list would clamp a restored scroll index before the rows exist, so the
            // LazyColumn stays unmounted until the first page settles.
            items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> {
                Column(Modifier.fillMaxSize()) {
                    PodcastHeader(
                        detail = detail,
                        nowMs = nowMs,
                        groups = state.groups,
                        onRemoveGroup = onRemoveGroup,
                        onAddToGroups = { navigator.push(AddToGroupsKey(listOf(detail.id))) },
                    )
                    FeedStateBanner(
                        detail = detail,
                        nowMs = nowMs,
                        onRetryFeed = onRetryFeed,
                        onEnterCredentials = onEnterCredentials,
                        onOpenSettings = onOpenSettings,
                        onUnsubscribe = onUnsubscribeRequest,
                    )
                    ChipsRow(
                        filters = state.filters,
                        order = state.effectiveOrder,
                        onFiltersChange = onFiltersChange,
                        onOrderChange = onOrderChange,
                    )
                    Box(
                        Modifier.fillMaxWidth().padding(FOOTER_PADDING),
                        contentAlignment = Alignment.Center,
                    ) {
                        NdLoading()
                    }
                }
            }

            else -> {
                // 08's paging-error rule, applied where the user actually is: with placeholders
                // on, an append failure's footer would sit thousands of rows away and a
                // prepend failure has no footer at all — every non-empty-list failure pins a
                // banner under the app bar instead (revised 2026-10-14: the append footer row
                // is gone; it duplicated the banner once the user scrolled to it).
                val episodeStart = headerIndex + 1
                ReportVisibleEpisodeIds(
                    listState = listState,
                    itemCount = { episodeStart + items.itemCount },
                    idAt = { index ->
                        val pagedIndex = index - episodeStart
                        if (pagedIndex in 0 until items.itemCount) items.peek(pagedIndex)?.id else null
                    },
                    onIds = onVisibleIds,
                )
                LoadErrorBanner(items)
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    offlineBannerItem(state.offline)
                    item(key = "header", contentType = "header") {
                        Column {
                            PodcastHeader(
                                detail = detail,
                                nowMs = nowMs,
                                groups = state.groups,
                                onRemoveGroup = onRemoveGroup,
                                onAddToGroups = {
                                    navigator.push(AddToGroupsKey(listOf(detail.id)))
                                },
                            )
                            FeedStateBanner(
                                detail = detail,
                                nowMs = nowMs,
                                onRetryFeed = onRetryFeed,
                                onEnterCredentials = onEnterCredentials,
                                onOpenSettings = onOpenSettings,
                                onUnsubscribe = onUnsubscribeRequest,
                            )
                            ChipsRow(
                                filters = state.filters,
                                order = state.effectiveOrder,
                                onFiltersChange = onFiltersChange,
                                onOrderChange = onOrderChange,
                            )
                        }
                    }

                    // 08's `emptyFeed` doubles as the filtered-empty state: a fetched feed
                    // holding zero episodes gets the bare copy, while an active filter adds
                    // the reset action.
                    val pendingChannel =
                        detail.sourceType == SourceType.YOUTUBE_CHANNEL &&
                            detail.status == PodcastStatus.PENDING_FIRST_FETCH
                    if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading && !pendingChannel) {
                        item(key = "empty", contentType = "empty") {
                            EmptyState(
                                icon = NdIcons.FilterList,
                                title =
                                    stringResource(
                                        if (detail.sourceType == SourceType.YOUTUBE_CHANNEL &&
                                            state.filters == FeedFilters()
                                        ) {
                                            Res.string.yt_empty_channel
                                        } else {
                                            Res.string.podcast_no_episodes
                                        },
                                    ),
                                body = "",
                                actionLabel =
                                    if (state.filters != FeedFilters()) {
                                        stringResource(Res.string.feeds_show_played)
                                    } else if (detail.sourceType == SourceType.YOUTUBE_CHANNEL) {
                                        stringResource(Res.string.podcast_settings)
                                    } else {
                                        null
                                    },
                                onAction =
                                    if (state.filters != FeedFilters()) {
                                        { onFiltersChange(FeedFilters()) }
                                    } else if (detail.sourceType == SourceType.YOUTUBE_CHANNEL) {
                                        onOpenSettings
                                    } else {
                                        null
                                    },
                            )
                        }
                    }

                    // 08's Failed shape for a first-page error — the header and chips stay
                    // visible and the state sits where the episodes would.
                    if (items.loadState.refresh is LoadState.Error && items.itemCount == 0) {
                        item(key = "refreshError", contentType = "status") {
                            EmptyState(
                                icon = NdIcons.Error,
                                title = stringResource(Res.string.feeds_load_error),
                                body = "",
                                actionLabel = stringResource(Res.string.action_retry),
                                onAction = items::retry,
                            )
                        }
                    }

                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.id },
                        contentType = { "episode" },
                    ) { index ->
                        val row = items[index]
                        if (row == null) {
                            // 05's placeholders report the full count ahead of the loaded pages:
                            // the stub must keep a row's height or a restored scroll index past
                            // the loaded page slides back to wherever real rows fill the view.
                            EpisodeRowPlaceholder()
                        } else {
                            val rowLive by remember(row.id, live) { derivedStateOf { live.value[row.id] } }
                            EpisodeRow(
                                row = row,
                                live = rowLive,
                                style = EpisodeRowStyle.PODCAST,
                                caps = state.rowCaps.forSource(row.sourceType).copy(offline = state.offline),
                                highlightNew = false,
                                selected = selectedIds.takeIf { it.isNotEmpty() }?.contains(row.id),
                                onAction = { action ->
                                    when {
                                        action is EpisodeAction.ExtendSelection && selection != null -> {
                                            selection.extend(
                                                action.episodeId,
                                                items.itemSnapshotList.items.map { it.id },
                                            )
                                        }

                                        action is EpisodeAction.Select && selection != null -> {
                                            selection.toggle(action.episodeId)
                                        }

                                        else -> {
                                            onAction(action)
                                        }
                                    }
                                },
                            )
                        }
                    }

                    if (detail.hasOlderPages) {
                        item(key = "loadOlder", contentType = "footer") {
                            Box(
                                Modifier.fillMaxWidth().padding(FOOTER_PADDING),
                                contentAlignment = Alignment.Center,
                            ) {
                                NdTextButton(
                                    label = stringResource(Res.string.podcast_load_older),
                                    onClick = onLoadOlder,
                                    enabled = !state.refreshing,
                                )
                            }
                        }
                    }
                    if (items.loadState.append is LoadState.Loading) {
                        item(key = "appendLoading", contentType = "footer") {
                            Box(
                                Modifier.fillMaxWidth().padding(FOOTER_PADDING),
                                contentAlignment = Alignment.Center,
                            ) {
                                NdLoading()
                            }
                        }
                    }
                }
            }
        }
    }

    pendingUnsubscribe?.let { pending ->
        NdDialog(
            onDismissRequest = onDismissUnsubscribe,
            icon = NdIcons.Error,
            title = stringResource(Res.string.podcast_unsubscribe_title, pending.title),
            text =
                pluralStringResource(
                    Res.plurals.podcast_unsubscribe_downloads,
                    pending.downloads,
                    pending.downloads,
                ),
            confirm =
                NdDialogAction(
                    stringResource(Res.string.podcast_unsubscribe),
                    role = NdActionRole.DESTRUCTIVE,
                    onClick = onConfirmUnsubscribe,
                ),
            dismiss =
                NdDialogAction(stringResource(Res.string.action_cancel), onClick = onDismissUnsubscribe),
        )
    }
}

/**
 * 08's overflow: settings, share/copy of the website link, "Watch on YouTube" / "Open website",
 * "Copy feed address" (a private feed warns first), mark all played, unsubscribe. Desktop
 * platforms have no share sheet, so the item degrades to "Copy link" (08 Overflow).
 */
@Composable
private fun PodcastOverflow(
    detail: PodcastDetail?,
    caps: RowCaps,
    feedUrl: String?,
    onOpenSettings: () -> Unit,
    onMarkAllPlayedClick: () -> Unit,
    onDownloadAll: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirmPrivateCopy by remember { mutableStateOf(false) }
    val actions = LocalPlatformActions.current
    val openUrl = rememberUrlOpener()
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val copiedLabel = stringResource(Res.string.ps_feed_copied)
    val link = detail?.link

    Box {
        NdIconButton(
            onClick = { open = true },
            icon = NdIcons.MoreVert,
            contentDescription = stringResource(Res.string.action_more),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (detail?.sourceType == SourceType.RSS || caps.downloads) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.download_all)) }, onClick = {
                    open = false
                    onDownloadAll()
                })
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_settings)) },
                onClick = {
                    open = false
                    onOpenSettings()
                },
            )
            if (link != null) {
                val shareLabel =
                    stringResource(
                        if (actions.share != null) {
                            Res.string.action_share
                        } else {
                            Res.string.action_copy_link
                        },
                    )
                DropdownMenuItem(
                    text = { Text(shareLabel) },
                    onClick = {
                        open = false
                        val share = actions.share
                        if (share != null) {
                            share.shareText(link, detail.displayTitle)
                        } else {
                            clipboard.setText(AnnotatedString(link))
                            scope.launch { snackbar.showSnackbar(copiedLabel) }
                        }
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (detail.sourceType == SourceType.RSS) {
                                    Res.string.action_open_website
                                } else {
                                    Res.string.action_watch_on_youtube
                                },
                            ),
                        )
                    },
                    onClick = {
                        open = false
                        openUrl(link)
                    },
                )
            }
            if (feedUrl != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.podcast_copy_feed)) },
                    onClick = {
                        open = false
                        if (detail?.isPrivate == true) {
                            confirmPrivateCopy = true
                        } else {
                            clipboard.setText(AnnotatedString(feedUrl))
                            scope.launch { snackbar.showSnackbar(copiedLabel) }
                        }
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_mark_played)) },
                onClick = {
                    open = false
                    onMarkAllPlayedClick()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_unsubscribe)) },
                // Without a detail there is no feed to leave — the top bar shows early.
                enabled = detail != null,
                onClick = {
                    open = false
                    onUnsubscribe()
                },
            )
        }
    }

    // 03 Feed addresses: the private feed's URL grants read access — copy needs a warning first.
    if (confirmPrivateCopy && feedUrl != null) {
        NdDialog(
            onDismissRequest = { confirmPrivateCopy = false },
            icon = NdIcons.Lock,
            title = stringResource(Res.string.podcast_copy_feed),
            text = stringResource(Res.string.podcast_copy_feed_warning),
            confirm =
                NdDialogAction(stringResource(Res.string.action_copy)) {
                    confirmPrivateCopy = false
                    clipboard.setText(AnnotatedString(feedUrl))
                    scope.launch { snackbar.showSnackbar(copiedLabel) }
                },
            dismiss =
                NdDialogAction(stringResource(Res.string.action_cancel)) {
                    confirmPrivateCopy = false
                },
        )
    }
}

/**
 * 03's per-feed states as the banner between header and chips (08 Podcast detail): credentials,
 * gone, possibly dead and pending each have their own copy and action; a bare failure shows
 * `FeedErrorText`. Only the most actionable one renders.
 */
@Composable
private fun FeedStateBanner(
    detail: PodcastDetail,
    nowMs: Long,
    onRetryFeed: () -> Unit,
    onEnterCredentials: (BasicCredentials) -> Unit,
    onOpenSettings: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val health = detail.health
    var credentialsOpen by remember { mutableStateOf(false) }

    when {
        health.needsCredentials -> {
            NdBanner(
                message = stringResource(Res.string.podcast_needs_password),
                icon = NdIcons.Key,
                primary =
                    NdDialogAction(
                        stringResource(Res.string.podcast_enter_password),
                    ) { credentialsOpen = true },
            )
        }

        health.gone -> {
            NdBanner(
                message = stringResource(Res.string.podcast_gone),
                icon = NdIcons.Error,
                primary =
                    NdDialogAction(stringResource(Res.string.ps_edit_url), onClick = onOpenSettings),
                secondary =
                    NdDialogAction(stringResource(Res.string.podcast_unsubscribe), onClick = onUnsubscribe),
            )
        }

        health.possiblyDead -> {
            NdBanner(
                // A feed that never fetched successfully has no `lastSuccessAt` to format —
                // 03 anchors PossiblyDead on COALESCE(lastSuccessAt, subscribedAt) and the
                // detail model does not carry `subscribedAt`, so the copy goes date-less.
                message =
                    health.lastSuccessAt
                        ?.let {
                            stringResource(
                                Res.string.podcast_maybe_moved,
                                FeedDates.relative(it, nowMs).asString(),
                            )
                        }
                        ?: stringResource(Res.string.podcast_maybe_moved_never),
                icon = NdIcons.Error,
                primary =
                    NdDialogAction(stringResource(Res.string.action_retry), onClick = onRetryFeed),
                secondary =
                    NdDialogAction(stringResource(Res.string.ps_edit_url), onClick = onOpenSettings),
            )
        }

        detail.status == PodcastStatus.PENDING_FIRST_FETCH && health.lastErrorKind == null -> {
            NdBanner(
                message = stringResource(Res.string.podcast_fetching),
                icon = NdIcons.ProgressActivity,
            )
        }

        health.lastErrorKind != null -> {
            NdBanner(
                message = FeedErrorText.describe(health.lastErrorKind!!).asString(),
                icon = NdIcons.Error,
                primary =
                    NdDialogAction(stringResource(Res.string.action_retry), onClick = onRetryFeed),
            )
        }
    }

    if (credentialsOpen) {
        CredentialsDialog(
            onSubmit = {
                credentialsOpen = false
                onEnterCredentials(it)
            },
            onDismiss = { credentialsOpen = false },
        )
    }
}

/** The transient chips plus the persisted order chip (08: "Newest v" writes `episodeOrder`). */
@Composable
private fun ChipsRow(
    filters: FeedFilters,
    order: FeedOrder,
    onFiltersChange: (FeedFilters) -> Unit,
    onOrderChange: (FeedOrder) -> Unit,
) {
    var orderOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FeedFilterChips(
            availableFilters = AVAILABLE_FEED_FILTER_CAPABILITIES,
            filters = filters,
            onFiltersChange = onFiltersChange,
            modifier = Modifier.weight(1f),
        )
        Box {
            NdFilterChip(
                selected = false,
                onClick = { orderOpen = true },
                label =
                    stringResource(
                        if (order == FeedOrder.NEWEST_FIRST) {
                            Res.string.order_newest
                        } else {
                            Res.string.order_oldest
                        },
                    ),
                leadingIcon = NdIcons.Sort,
            )
            DropdownMenu(expanded = orderOpen, onDismissRequest = { orderOpen = false }) {
                for (option in FeedOrder.entries) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (option == FeedOrder.NEWEST_FIRST) {
                                        Res.string.order_newest
                                    } else {
                                        Res.string.order_oldest
                                    },
                                ),
                            )
                        },
                        leadingIcon = {
                            if (option == order) {
                                Icon(NdIcons.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            orderOpen = false
                            onOrderChange(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 03 Basic auth: the "Enter password" dialog of the needs-credentials banner. `internal` so
 * desktop tests can drive the control.
 */
@Composable
internal fun CredentialsDialog(
    onSubmit: (BasicCredentials) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    NdDialog(
        onDismissRequest = onDismiss,
        icon = NdIcons.Key,
        title = stringResource(Res.string.add_auth_title),
        confirm =
            NdDialogAction(stringResource(Res.string.action_save)) { onSubmit(BasicCredentials(username, password)) },
        dismiss = NdDialogAction(stringResource(Res.string.action_cancel), onClick = onDismiss),
    ) {
        CredentialFields(
            username = username,
            onUsernameChange = { username = it },
            password = password,
            onPasswordChange = { password = it },
        )
    }
}

/**
 * One skeleton stand-in for an unloaded paging placeholder (08: placeholders render skeleton
 * rows): the PODCAST leading slot's 48 dp block plus title/meta bars, at the row's 72 dp
 * height so unloaded items hold their scroll position (UI review round 2).
 */
@Composable
private fun EpisodeRowPlaceholder() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(PLACEHOLDER_GAP),
    ) {
        Box(
            Modifier
                .size(PLACEHOLDER_LEAD)
                .clip(NeutrodyneShapes.Tile)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        Column(verticalArrangement = Arrangement.spacedBy(PLACEHOLDER_BAR_GAP)) {
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(PLACEHOLDER_BAR_HEIGHT)
                    .clip(RoundedCornerShape(PLACEHOLDER_BAR_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.4f)
                    .height(PLACEHOLDER_BAR_HEIGHT)
                    .clip(RoundedCornerShape(PLACEHOLDER_BAR_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
        }
    }
}

/**
 * The paged list's load error, pinned where the user is (08's paging-error rule, revised
 * 2026-10-14): a refresh failure with episodes still shown, or an append/prepend failure,
 * surfaces as this banner under the app bar. There is no append footer row — placeholders
 * can park it thousands of rows away, a prepend failure never had one, and showing both
 * duplicated the error at the list end. Retry replays every failed load via
 * `items.retry()`; the empty first-page failure keeps the in-list error state instead.
 */
@Composable
private fun LoadErrorBanner(items: LazyPagingItems<*>) {
    val failed =
        items.loadState.prepend is LoadState.Error ||
            items.loadState.append is LoadState.Error ||
            (items.loadState.refresh is LoadState.Error && items.itemCount > 0)
    if (!failed) return
    NdBanner(
        message = stringResource(Res.string.feeds_load_error),
        icon = NdIcons.Error,
        primary = NdDialogAction(stringResource(Res.string.action_retry), onClick = items::retry),
    )
}

/** The header placeholder while `observePodcast` has not emitted (08's skeleton). */
@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize().padding(LOADING_PADDING), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NdLoading()
            Text(
                stringResource(Res.string.podcast_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LOADING_TEXT_GAP),
            )
        }
    }
}

private val FOOTER_PADDING = 16.dp
private val LOADING_PADDING = 32.dp
private val LOADING_TEXT_GAP = 16.dp

private val PLACEHOLDER_HEIGHT = 72.dp
private val PLACEHOLDER_LEAD = 48.dp
private val PLACEHOLDER_GAP = 12.dp
private val PLACEHOLDER_BAR_GAP = 8.dp
private val PLACEHOLDER_BAR_HEIGHT = 14.dp
private val PLACEHOLDER_BAR_CORNER = 4.dp
