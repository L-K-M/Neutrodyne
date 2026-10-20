// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover.youtube

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.YouTubeSearchKey
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.youtube_search_empty
import ch.lkmc.neutrodyne.core.ui.resources.youtube_search_error
import ch.lkmc.neutrodyne.core.ui.resources.youtube_search_more
import ch.lkmc.neutrodyne.core.ui.resources.youtube_search_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.youtube_subscriber_count
import ch.lkmc.neutrodyne.core.ui.resources.youtube_verified
import ch.lkmc.neutrodyne.youtube.api.ChannelHit
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun YouTubeSearchRoute(key: YouTubeSearchKey) {
    val viewModel = assistedMetroViewModel<YouTubeSearchViewModel, YouTubeSearchViewModel.Factory> { create(key) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    YouTubeSearchScreen(key, state, viewModel::loadMore, LocalAppNavigator.current)
}

@Composable
internal fun YouTubeSearchScreen(
    key: YouTubeSearchKey,
    state: YouTubeSearchState,
    onMore: () -> Unit,
    navigator: AppNavigator,
) {
    val list = rememberLazyListState()
    val bars = LocalScrollbars.current
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            key.query,
            navigation = { NdIconButton({ navigator.pop() }, NdIcons.ArrowBack, stringResource(Res.string.back)) },
        )
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().ndScrollbar(list, bars, ndScrollbarColor(bars))) {
            item {
                when {
                    !state.capable -> {
                        Text(stringResource(Res.string.youtube_search_unavailable))
                    }

                    state.error != null -> {
                        Column {
                            Text(stringResource(Res.string.youtube_search_error))
                            NdTextButton(stringResource(Res.string.action_retry), onMore)
                        }
                    }

                    state.attempted && !state.loading && state.hits.isEmpty() && !state.canPage -> {
                        Text(stringResource(Res.string.youtube_search_empty, key.query))
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            items(state.hits, key = { it.channelId }) { hit ->
                ChannelRow(hit) {
                    // A hit enters the normal Add flow: resolve, preview, subscribe — no second path.
                    navigator.push(AddPodcastKey(YouTubeFeedUrls.channelPage(hit.channelId)))
                }
            }
            if (state.canPage) {
                item {
                    NdTextButton(stringResource(Res.string.youtube_search_more), onMore)
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    hit: ChannelHit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(hit.title) },
        supportingContent = {
            Column {
                hit.subscriberCount?.let {
                    val count = it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    Text(pluralStringResource(Res.plurals.youtube_subscriber_count, count, count))
                }
                hit.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                if (hit.verified) {
                    Text(stringResource(Res.string.youtube_verified), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        leadingContent = {
            CoverArt(
                ref = hit.avatarUrl?.let { ArtworkRef(key = it, url = it, version = 0) },
                monogram = rememberMonogram(hit.title),
                title = hit.title,
                modifier = Modifier.size(64.dp).padding(end = 0.dp),
            )
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}
