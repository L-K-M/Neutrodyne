// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverAspect
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdProgress
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.settings.YouTubeRowArt
import ch.lkmc.neutrodyne.core.navigation.LocalNavTab
import ch.lkmc.neutrodyne.core.ui.navigation.SharedKeys
import ch.lkmc.neutrodyne.core.ui.navigation.sharedArt
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel_download
import ch.lkmc.neutrodyne.core.ui.resources.action_check_again
import ch.lkmc.neutrodyne.core.ui.resources.action_delete_download
import ch.lkmc.neutrodyne.core.ui.resources.action_download
import ch.lkmc.neutrodyne.core.ui.resources.action_go_to_podcast
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_unplayed
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.action_open
import ch.lkmc.neutrodyne.core.ui.resources.action_pause
import ch.lkmc.neutrodyne.core.ui.resources.action_pause_download
import ch.lkmc.neutrodyne.core.ui.resources.action_play
import ch.lkmc.neutrodyne.core.ui.resources.action_play_again
import ch.lkmc.neutrodyne.core.ui.resources.action_play_last
import ch.lkmc.neutrodyne.core.ui.resources.action_play_next
import ch.lkmc.neutrodyne.core.ui.resources.action_resume
import ch.lkmc.neutrodyne.core.ui.resources.action_resume_download
import ch.lkmc.neutrodyne.core.ui.resources.action_retry_download
import ch.lkmc.neutrodyne.core.ui.resources.action_select
import ch.lkmc.neutrodyne.core.ui.resources.action_watch_on_youtube
import ch.lkmc.neutrodyne.core.ui.resources.downloads_options
import ch.lkmc.neutrodyne.core.ui.resources.episode_minutes_left
import ch.lkmc.neutrodyne.core.ui.resources.episode_offline_row
import ch.lkmc.neutrodyne.core.ui.resources.episode_opens_youtube
import ch.lkmc.neutrodyne.core.ui.resources.summary_downloading
import ch.lkmc.neutrodyne.core.ui.resources.summary_percent_played
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.selected as semanticsSelected

/**
 * The shared episode row (08 EpisodeRow): [EpisodeRowStyle] picks the leading slot (feed art vs the
 * podcast screen's date block), [RowCaps] the platform/capability gates, [RowLive] the overlay a
 * live download or the player adds. The row is one merged semantics node described by
 * [EpisodeRowSummary], with the menu entries mirrored as custom actions; inner buttons clear their
 * semantics so a screen reader stops once per row.
 *
 * Android swipes honour the screen's capability gate; desktop rows only scroll. Selection
 * gestures dispatch IDs; the screen supplies the loaded order for range selection.
 */
@Composable
public fun EpisodeRow(
    row: EpisodeRow,
    live: RowLive?,
    style: EpisodeRowStyle,
    caps: RowCaps,
    highlightNew: Boolean,
    selected: Boolean?,
    onAction: (EpisodeAction) -> Unit,
    modifier: Modifier = Modifier,
    additionalActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val nowMs = LocalUiClock.current.now()
    // 08 dims the whole row only for availability; offline-and-not-downloaded dims just the
    // play affordance (TrailingSlot's cloud_off at 60 %), the status line carries the reason.
    val dimmed =
        row.availability != Availability.AVAILABLE ||
            (style == EpisodeRowStyle.QUEUE && row.sourceType != SourceType.RSS && !caps.inAppPlayback)
    val summary =
        EpisodeRowSummary.describe(row, live, nowMs, LocalPlatformKind.current, caps).asString()
    val stateDesc = rowStateDescription(row, live)
    var menuOpen by remember(row.id) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val container =
        if (selected == true || live?.isNowPlaying == true) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val menuActions =
        rowMenuActions(row, live, caps, style).map { (label, action) ->
            label to { onAction(action) }
        } + additionalActions

    EpisodeSwipe(
        row,
        caps.swipe,
        onAction,
        modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_MIN_HEIGHT)
                    .background(container)
                    .hoverable(interactionSource)
                    .onFocusChanged { focused = it.isFocused }
                    .focusRequester(focusRequester)
                    .onEpisodePointerGesture(
                        onFocus = { focusRequester.requestFocus() },
                        onMenu = { menuOpen = true },
                        onSelect = { onAction(EpisodeAction.Select(row.id)) },
                        onExtend = { onAction(EpisodeAction.ExtendSelection(row.id)) },
                    ).onPreviewKeyEvent { event ->
                        when {
                            event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed) -> {
                                if (event.type == KeyEventType.KeyDown) menuOpen = true
                                true
                            }

                            (event.key == Key.Enter || event.key == Key.NumPadEnter) && focused -> {
                                if (event.type == KeyEventType.KeyDown) onAction(EpisodeAction.Open(row.id))
                                true
                            }

                            else -> {
                                false
                            }
                        }
                    }.semantics(mergeDescendants = true) {
                        contentDescription = summary
                        stateDescription = stateDesc
                        if (selected != null) semanticsSelected = selected
                        customActions =
                            menuActions.map { (label, action) ->
                                CustomAccessibilityAction(label) {
                                    action()
                                    true
                                }
                            }
                    }.combinedClickable(
                        onClick = {
                            val action =
                                if (selected == null) EpisodeAction.Open(row.id) else EpisodeAction.Select(row.id)
                            onAction(action)
                        },
                        onLongClick = {
                            focusRequester.requestFocus()
                            onAction(EpisodeAction.Select(row.id))
                        },
                    ).alpha(if (dimmed) DIMMED_ALPHA else 1f)
                    .padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
        ) {
            LeadingSlot(row, style)
            Column(
                modifier = Modifier.weight(1f).padding(start = ROW_TEXT_GAP),
                verticalArrangement = Arrangement.Center,
            ) {
                if (style == EpisodeRowStyle.PODCAST && !row.episodeDisplay.isNullOrEmpty()) {
                    Text(
                        row.episodeDisplay.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (highlightNew && row.isNew) {
                        Box(
                            Modifier
                                .padding(end = NEW_DOT_GAP)
                                .size(NEW_DOT_SIZE)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleSmall,
                        color =
                            if (row.playedAt != null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    RowBadges(row)
                }
                MetaLine(row, live, style, nowMs)
                ProgressLine(row, live)
                StatusLine(row, live, caps, nowMs)
            }
            TrailingSlot(
                row = row,
                live = live,
                caps = caps,
                onAction = onAction,
                showOverflow = hovered || focused || menuOpen,
                onOpenMenu = { menuOpen = true },
            )
        }

        Box(Modifier.align(Alignment.CenterEnd)) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menuActions.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

/** The row's action list (08's custom-actions catalogue), shared by menu and semantics. */
@Composable
private fun rowMenuActions(
    row: EpisodeRow,
    live: RowLive?,
    caps: RowCaps,
    style: EpisodeRowStyle,
): List<Pair<String, EpisodeAction>> {
    val downloadState = live?.downloadState ?: row.downloadState
    val actions = mutableListOf<Pair<String, EpisodeAction>>()
    // 08's catalogue leads with the playback action the merged node hides: the trailing
    // button's Play/Pause/Resume, or "Watch on YouTube" in external mode. A YouTube row
    // without a video id shows a disabled affordance, so it offers no action here either.
    val videoId = row.externalMediaId
    if (row.sourceType != SourceType.RSS && !caps.inAppPlayback) {
        if (videoId != null) {
            actions += stringResource(Res.string.action_watch_on_youtube) to
                EpisodeAction.WatchOnYouTube(row.id, videoId)
        }
    } else {
        actions += playActionLabel(row, live) to EpisodeAction.PlayToggle(row.id)
    }
    actions += stringResource(Res.string.action_open) to EpisodeAction.Open(row.id)
    if (style != EpisodeRowStyle.QUEUE) {
        actions += stringResource(Res.string.action_select) to EpisodeAction.Select(row.id)
    }
    if (row.playedAt == null) {
        actions += stringResource(Res.string.action_mark_played) to
            EpisodeAction.SetPlayed(row.id, played = true)
    } else {
        actions += stringResource(Res.string.action_mark_unplayed) to
            EpisodeAction.SetPlayed(row.id, played = false)
    }
    if (row.sourceType == SourceType.RSS || caps.inAppPlayback) {
        actions += stringResource(Res.string.action_play_next) to EpisodeAction.PlayNext(row.id)
        actions += stringResource(Res.string.action_play_last) to EpisodeAction.PlayLast(row.id)
    }
    if (caps.downloads && (row.sourceType == SourceType.RSS || caps.inAppPlayback)) {
        when (downloadState) {
            null -> {
                actions += stringResource(Res.string.action_download) to
                    EpisodeAction.DownloadToggle(row.id)
            }

            DownloadState.COMPLETED -> {
                actions += stringResource(Res.string.action_delete_download) to
                    EpisodeAction.DownloadToggle(row.id)
            }

            DownloadState.FAILED, DownloadState.MISSING -> {
                actions += stringResource(Res.string.action_retry_download) to
                    EpisodeAction.DownloadToggle(row.id)
            }

            // 08's tap table: Cancel while queued or resolving, no download action while
            // verifying, Pause while downloading, Resume while paused. 08's "Download now"
            // while queued is 07's `promote`, which one toggle cannot express next to Cancel;
            // it arrives with M6's download actions.
            DownloadState.QUEUED -> {
                actions += stringResource(Res.string.downloads_options) to
                    EpisodeAction.DownloadToggle(row.id)
            }

            DownloadState.RESOLVING -> {
                actions += stringResource(Res.string.action_cancel_download) to
                    EpisodeAction.DownloadToggle(row.id)
            }

            DownloadState.VERIFYING -> {}

            DownloadState.DOWNLOADING -> {
                actions += stringResource(Res.string.action_pause_download) to
                    EpisodeAction.DownloadToggle(row.id)
            }

            DownloadState.PAUSED -> {
                actions += stringResource(Res.string.action_resume_download) to
                    EpisodeAction.DownloadToggle(row.id)
            }
        }
    }
    if (caps.recheck &&
        (
            row.availability == Availability.REGION_BLOCKED ||
                row.availability == Availability.PRIVATE ||
                row.availability == Availability.UNAVAILABLE
        )
    ) {
        actions += stringResource(Res.string.action_check_again) to
            EpisodeAction.CheckAvailability(row.id)
    }
    // In-app YouTube rows keep a secondary "Watch on YouTube" entry; in external mode the
    // playback slot above already carries it.
    if (row.sourceType != SourceType.RSS && videoId != null && caps.inAppPlayback) {
        actions += stringResource(Res.string.action_watch_on_youtube) to
            EpisodeAction.WatchOnYouTube(row.id, videoId, YouTubeWatchMode.PLAYABLE_OVERFLOW)
    }
    actions += stringResource(Res.string.action_go_to_podcast) to
        EpisodeAction.OpenPodcast(row.id, row.podcastId)
    return actions
}

/**
 * The play affordance's spoken label per 08's playback-state table. Shared by the trailing
 * button and the row's playback custom action so the two never disagree.
 */
@Composable
private fun playActionLabel(
    row: EpisodeRow,
    live: RowLive?,
): String =
    stringResource(
        when {
            live?.isNowPlaying == true && live.isPlaying -> Res.string.action_pause
            live?.isNowPlaying == true || row.startedAt != null -> Res.string.action_resume
            row.playedAt != null -> Res.string.action_play_again
            else -> Res.string.action_play
        },
    )

/** The row's `stateDescription` (08: "40 percent played" / "Downloading, 34 percent"). */
@Composable
private fun rowStateDescription(
    row: EpisodeRow,
    live: RowLive?,
): String {
    val downloadState = live?.downloadState ?: row.downloadState
    if (downloadState == DownloadState.DOWNLOADING) {
        val percent = live?.downloadProgress()?.let { (it * PERCENT).toInt() } ?: 0
        return stringResource(Res.string.summary_downloading, percent)
    }
    val position = live?.positionMs ?: return ""
    // A live duration of 0 is "still resolving", not "no duration" — fall back to the
    // persisted row value rather than hide it (same at the meta and progress lines).
    val duration = live?.durationMs?.takeIf { it > 0 } ?: row.durationMs ?: return ""
    if (duration <= 0) return ""
    val percent = ((position * PERCENT) / duration).toInt().coerceIn(0, 100)
    return pluralStringResource(Res.plurals.summary_percent_played, percent, percent)
}

@Composable
private fun LeadingSlot(
    row: EpisodeRow,
    style: EpisodeRowStyle,
) {
    if (row.sourceType == SourceType.YOUTUBE_CHANNEL) {
        val video = LocalYouTubeRowArt.current == YouTubeRowArt.VIDEO_THUMBNAIL
        CoverArt(
            ref = if (video) row.artwork else row.podcastArtwork,
            monogram = rememberMonogram(if (video) row.title else row.podcastTitle),
            modifier =
                Modifier
                    .size(width = if (video) VIDEO_THUMB_WIDTH else COVER_SIZE, height = COVER_SIZE)
                    .sharedArt(SharedKeys.episodeArt(LocalNavTab.current, row.id)),
            aspect = if (video) CoverAspect.WIDE_16_9 else CoverAspect.SQUARE,
            avgArgb = if (video) row.artworkAvgArgb else row.podcastArtworkAvgArgb,
        )
        return
    }

    when (style) {
        EpisodeRowStyle.FEED, EpisodeRowStyle.QUEUE -> {
            CoverArt(
                ref = row.artwork,
                monogram = rememberMonogram(row.podcastTitle),
                modifier = Modifier.size(COVER_SIZE).sharedArt(SharedKeys.episodeArt(LocalNavTab.current, row.id)),
                avgArgb = row.artworkAvgArgb,
            )
        }

        EpisodeRowStyle.PODCAST -> {
            if (row.artwork.key != row.podcastArtwork.key) {
                CoverArt(
                    ref = row.artwork,
                    monogram = rememberMonogram(row.podcastTitle),
                    modifier = Modifier.size(COVER_SIZE).sharedArt(SharedKeys.episodeArt(LocalNavTab.current, row.id)),
                    avgArgb = row.artworkAvgArgb,
                )
            } else {
                DateBlock(row)
            }
        }
    }
}

/**
 * The PODCAST style's date block: day `titleMedium` over month `labelSmall`, at least 48 dp (08).
 * A minimum, not a fixed size — at 200 % text the two lines grow past 48 dp and a fixed box
 * would clip the month (08's "no fixed heights" accessibility rule).
 */
@Composable
private fun DateBlock(row: EpisodeRow) {
    val epochMs = row.pubDate ?: row.sortDate
    val (day, month) = remember(epochMs) { FeedDates.dayMonth(epochMs) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .defaultMinSize(minWidth = DATE_BLOCK_SIZE, minHeight = DATE_BLOCK_SIZE)
                .testTag(DATE_BLOCK_TAG),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(day, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(
            month,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Inline 16 dp badges after the title (08: check / videocam / smart_display / favorite). */
@Composable
private fun RowBadges(row: EpisodeRow) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row {
        if (row.playedAt != null) {
            Icon(
                NdIcons.Check,
                contentDescription = null,
                modifier = Modifier.padding(start = BADGE_GAP).size(BADGE_SIZE),
                tint = tint,
            )
        }
        if (row.isVideo) {
            Icon(
                NdIcons.Videocam,
                contentDescription = null,
                modifier = Modifier.padding(start = BADGE_GAP).size(BADGE_SIZE),
                tint = tint,
            )
        }
        if (row.sourceType != SourceType.RSS) {
            Icon(
                NdIcons.SmartDisplay,
                contentDescription = null,
                modifier = Modifier.padding(start = BADGE_GAP).size(BADGE_SIZE),
                tint = tint,
            )
        }
        if (row.isFavorite) {
            Icon(
                NdIcons.FavoriteFilled,
                contentDescription = null,
                modifier = Modifier.padding(start = BADGE_GAP).size(BADGE_SIZE),
                tint = tint,
            )
        }
    }
}

@Composable
private fun MetaLine(
    row: EpisodeRow,
    live: RowLive?,
    style: EpisodeRowStyle,
    nowMs: Long,
) {
    val date = FeedDates.dayLabel(row.pubDate ?: row.sortDate, nowMs)
    val duration = FeedDates.duration(live?.durationMs?.takeIf { it > 0 } ?: row.durationMs)
    val meta =
        when (style) {
            EpisodeRowStyle.FEED, EpisodeRowStyle.QUEUE -> {
                UiText.Joined(
                    listOf(UiText.Raw(row.podcastTitle), date, duration),
                    separator = " · ",
                    suffix = "",
                )
            }

            EpisodeRowStyle.PODCAST -> {
                duration
            }
        }.asString()
    if (meta.isEmpty()) return
    Text(
        meta,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The 3 dp progress line plus "{n} min left" (08: shown while started and unplayed). */
@Composable
private fun ProgressLine(
    row: EpisodeRow,
    live: RowLive?,
) {
    if (row.startedAt == null || row.playedAt != null) return
    val duration = live?.durationMs?.takeIf { it > 0 } ?: row.durationMs ?: return
    if (duration <= 0) return
    val position = live?.positionMs ?: return
    val left = ((duration - position).coerceAtLeast(0) + MS_PER_MINUTE - 1) / MS_PER_MINUTE
    Row(verticalAlignment = Alignment.CenterVertically) {
        NdProgress.Linear(
            progress = (position.toFloat() / duration).coerceIn(0f, 1f),
            modifier = Modifier.weight(1f).padding(top = PROGRESS_TOP_GAP),
        )
        Text(
            pluralStringResource(Res.plurals.episode_minutes_left, left.toInt(), left),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = PROGRESS_LABEL_GAP),
        )
    }
}

/** The first of: unavailable reason, offline marker, external marker, download text (08). */
@Composable
private fun StatusLine(
    row: EpisodeRow,
    live: RowLive?,
    caps: RowCaps,
    nowMs: Long,
) {
    val downloadState = live?.downloadState ?: row.downloadState
    val text: String? =
        when {
            row.availability != Availability.AVAILABLE -> {
                AvailabilityText.describe(row.availability).asString()
            }

            caps.offline && downloadState != DownloadState.COMPLETED -> {
                stringResource(Res.string.episode_offline_row)
            }

            row.sourceType != SourceType.RSS && !caps.inAppPlayback -> {
                stringResource(Res.string.episode_opens_youtube)
            }

            else -> {
                DownloadStatusText
                    .describe(
                        state = downloadState,
                        waitReason = live?.waitReason,
                        progress = live?.downloadProgress(),
                        error = live?.lastError,
                        nextAttemptAt = live?.nextAttemptAt,
                        nowMs = nowMs,
                        platform = LocalPlatformKind.current,
                    )?.asString()
            }
        }
    if (text.isNullOrEmpty()) return
    val failed = downloadState == DownloadState.FAILED || downloadState == DownloadState.MISSING
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color =
            if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Trailing download button then play button, each ≥ 48 dp (08). The hover/focus overflow button
 * composes in-flow as the last child — a `CenterEnd` overlay on the row would sit on top of the
 * play affordance and win hit-testing exactly when a mouse user reaches for it.
 */
@Composable
private fun TrailingSlot(
    row: EpisodeRow,
    live: RowLive?,
    caps: RowCaps,
    onAction: (EpisodeAction) -> Unit,
    showOverflow: Boolean,
    onOpenMenu: () -> Unit,
) {
    val downloadState = live?.downloadState ?: row.downloadState
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (row.sourceType != SourceType.RSS && !caps.inAppPlayback) {
            // External mode (08): `open_in_new` replaces both buttons; a row without a video id
            // (malformed/legacy YouTube rows) gets a disabled affordance rather than a dead one.
            NdIconButton(
                onClick = {
                    row.externalMediaId?.let { id ->
                        onAction(EpisodeAction.WatchOnYouTube(row.id, id))
                    }
                },
                icon = NdIcons.OpenInNew,
                contentDescription = stringResource(Res.string.action_watch_on_youtube),
                modifier = Modifier.clearAndSetSemantics {},
                enabled = row.externalMediaId != null,
            )
        } else {
            if (caps.downloads) {
                DownloadStateButton(
                    state = downloadState,
                    waitReason = live?.waitReason,
                    progress = live?.downloadProgress(),
                    onClick = { onAction(EpisodeAction.DownloadToggle(row.id)) },
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }

            val playing = live?.isNowPlaying == true && live.isPlaying
            val icon = if (playing) NdIcons.Pause else NdIcons.PlayArrow
            val label = playActionLabel(row, live)
            // Offline and not downloaded: the affordance stays enabled as `cloud_off` at 60 %
            // alpha (08's row table) — the tap still dispatches PlayToggle so the screen answers
            // with the offline snackbar instead of a dead button.
            val offlineBlocked = caps.offline && downloadState != DownloadState.COMPLETED
            NdIconButton(
                onClick = { onAction(EpisodeAction.PlayToggle(row.id)) },
                icon = if (offlineBlocked) NdIcons.CloudOff else icon,
                contentDescription = label,
                modifier =
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(if (offlineBlocked) DIMMED_ALPHA else 1f),
            )
        }

        if (showOverflow) {
            NdIconButton(
                onClick = onOpenMenu,
                icon = NdIcons.MoreVert,
                contentDescription = stringResource(Res.string.action_more),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * The download button's icon per 08's state table: an indeterminate ring around `schedule` for
 * queued/waiting, determinate around `stop` while downloading, filled `download_done` when done,
 * `error` on failure. Tap dispatches `DownloadToggle`; the screen routes it (07).
 */
@Composable
public fun DownloadStateButton(
    state: DownloadState?,
    waitReason: WaitReason?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        DownloadStatusText
            .describe(
                state = state,
                waitReason = waitReason,
                progress = progress,
                error = null,
                nextAttemptAt = null,
                nowMs = LocalUiClock.current.now(),
                platform = LocalPlatformKind.current,
            )?.asString() ?: stringResource(Res.string.action_download)
    // RESOLVING/VERIFYING have no status text (DownloadStatusText returns null) — the tap
    // cancels, so the bare ring carries the cancel label rather than the "Download" fallback.
    val spinnerLabel = stringResource(Res.string.action_cancel_download)

    Box(
        modifier = modifier.size(TOUCH_TARGET).clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            null -> {
                Icon(NdIcons.Download, contentDescription = label, modifier = Modifier.size(ICON_SIZE))
            }

            DownloadState.QUEUED -> {
                CircularProgressIndicator(modifier = Modifier.size(ICON_SIZE + RING_PAD))
                Icon(NdIcons.Schedule, contentDescription = label, modifier = Modifier.size(ICON_INNER))
            }

            DownloadState.RESOLVING, DownloadState.VERIFYING -> {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .size(ICON_SIZE + RING_PAD)
                            .semantics { contentDescription = spinnerLabel },
                )
            }

            DownloadState.DOWNLOADING -> {
                if (progress == null) {
                    CircularProgressIndicator(modifier = Modifier.size(ICON_SIZE + RING_PAD))
                } else {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(ICON_SIZE + RING_PAD),
                    )
                }
                Icon(NdIcons.Close, contentDescription = label, modifier = Modifier.size(ICON_INNER))
            }

            DownloadState.PAUSED -> {
                Icon(NdIcons.Pause, contentDescription = label, modifier = Modifier.size(ICON_SIZE))
            }

            DownloadState.COMPLETED -> {
                Icon(
                    NdIcons.DownloadDone,
                    contentDescription = label,
                    modifier = Modifier.size(ICON_SIZE),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            DownloadState.FAILED -> {
                // 08's state table: FAILED is the filled `error` in the error colour; MISSING
                // below keeps the untinted outline glyph.
                Icon(
                    NdIcons.ErrorFilled,
                    contentDescription = label,
                    modifier = Modifier.size(ICON_SIZE),
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            DownloadState.MISSING -> {
                Icon(NdIcons.Error, contentDescription = label, modifier = Modifier.size(ICON_SIZE))
            }
        }
    }
}

private val ROW_MIN_HEIGHT = 72.dp
private val ROW_PADDING_H = 16.dp
private val ROW_PADDING_V = 8.dp
private val COVER_SIZE = 56.dp
private val VIDEO_THUMB_WIDTH = 100.dp
private val DATE_BLOCK_SIZE = 48.dp
private val ROW_TEXT_GAP = 12.dp
private val NEW_DOT_SIZE = 8.dp
private val NEW_DOT_GAP = 6.dp
private val BADGE_SIZE = 16.dp
private val BADGE_GAP = 4.dp
private val ICON_SIZE = 24.dp
private val ICON_INNER = 14.dp
private val RING_PAD = 6.dp
private val TOUCH_TARGET = 48.dp
private val PROGRESS_TOP_GAP = 4.dp
private val PROGRESS_LABEL_GAP = 8.dp

/** Semantics tag locating the PODCAST date block in UI tests. */
internal const val DATE_BLOCK_TAG = "episode_date_block"
private const val DIMMED_ALPHA = 0.6f
private const val PERCENT = 100L
private const val MS_PER_MINUTE = 60_000L
