// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdAssistChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndHorizontalScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.discover_add_url
import ch.lkmc.neutrodyne.core.ui.resources.discovery_add_link
import ch.lkmc.neutrodyne.core.ui.resources.discovery_add_youtube
import ch.lkmc.neutrodyne.core.ui.resources.discovery_categories
import ch.lkmc.neutrodyne.core.ui.resources.discovery_disabled
import ch.lkmc.neutrodyne.core.ui.resources.discovery_disclosure
import ch.lkmc.neutrodyne.core.ui.resources.discovery_import
import ch.lkmc.neutrodyne.core.ui.resources.discovery_more
import ch.lkmc.neutrodyne.core.ui.resources.discovery_popular
import ch.lkmc.neutrodyne.core.ui.resources.discovery_search
import ch.lkmc.neutrodyne.core.ui.resources.discovery_top
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.root.LocalDiscoverSearchFocus
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiscoverRoute(viewModel: DiscoverViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf(state.input) }
    LaunchedEffect(Unit) { viewModel.inputChanged(input) }
    val navigator = LocalAppNavigator.current
    DiscoverScreen(state.copy(input = input), {
        input = it
        viewModel.inputChanged(it)
    }, viewModel::retry, navigator, viewModel::loadChart)
}

@Composable
internal fun DiscoverScreen(
    state: DiscoverState,
    onInput: (String) -> Unit,
    retry: () -> Unit,
    navigator: AppNavigator,
    onChartVisible: (ChartGenre?) -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val requestSearchFocus: () -> Unit =
        remember(list, focus, scope) {
            {
                scope.launch {
                    list.scrollToItem(0)
                    focus.requestFocus()
                }
                Unit
            }
        }
    val searchFocus = LocalDiscoverSearchFocus.current
    DisposableEffect(searchFocus, requestSearchFocus) {
        searchFocus?.register(requestSearchFocus)
        onDispose { searchFocus?.unregister(requestSearchFocus) }
    }
    val currentOnChartVisible by rememberUpdatedState(onChartVisible)
    val bars = LocalScrollbars.current
    val barColor = ndScrollbarColor(bars)
    val settings = { navigator.pushDetail(SettingsKey(SettingsPage.DISCOVER)) }

    fun submit() {
        val query = state.input.trim()
        if (query.isEmpty()) return
        if (isLinkInput(query)) {
            navigator.push(AddPodcastKey(query))
        } else {
            navigator.pushDetail(DirectoryKey(query, null))
        }
    }
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.F && (it.isCtrlPressed || it.isMetaPressed)) {
                requestSearchFocus()
                true
            } else {
                false
            }
        },
    ) {
        NdTopAppBar(stringResource(Res.string.nav_discover), actions = { SettingsGearButton() })
        LazyColumn(state = list, modifier = Modifier.weight(1f).ndScrollbar(list, bars, barColor)) {
            item(key = "search") {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInput,
                    label = { Text(stringResource(Res.string.discovery_search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .focusRequester(focus)
                            .onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                                    submit()
                                    true
                                } else {
                                    false
                                }
                            },
                )
                val recipients = state.providers.filter { it.effective }.joinToString(", ") { it.id.displayName() }
                Text(
                    if (recipients.isEmpty()) {
                        stringResource(Res.string.discovery_disabled)
                    } else {
                        stringResource(Res.string.discovery_disclosure, recipients)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.input.isBlank()) {
                        NdAssistChip(
                            onClick = { navigator.pushDetail(DirectoryKey("", null)) },
                            label = stringResource(Res.string.discovery_top),
                        )
                        NdAssistChip(
                            onClick = { scope.launch { list.scrollToItem(state.charts.size + 1) } },
                            label = stringResource(Res.string.discovery_categories),
                        )
                    }
                    NdAssistChip(
                        onClick = { navigator.push(AddPodcastKey(null)) },
                        label = stringResource(Res.string.discover_add_url),
                    )
                    NdAssistChip(
                        onClick = { navigator.push(ImportFileKey) },
                        label = stringResource(Res.string.discovery_import),
                    )
                    NdAssistChip(
                        onClick = { navigator.push(AddPodcastKey(null)) },
                        label = stringResource(Res.string.discovery_add_youtube),
                    )
                    if (isLinkInput(
                            state.input,
                        )
                    ) {
                        NdAssistChip(onClick = ::submit, label = stringResource(Res.string.discovery_add_link))
                    }
                }
            }
            if (state.input.isNotBlank() && !isLinkInput(state.input)) {
                state.results?.let { results ->
                    item { SearchStatus(results, retry, settings, { navigator.push(AddPodcastKey(null)) }) }
                    items(results.hits.take(INLINE_LIMIT), key = { it.feedUrl }) { hit ->
                        DirectoryRow(hit) { navigator.openHit(hit) }
                    }
                    if (results.hits.size > INLINE_LIMIT) {
                        item {
                            NdTextButton(stringResource(Res.string.discovery_more), ::submit)
                        }
                    }
                }
            } else if (state.input.isBlank()) {
                state.charts.forEach { row ->
                    item(key = row.title ?: "top") {
                        LaunchedEffect(row.generation, row.genre) { currentOnChartVisible(row.genre) }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(
                                row.title?.let { stringResource(Res.string.discovery_popular, it) }
                                    ?: stringResource(Res.string.discovery_top),
                                Modifier.weight(1f),
                            )
                            NdTextButton(stringResource(Res.string.discovery_more), {
                                navigator.pushDetail(DirectoryKey("", row.genre?.id))
                            })
                        }
                        if (row.hits == null) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                repeat(CHART_PLACEHOLDERS) {
                                    Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.surfaceContainer))
                                }
                            }
                        } else {
                            val scroll = rememberLazyListState()
                            LazyRow(
                                state = scroll,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("chart-row:${row.genre?.id.orEmpty()}")
                                        .ndHorizontalScrollbar(scroll, bars, barColor),
                                contentPadding =
                                    androidx.compose.foundation.layout
                                        .PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(row.hits, key = { it.feedUrl }) { hit ->
                                    Column(
                                        Modifier.width(120.dp).clickable(role = Role.Button) { navigator.openHit(hit) },
                                    ) {
                                        DirectoryCover(hit, Modifier.size(120.dp))
                                        Text(hit.title, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "categories") {
                    Text(stringResource(Res.string.discovery_categories), Modifier.padding(16.dp))
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChartGenre.entries.forEach { genre ->
                            NdAssistChip(
                                onClick = { navigator.pushDetail(DirectoryKey("", genre.id)) },
                                label = genre.label(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val INLINE_LIMIT = 10

private const val CHART_PLACEHOLDERS = 5
