// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdAssistChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastPreviewKey
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.PodcastDescription
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_cancel
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.add_already_subscribed
import ch.lkmc.neutrodyne.core.ui.resources.add_empty_feed
import ch.lkmc.neutrodyne.core.ui.resources.add_episodes_count
import ch.lkmc.neutrodyne.core.ui.resources.add_last_episode
import ch.lkmc.neutrodyne.core.ui.resources.add_might_be_subscribed
import ch.lkmc.neutrodyne.core.ui.resources.add_open
import ch.lkmc.neutrodyne.core.ui.resources.add_private
import ch.lkmc.neutrodyne.core.ui.resources.add_subscribe
import ch.lkmc.neutrodyne.core.ui.resources.add_subscribing
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.discover_add_url
import ch.lkmc.neutrodyne.core.ui.resources.membership_add_to
import ch.lkmc.neutrodyne.core.ui.resources.membership_create
import ch.lkmc.neutrodyne.core.ui.resources.membership_name
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_empty
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_taken
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_too_long
import ch.lkmc.neutrodyne.core.ui.resources.membership_new
import ch.lkmc.neutrodyne.core.ui.resources.preview_confirm
import ch.lkmc.neutrodyne.core.ui.resources.preview_subscribe_play
import ch.lkmc.neutrodyne.core.ui.resources.preview_title
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PodcastPreviewRoute(key: PodcastPreviewKey) {
    val viewModel =
        assistedMetroViewModel<PodcastPreviewViewModel, PodcastPreviewViewModel.Factory> {
            create(key.feedUrl)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(state.doneId) {
        val id = viewModel.consumeDone() ?: return@LaunchedEffect
        if (navigator.pop(key)) navigator.pushDetail(PodcastKey(id))
    }
    PodcastPreviewScreen(
        state,
        viewModel::retry,
        viewModel::subscribe,
        viewModel::createGroup,
        viewModel::consumeCreatedGroup,
        { navigator.pop(key) },
        {
            if (navigator.pop(key)) navigator.pushDetail(PodcastKey(it))
        },
        { navigator.push(AddPodcastKey(key.feedUrl)) },
    )
}

@Composable
internal fun PodcastPreviewScreen(
    state: PodcastPreviewState,
    retry: () -> Unit,
    subscribe: (Set<Long>) -> Unit,
    createGroup: (String) -> Unit,
    consumeCreatedGroup: () -> Unit,
    back: () -> Unit,
    open: (Long) -> Unit,
    edit: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var groupName by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val bars = LocalScrollbars.current
    val currentConsumeGroup by rememberUpdatedState(consumeCreatedGroup)
    LaunchedEffect(state.createdGroupId) {
        state.createdGroupId?.let {
            selected = (selected + it).distinct()
            groupName = null
            currentConsumeGroup()
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(Res.string.preview_confirm)) },
            text = { Text(stringResource(Res.string.add_might_be_subscribed)) },
            confirmButton = {
                NdTextButton(stringResource(Res.string.add_subscribe), {
                    confirm = false
                    subscribe(selected.toSet())
                })
            },
            dismissButton = { NdTextButton(stringResource(Res.string.action_cancel), { confirm = false }) },
        )
    }
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(stringResource(Res.string.preview_title), navigation = {
            NdIconButton(back, NdIcons.ArrowBack, stringResource(Res.string.back))
        })
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().ndScrollbar(list, bars, ndScrollbarColor(bars)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (state.loading) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Box(Modifier.size(160.dp).background(MaterialTheme.colorScheme.surfaceContainer))
                        repeat(PREVIEW_PLACEHOLDERS) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        72.dp,
                                    ).background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                }
                state.fetchError?.let {
                    Text(PreviewErrorText.describe(it).asString())
                    NdTextButton(stringResource(Res.string.action_retry), retry)
                    NdTextButton(stringResource(Res.string.discover_add_url), edit)
                }
            }
            state.preview?.let { preview ->
                item {
                    CoverArt(
                        preview.artworkUrl?.let { ArtworkRef(key = it, url = it, version = 0) },
                        rememberMonogram(preview.title),
                        title = preview.title,
                        modifier = Modifier.size(160.dp),
                        tier = CoverTier.HERO,
                    )
                    Text(preview.title, style = MaterialTheme.typography.headlineSmall)
                    preview.author?.let { Text(it) }
                    Text(
                        pluralStringResource(
                            Res.plurals.add_episodes_count,
                            preview.episodeCount,
                            preview.episodeCount,
                        ),
                    )
                    preview.latestEpisodeAt?.let {
                        val (day, month) = FeedDates.dayMonth(it)
                        Text(stringResource(Res.string.add_last_episode, "$day $month"))
                    }
                    if (preview.isPrivate) Text(stringResource(Res.string.add_private))
                    if (preview.emptyFeed) Text(stringResource(Res.string.add_empty_feed))
                    if (state.openId != null) {
                        Text(stringResource(Res.string.add_already_subscribed))
                        NdButton(stringResource(Res.string.add_open), { open(state.openId) })
                    } else {
                        if (preview.alreadySubscribed != null) Text(stringResource(Res.string.add_might_be_subscribed))
                        Text(stringResource(Res.string.membership_add_to))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.groups.forEach { group ->
                                NdFilterChip(
                                    selected = group.id in selected,
                                    onClick = {
                                        selected =
                                            if (group.id in selected) selected - group.id else selected + group.id
                                    },
                                    label = group.name,
                                    enabled = !state.subscribing,
                                )
                            }
                            NdAssistChip(
                                onClick = { groupName = "" },
                                label = stringResource(Res.string.membership_new),
                                enabled = !state.creatingGroup && !state.subscribing,
                            )
                        }
                        groupName?.let { name ->
                            val errorText = state.groupError?.asString()
                            OutlinedTextField(
                                name,
                                { groupName = it },
                                singleLine = true,
                                label = { Text(stringResource(Res.string.membership_name)) },
                                isError = errorText != null,
                                supportingText = errorText?.let { { Text(it) } },
                                enabled = !state.creatingGroup && !state.subscribing,
                            )
                            NdTextButton(
                                stringResource(Res.string.membership_create),
                                { createGroup(name) },
                                enabled = !state.creatingGroup && !state.subscribing,
                            )
                        }
                        NdButton(stringResource(Res.string.add_subscribe), {
                            if (preview.alreadySubscribed != null) confirm = true else subscribe(selected.toSet())
                        }, enabled = !state.loading && !state.subscribing && !state.creatingGroup)
                    }
                    state.subscribeError?.let { Text(PreviewSubscribeErrorText.describe(it).asString()) }
                    if (state.subscribing) Text(stringResource(Res.string.add_subscribing))
                }
                preview.description?.let { notes -> item { PodcastDescription(notes) } }
                items(preview.episodes.take(MAX_PREVIEW_EPISODES)) { episode ->
                    ListItem(headlineContent = { Text(episode.title) }, supportingContent = {
                        Column {
                            val date =
                                episode.pubDate?.let {
                                    val (day, month) = FeedDates.dayMonth(it)
                                    "$day $month"
                                }
                            val duration = FeedDates.duration(episode.durationMs).asString().takeIf { it.isNotEmpty() }
                            Text(listOfNotNull(date, duration).joinToString(" · "))
                            episode.snippet?.let { Text(it, maxLines = 3) }
                            Text(stringResource(Res.string.preview_subscribe_play))
                        }
                    })
                }
            }
        }
    }
}

private const val MAX_PREVIEW_EPISODES = 200

private const val PREVIEW_PLACEHOLDERS = 6
