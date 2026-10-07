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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_check_again
import ch.lkmc.neutrodyne.core.ui.resources.action_delete_download
import ch.lkmc.neutrodyne.core.ui.resources.action_download
import ch.lkmc.neutrodyne.core.ui.resources.action_download_now
import ch.lkmc.neutrodyne.core.ui.resources.action_go_to_podcast
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_unplayed
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.action_open
import ch.lkmc.neutrodyne.core.ui.resources.action_pause
import ch.lkmc.neutrodyne.core.ui.resources.action_play_again
import ch.lkmc.neutrodyne.core.ui.resources.action_play_last
import ch.lkmc.neutrodyne.core.ui.resources.action_play_next
import ch.lkmc.neutrodyne.core.ui.resources.action_resume
import ch.lkmc.neutrodyne.core.ui.resources.action_retry_download
import ch.lkmc.neutrodyne.core.ui.resources.action_watch_on_youtube
import ch.lkmc.neutrodyne.core.ui.resources.episode_offline_row
import ch.lkmc.neutrodyne.core.ui.resources.episode_opens_youtube
import ch.lkmc.neutrodyne.core.ui.resources.summary_downloading
import ch.lkmc.neutrodyne.core.ui.resources.summary_minutes_left
import ch.lkmc.neutrodyne.core.ui.resources.summary_percent_played
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The shared episode row (08 EpisodeRow): [EpisodeRowStyle] picks the leading slot (feed art vs the
 * podcast screen's date block), [RowCaps] the platform/capability gates, [RowLive] the overlay a
 * live download or the player adds. The row is one merged semantics node described by
 * [EpisodeRowSummary], with the menu entries mirrored as custom actions; inner buttons clear their
 * semantics so a screen reader stops once per row.
 *
 * Swipe: `caps.swipe` is always `null` at M1a (08's `feeds_row_swipe` setting lands with M2), so no
 * swipe container wraps the row yet. A secondary click (desktop, mice on Android) or the
 * hover/focus overflow button opens the action menu; long-press dispatches `Select` (selection
 * mode itself is M2).
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
) {
    val nowMs = LocalUiClock.current.now()
    val downloadState = live?.downloadState ?: row.downloadState
    val dimmed =
        (caps.offline && downloadState != DownloadState.COMPLETED) ||
            row.availability != Availability.AVAILABLE
    val summary = EpisodeRowSummary.describe(row, live, nowMs, caps).asString()
    val stateDesc = rowStateDescription(row, live)
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }

    val container =
        if (selected == true || live?.isNowPlaying == true) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val menuActions = rowMenuActions(row, caps, downloadState)

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_MIN_HEIGHT)
                    .background(container)
                    .hoverable(interactionSource)
                    .onFocusChanged { focused = it.isFocused }
                    .onSecondaryClick { menuOpen = true }
                    .semantics(mergeDescendants = true) {
                        contentDescription = summary
                        stateDescription = stateDesc
                        customActions =
                            menuActions.map { (label, action) ->
                                CustomAccessibilityAction(label) {
                                    onAction(action)
                                    true
                                }
                            }
                    }.combinedClickable(
                        onClick = { onAction(EpisodeAction.Open(row.id)) },
                        onLongClick = { onAction(EpisodeAction.Select(row.id)) },
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
            TrailingSlot(row, live, caps, onAction)
        }

        if (hovered || focused || menuOpen) {
            val more = stringResource(Res.string.action_more)
            NdIconButton(
                onClick = { menuOpen = true },
                icon = NdIcons.MoreVert,
                contentDescription = more,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = OVERFLOW_END_PAD)
                        .clearAndSetSemantics {},
            )
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menuActions.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuOpen = false
                            onAction(action)
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
    caps: RowCaps,
    downloadState: DownloadState?,
): List<Pair<String, EpisodeAction>> {
    val actions = mutableListOf<Pair<String, EpisodeAction>>()
    actions += stringResource(Res.string.action_open) to EpisodeAction.Open(row.id)
    if (row.playedAt == null) {
        actions += stringResource(Res.string.action_mark_played) to
            EpisodeAction.SetPlayed(row.id, played = true)
    } else {
        actions += stringResource(Res.string.action_mark_unplayed) to
            EpisodeAction.SetPlayed(row.id, played = false)
    }
    actions += stringResource(Res.string.action_play_next) to EpisodeAction.PlayNext(row.id)
    actions += stringResource(Res.string.action_play_last) to EpisodeAction.PlayLast(row.id)
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

        else -> {
            actions += stringResource(Res.string.action_download_now) to
                EpisodeAction.DownloadToggle(row.id)
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
    val videoId = row.externalMediaId
    if (row.sourceType != SourceType.RSS && videoId != null) {
        actions += stringResource(Res.string.action_watch_on_youtube) to
            EpisodeAction.WatchOnYouTube(row.id, videoId)
    }
    actions += stringResource(Res.string.action_go_to_podcast) to
        EpisodeAction.OpenPodcast(row.id, row.podcastId)
    return actions
}

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
    val duration = live?.durationMs ?: row.durationMs ?: return ""
    if (duration <= 0) return ""
    return pluralStringResource(
        Res.plurals.summary_percent_played,
        ((position * PERCENT) / duration).toInt().coerceIn(0, 100),
        (position * PERCENT / duration).toInt().coerceIn(0, 100),
    )
}

@Composable
private fun LeadingSlot(
    row: EpisodeRow,
    style: EpisodeRowStyle,
) {
    when (style) {
        EpisodeRowStyle.FEED, EpisodeRowStyle.QUEUE -> {
            if (row.sourceType != SourceType.RSS) {
                // YouTube rows show the 16:9 thumbnail (08; CHANNEL_AVATAR arrives with M8's setting).
                CoverArt(
                    ref = row.artwork,
                    monogram = rememberMonogram(row.title),
                    modifier = Modifier.size(width = VIDEO_THUMB_WIDTH, height = COVER_SIZE),
                    aspect = CoverAspect.WIDE_16_9,
                    avgArgb = row.artworkAvgArgb,
                )
            } else {
                CoverArt(
                    ref = row.artwork,
                    monogram = rememberMonogram(row.podcastTitle),
                    modifier = Modifier.size(COVER_SIZE),
                    avgArgb = row.artworkAvgArgb,
                )
            }
        }

        EpisodeRowStyle.PODCAST -> {
            if (row.artwork.key != row.podcastArtwork.key) {
                CoverArt(
                    ref = row.artwork,
                    monogram = rememberMonogram(row.podcastTitle),
                    modifier = Modifier.size(COVER_SIZE),
                    avgArgb = row.artworkAvgArgb,
                )
            } else {
                DateBlock(row)
            }
        }
    }
}

/** The PODCAST style's 48 dp date block: day `titleMedium` over month `labelSmall` (08). */
@Composable
private fun DateBlock(row: EpisodeRow) {
    val epochMs = row.pubDate ?: row.sortDate
    val (day, month) = remember(epochMs) { FeedDates.dayMonth(epochMs) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size(DATE_BLOCK_SIZE),
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
    val duration = FeedDates.duration(live?.durationMs ?: row.durationMs)
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
    val duration = live?.durationMs ?: row.durationMs ?: return
    if (duration <= 0) return
    val position = live?.positionMs ?: return
    val left = ((duration - position).coerceAtLeast(0) + MS_PER_MINUTE - 1) / MS_PER_MINUTE
    Row(verticalAlignment = Alignment.CenterVertically) {
        NdProgress.Linear(
            progress = (position.toFloat() / duration).coerceIn(0f, 1f),
            modifier = Modifier.weight(1f).padding(top = PROGRESS_TOP_GAP),
        )
        Text(
            pluralStringResource(Res.plurals.summary_minutes_left, left.toInt(), left),
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

/** Trailing download button then play button, each ≥ 48 dp (08). */
@Composable
private fun TrailingSlot(
    row: EpisodeRow,
    live: RowLive?,
    caps: RowCaps,
    onAction: (EpisodeAction) -> Unit,
) {
    val downloadState = live?.downloadState ?: row.downloadState
    if (row.sourceType != SourceType.RSS && !caps.inAppPlayback) {
        NdIconButton(
            onClick = {
                row.externalMediaId?.let { id -> onAction(EpisodeAction.WatchOnYouTube(row.id, id)) }
            },
            icon = NdIcons.OpenInNew,
            contentDescription = stringResource(Res.string.action_watch_on_youtube),
            modifier = Modifier.clearAndSetSemantics {},
        )
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
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
        val (icon, label) =
            when {
                playing -> {
                    NdIcons.Pause to stringResource(Res.string.action_pause)
                }

                live?.isNowPlaying == true || row.startedAt != null -> {
                    NdIcons.PlayArrow to stringResource(Res.string.action_resume)
                }

                row.playedAt != null -> {
                    NdIcons.PlayArrow to stringResource(Res.string.action_play_again)
                }

                else -> {
                    NdIcons.PlayArrow to stringResource(Res.string.action_open)
                }
            }
        NdIconButton(
            onClick = { onAction(EpisodeAction.PlayToggle(row.id)) },
            icon = icon,
            contentDescription = label,
            modifier = Modifier.clearAndSetSemantics {},
            enabled = !caps.offline || downloadState == DownloadState.COMPLETED,
        )
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

    Box(
        modifier = modifier.size(TOUCH_TARGET).clickable(onClick = onClick),
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
                CircularProgressIndicator(modifier = Modifier.size(ICON_SIZE + RING_PAD))
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
                Icon(
                    NdIcons.Error,
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
private val OVERFLOW_END_PAD = 4.dp
private val PROGRESS_TOP_GAP = 4.dp
private val PROGRESS_LABEL_GAP = 8.dp
private const val DIMMED_ALPHA = 0.6f
private const val PERCENT = 100L
private const val MS_PER_MINUTE = 60_000L
