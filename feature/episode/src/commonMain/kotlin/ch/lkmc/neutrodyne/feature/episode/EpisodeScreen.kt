// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.DateFormatter
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverAspect
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdLoading
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.ui.AvailabilityText
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.NavBackButton
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.offlineBannerItem
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.action_download
import ch.lkmc.neutrodyne.core.ui.resources.action_favorite
import ch.lkmc.neutrodyne.core.ui.resources.action_go_to_podcast
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_unplayed
import ch.lkmc.neutrodyne.core.ui.resources.action_more
import ch.lkmc.neutrodyne.core.ui.resources.action_open_website
import ch.lkmc.neutrodyne.core.ui.resources.action_play
import ch.lkmc.neutrodyne.core.ui.resources.action_play_again
import ch.lkmc.neutrodyne.core.ui.resources.action_play_last
import ch.lkmc.neutrodyne.core.ui.resources.action_play_next
import ch.lkmc.neutrodyne.core.ui.resources.action_share
import ch.lkmc.neutrodyne.core.ui.resources.action_unfavorite
import ch.lkmc.neutrodyne.core.ui.resources.action_up_next
import ch.lkmc.neutrodyne.core.ui.resources.action_watch_on_youtube
import ch.lkmc.neutrodyne.core.ui.resources.episode_loading
import ch.lkmc.neutrodyne.core.ui.resources.episode_no_notes
import ch.lkmc.neutrodyne.core.ui.resources.episode_not_found
import ch.lkmc.neutrodyne.core.ui.resources.episode_offline_row
import ch.lkmc.neutrodyne.core.ui.resources.episode_offline_toast
import ch.lkmc.neutrodyne.core.ui.resources.episode_opens_youtube
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.resources.summary_downloaded
import ch.lkmc.neutrodyne.core.ui.resources.summary_video
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.showNotes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The episode detail screen (08 Episode detail): a 120 dp cover beside the title (YouTube renders
 * its art 16:9 full width instead), the meta line, the action row and the sanitised show notes as
 * one lazy item per block. Play, download and Up-next actions dispatch to the route's handler and
 * stay inert until M4/M6; timestamp taps wait for M5's seek — both deviations recorded in 08.
 */
@Composable
internal fun EpisodeScreen(
    state: EpisodeUiState,
    onAction: (EpisodeAction) -> Unit,
    onFavorite: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = state.episode
    val urls = LocalPlatformActions.current.urls
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val noHandler = stringResource(Res.string.no_handler)
    var imagesRevealed by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        NdTopAppBar(
            title = "",
            navigation = { NavBackButton() },
            actions = {
                episode?.let { EpisodeChrome(it, onAction, onFavorite) }
            },
        )
        when {
            !state.loaded -> {
                LoadingBody()
            }

            episode == null -> {
                EmptyState(
                    icon = NdIcons.Error,
                    title = stringResource(Res.string.episode_not_found),
                    body = "",
                )
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    offlineBannerItem(state.offline)
                    item(key = "header", contentType = "header") {
                        EpisodeHeader(episode, state, onAction) { message ->
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                    }
                    val notes = state.notes
                    if (notes == null || notes.blocks.isEmpty()) {
                        item(key = "noNotes", contentType = "notes") {
                            Text(
                                stringResource(Res.string.episode_no_notes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(NOTES_PADDING),
                            )
                        }
                    } else {
                        showNotes(
                            notes = notes,
                            imageMode =
                                if (imagesRevealed) {
                                    ShowNotesImageMode.SHOWN
                                } else {
                                    state.imageMode
                                },
                            durationMs = episode.durationMs,
                            onLink = { url ->
                                if (urls.open(url) == OpenResult.NO_HANDLER) {
                                    scope.launch { snackbar.showSnackbar(noHandler) }
                                }
                            },
                            // M5 seeks; M1a timestamps render but do nothing (deviation in 08).
                            onTimestamp = {},
                            onLoadImages = { imagesRevealed = true },
                        )
                    }
                }
            }
        }
    }
}

/** The top bar's share icon and overflow (08 Episode detail; share is "Copy link" on desktop). */
@Composable
private fun EpisodeChrome(
    episode: EpisodeDetail,
    onAction: (EpisodeAction) -> Unit,
    onFavorite: (Boolean) -> Unit,
) {
    val actions = LocalPlatformActions.current
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val noHandler = stringResource(Res.string.no_handler)
    var menuOpen by remember { mutableStateOf(false) }

    val externalMediaId = episode.externalMediaId
    val watchUrl =
        if (episode.sourceType != SourceType.RSS && externalMediaId != null) {
            YouTubeLinks.watch(externalMediaId)
        } else {
            null
        }
    val episodeLink = episode.link
    val shareLink = episodeLink ?: watchUrl

    if (shareLink != null) {
        val share = actions.share
        if (share != null) {
            NdTooltipIconButton(
                onClick = { share.shareText(shareLink, episode.title) },
                icon = NdIcons.Share,
                tooltip = stringResource(Res.string.action_share),
            )
        } else {
            NdTooltipIconButton(
                onClick = { clipboard.setText(AnnotatedString(shareLink)) },
                icon = NdIcons.Link,
                tooltip = stringResource(Res.string.action_copy_link),
            )
        }
    }

    Box {
        NdIconButton(
            onClick = { menuOpen = true },
            icon = NdIcons.MoreVert,
            contentDescription = stringResource(Res.string.action_more),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_go_to_podcast)) },
                onClick = {
                    menuOpen = false
                    onAction(EpisodeAction.OpenPodcast(episode.id, episode.podcastId))
                },
            )
            if (episodeLink != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_open_website)) },
                    onClick = {
                        menuOpen = false
                        if (actions.urls.open(episodeLink) == OpenResult.NO_HANDLER) {
                            scope.launch { snackbar.showSnackbar(noHandler) }
                        }
                    },
                )
            }
            if (watchUrl != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_watch_on_youtube)) },
                    onClick = {
                        menuOpen = false
                        onAction(EpisodeAction.WatchOnYouTube(episode.id, externalMediaId!!))
                    },
                )
            }
            if (shareLink != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_copy_link)) },
                    onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(shareLink))
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (episode.isFavorite) {
                                Res.string.action_unfavorite
                            } else {
                                Res.string.action_favorite
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        if (episode.isFavorite) NdIcons.FavoriteFilled else NdIcons.Favorite,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    onFavorite(!episode.isFavorite)
                },
            )
        }
    }
}

/** The header block: art, title, podcast line, meta, availability and the action row. */
@Composable
private fun EpisodeHeader(
    episode: EpisodeDetail,
    state: EpisodeUiState,
    onAction: (EpisodeAction) -> Unit,
    toast: (String) -> Unit,
) {
    val isYouTube = episode.sourceType != SourceType.RSS
    Column(Modifier.fillMaxWidth().padding(horizontal = H_PADDING)) {
        if (isYouTube) {
            // 08: YouTube art is the 16:9 thumbnail full width above the title.
            CoverArt(
                ref = episode.artwork,
                monogram = rememberMonogram(episode.title),
                title = episode.title,
                tier = CoverTier.HERO,
                aspect = CoverAspect.WIDE_16_9,
                shape = NeutrodyneShapes.Tile,
                avgArgb = null,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                episode.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = TITLE_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = ART_GAP),
            )
            PodcastLine(episode, onAction)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ART_GAP)) {
                CoverArt(
                    ref = episode.artwork,
                    monogram = rememberMonogram(episode.podcastTitle),
                    title = episode.podcastTitle,
                    tier = CoverTier.THUMB,
                    shape = NeutrodyneShapes.Tile,
                    avgArgb = null,
                    modifier = Modifier.size(ART_SIZE),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PodcastLine(episode, onAction)
                }
            }
        }

        Text(
            metaLine(episode).asString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = META_GAP),
        )
        if (isYouTube) {
            Text(
                stringResource(Res.string.episode_opens_youtube),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        episode.availability?.let { availability ->
            if (availability != Availability.AVAILABLE) {
                Text(
                    AvailabilityText.describe(availability).asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ActionRow(episode, state, isYouTube, onAction, toast)
    }
}

/** "Podcast name ›" — the tap target that opens `PodcastKey`. */
@Composable
private fun PodcastLine(
    episode: EpisodeDetail,
    onAction: (EpisodeAction) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clickable {
                    onAction(EpisodeAction.OpenPodcast(episode.id, episode.podcastId))
                }.semantics { role = Role.Button },
    ) {
        Text(
            episode.podcastTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            NdIcons.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(CHEVRON),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "{date} · {duration} · {S2 E14} · Video" — the meta line under the title. */
private fun metaLine(episode: EpisodeDetail): UiText {
    val parts = mutableListOf<UiText>()
    episode.pubDate?.let { parts += UiText.Raw(DateFormatter.date(it)) }
    parts += FeedDates.duration(episode.durationMs)
    episode.episodeDisplay?.let { parts += UiText.Raw(it) }
    if (episode.isVideo) parts += UiText.Res(Res.string.summary_video)
    return UiText.Joined(parts, separator = " · ", suffix = "")
}

/**
 * The action row (08 Episode detail): the primary play affordance — "Watch on YouTube" for
 * YouTube sources while external mode is the only mode (M8 adds the engine's Play), the
 * offline-undownloaded state shows `cloud_off` "Offline" and toasts on tap — plus Download, Up
 * next and the played toggle as icon-label buttons.
 */
@Composable
private fun ActionRow(
    episode: EpisodeDetail,
    state: EpisodeUiState,
    isYouTube: Boolean,
    onAction: (EpisodeAction) -> Unit,
    toast: (String) -> Unit,
) {
    val offlineToast = stringResource(Res.string.episode_offline_toast)
    val downloaded = episode.downloadState == DownloadState.COMPLETED
    val playableOffline = !state.offline || downloaded
    var upNextMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
        modifier = Modifier.padding(top = BUTTONS_TOP),
    ) {
        val mediaId = episode.externalMediaId
        if (isYouTube && mediaId != null) {
            NdButton(onClick = {
                onAction(EpisodeAction.WatchOnYouTube(episode.id, mediaId))
            }) {
                Text(stringResource(Res.string.action_watch_on_youtube))
            }
        } else if (!playableOffline) {
            NdButton(onClick = { toast(offlineToast) }) {
                Icon(
                    NdIcons.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(BUTTON_ICON),
                )
                Text(
                    stringResource(Res.string.episode_offline_row),
                    modifier = Modifier.padding(start = BUTTON_LABEL_GAP),
                )
            }
        } else {
            NdButton(onClick = { onAction(EpisodeAction.PlayToggle(episode.id)) }) {
                Icon(
                    NdIcons.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(BUTTON_ICON),
                )
                Text(
                    stringResource(
                        if (episode.playedAt != null) {
                            Res.string.action_play_again
                        } else {
                            Res.string.action_play
                        },
                    ),
                    modifier = Modifier.padding(start = BUTTON_LABEL_GAP),
                )
            }
        }

        IconLabelButton(
            icon = if (downloaded) NdIcons.DownloadDone else NdIcons.Download,
            label =
                stringResource(
                    if (downloaded) Res.string.summary_downloaded else Res.string.action_download,
                ),
            onClick = { onAction(EpisodeAction.DownloadToggle(episode.id)) },
        )

        Box {
            IconLabelButton(
                icon = NdIcons.QueueMusic,
                label = stringResource(Res.string.action_up_next),
                onClick = { upNextMenu = true },
            )
            DropdownMenu(expanded = upNextMenu, onDismissRequest = { upNextMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_play_next)) },
                    onClick = {
                        upNextMenu = false
                        onAction(EpisodeAction.PlayNext(episode.id))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_play_last)) },
                    onClick = {
                        upNextMenu = false
                        onAction(EpisodeAction.PlayLast(episode.id))
                    },
                )
            }
        }

        IconLabelButton(
            icon = NdIcons.Check,
            label =
                stringResource(
                    if (episode.playedAt != null) {
                        Res.string.action_mark_unplayed
                    } else {
                        Res.string.action_mark_played
                    },
                ),
            onClick = {
                onAction(EpisodeAction.SetPlayed(episode.id, episode.playedAt == null))
            },
        )
    }
}

/** An icon-plus-label text button for the action row (08's "(v) Download (+) Up next (ok)"). */
@Composable
private fun IconLabelButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(BUTTON_ICON))
        Text(label, modifier = Modifier.padding(start = BUTTON_LABEL_GAP))
    }
}

/** The loading skeleton: centred spinner plus the "Loading episode" line. */
@Composable
private fun LoadingBody() {
    Column(
        Modifier.fillMaxSize().padding(LOADING_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NdLoading()
        Text(
            stringResource(Res.string.episode_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = LOADING_TEXT_GAP),
        )
    }
}

private val H_PADDING = 16.dp
private val ART_SIZE = 120.dp
private val ART_GAP = 16.dp
private const val TITLE_LINES = 3
private val META_GAP = 8.dp
private val CHEVRON = 14.dp
private val BUTTON_GAP = 4.dp
private val BUTTONS_TOP = 8.dp
private val BUTTON_ICON = 18.dp
private val BUTTON_LABEL_GAP = 4.dp
private val NOTES_PADDING = 16.dp
private val LOADING_PADDING = 32.dp
private val LOADING_TEXT_GAP = 16.dp
