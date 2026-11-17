// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.KeyBank
import ch.lkmc.neutrodyne.core.designsystem.radio.MomentaryKey
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.discover_add_url
import ch.lkmc.neutrodyne.core.ui.resources.discovery_charts_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.discovery_empty
import ch.lkmc.neutrodyne.core.ui.resources.discovery_invalid
import ch.lkmc.neutrodyne.core.ui.resources.discovery_top
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DirectoryRoute(key: DirectoryKey) {
    val viewModel = assistedMetroViewModel<DirectoryViewModel, DirectoryViewModel.Factory> { create(key) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    DirectoryScreen(key, state, viewModel::retry, LocalAppNavigator.current, viewModel::onProvidersVisible)
}

@Composable
internal fun DirectoryScreen(
    key: DirectoryKey,
    state: DirectoryState,
    retry: () -> Unit,
    navigator: AppNavigator,
    onProvidersVisible: (List<ProviderInfo>) -> Unit = {},
) {
    val list = rememberLazyListState()
    val bars = LocalScrollbars.current
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            key.query.takeIf { it.isNotBlank() } ?: key.genreId?.let(ChartGenre::fromId)?.label()
                ?: stringResource(Res.string.discovery_top),
            navigation = { NdIconButton({ navigator.pop() }, NdIcons.ArrowBack, stringResource(Res.string.back)) },
        )
        ProviderDisclosure(
            state.providers,
            Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            onProvidersVisible,
        )
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().ndScrollbar(list, bars, ndScrollbarColor(bars))) {
            item {
                when {
                    state.invalid -> {
                        Text(
                            stringResource(Res.string.discovery_invalid),
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }

                    state.results != null -> {
                        SearchStatus(state.results, retry) {
                            navigator.pushDetail(SettingsKey(SettingsPage.DISCOVER))
                        }
                    }

                    state.loading -> {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                            repeat(DIRECTORY_PLACEHOLDERS) { SkeletonSlip() }
                        }
                    }

                    state.chartError != null -> {
                        Column(
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(Res.string.discovery_charts_unavailable))
                            KeyBank { MomentaryKey(stringResource(Res.string.action_retry), retry) }
                        }
                    }

                    state.hits.isEmpty() -> {
                        Text(
                            stringResource(Res.string.discovery_empty, key.query),
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            items(state.hits, key = { it.feedUrl }) { hit -> DirectoryRow(hit) { navigator.openHit(hit) } }
            if (state.hits.isEmpty()) {
                item {
                    KeyBank(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        MomentaryKey(
                            stringResource(Res.string.discover_add_url),
                            { navigator.push(AddPodcastKey(null)) },
                        )
                    }
                }
            }
        }
    }
}

private const val DIRECTORY_PLACEHOLDERS = 6
