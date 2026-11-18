// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.radio.EngravedHeading
import ch.lkmc.neutrodyne.core.designsystem.radio.KeyBank
import ch.lkmc.neutrodyne.core.designsystem.radio.MomentaryKey
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKey
import ch.lkmc.neutrodyne.core.designsystem.radio.TuningDial
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.PageColumnInset
import ch.lkmc.neutrodyne.core.ui.pageColumn
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.discover_add_url
import ch.lkmc.neutrodyne.core.ui.resources.discovery_add_link
import ch.lkmc.neutrodyne.core.ui.resources.discovery_charts_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.discovery_import
import ch.lkmc.neutrodyne.core.ui.resources.discovery_more
import ch.lkmc.neutrodyne.core.ui.resources.discovery_popular
import ch.lkmc.neutrodyne.core.ui.resources.discovery_search
import ch.lkmc.neutrodyne.core.ui.resources.discovery_station_top
import ch.lkmc.neutrodyne.core.ui.resources.discovery_top
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.root.LocalDiscoverSearchFocus
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiscoverRoute(viewModel: DiscoverViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf(state.input) }
    LaunchedEffect(Unit) { viewModel.inputChanged(input) }
    val navigator = LocalAppNavigator.current
    DiscoverScreen(
        state.copy(input = input),
        {
            input = it
            viewModel.inputChanged(it)
        },
        viewModel::retry,
        navigator,
        viewModel::loadChart,
        viewModel::onProvidersVisible,
        viewModel::tune,
        viewModel::retryChart,
    )
}

/**
 * Discover in the radio's parts (PO-55): a paper search strip with the provider disclosure under
 * it, a bank of keys to add by URL or import, and, while nothing is typed, the charts as radio
 * stations: preset keys for the top chart and the genres the user's groups map to, a tuning dial
 * of every genre, and a tray of slips for the tuned station's top podcasts.
 */
@Composable
internal fun DiscoverScreen(
    state: DiscoverState,
    onInput: (String) -> Unit,
    retry: () -> Unit,
    navigator: AppNavigator,
    onChartVisible: (ChartGenre?) -> Unit = {},
    onProvidersVisible: (List<ProviderInfo>) -> Unit = {},
    onTune: (ChartGenre?) -> Unit = {},
    onChartRetry: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val searchFocus = LocalDiscoverSearchFocus.current
    DisposableEffect(searchFocus, focus) {
        val action: () -> Unit = { focus.requestFocus() }
        searchFocus?.register(action)
        onDispose { searchFocus?.unregister(action) }
    }
    val list = rememberLazyListState()
    val bars = LocalScrollbars.current
    val barColor = ndScrollbarColor(bars)
    val settings = { navigator.pushDetail(SettingsKey(SettingsPage.DISCOVER)) }
    val linkInput = isLinkInput(state.input)

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
                focus.requestFocus()
                true
            } else {
                false
            }
        },
    ) {
        NdTopAppBar(stringResource(Res.string.nav_discover))
        Column(
            Modifier.pageColumn().padding(horizontal = 16.dp).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchStrip(state.input, onInput, ::submit, stringResource(Res.string.discovery_search), focus)
            ProviderDisclosure(state.providers, Modifier.padding(horizontal = 4.dp), onProvidersVisible)
            KeyBank {
                if (linkInput) MomentaryKey(stringResource(Res.string.discovery_add_link), ::submit)
                MomentaryKey(stringResource(Res.string.discover_add_url), { navigator.push(AddPodcastKey(null)) })
                MomentaryKey(stringResource(Res.string.discovery_import), { navigator.push(ImportFileKey) })
            }
        }
        PageColumnInset(Modifier.weight(1f)) { inset ->
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().ndScrollbar(list, bars, barColor),
                contentPadding = PaddingValues(start = inset, top = 8.dp, end = inset, bottom = 16.dp),
            ) {
                if (state.input.isNotBlank() && !linkInput) {
                    state.results?.let { results ->
                        item { SearchStatus(results, retry, settings) }
                        val inline = results.hits.take(INLINE_LIMIT)
                        val keys = hitKeys(inline)
                        itemsIndexed(inline, key = { index, _ -> keys[index] }) { _, hit ->
                            DirectoryRow(hit) { navigator.openHit(hit) }
                        }
                        if (results.hits.size > INLINE_LIMIT) {
                            item {
                                KeyBank(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    MomentaryKey(stringResource(Res.string.discovery_more), ::submit)
                                }
                            }
                        }
                    }
                } else if (state.input.isBlank() && state.chartsAvailable()) {
                    charts(state, navigator, onChartVisible, onTune, onChartRetry)
                } else if (state.input.isBlank()) {
                    item(key = "charts-off") { ChartsOff(settings) }
                }
            }
        }
    }
}

/**
 * Charts come from Apple alone: none while Apple is switched off. Until the providers are known,
 * the tray shows; the chart request itself reports [ProviderStatus.Disabled].
 */
private fun DiscoverState.chartsAvailable(): Boolean {
    if (chart.failure == ProviderStatus.Disabled) return false
    val known = providers ?: return true
    return known.any { it.id == ProviderId.APPLE && it.effective }
}

private fun LazyListScope.charts(
    state: DiscoverState,
    navigator: AppNavigator,
    onChartVisible: (ChartGenre?) -> Unit,
    onTune: (ChartGenre?) -> Unit,
    onChartRetry: () -> Unit,
) {
    val chart = state.chart
    if (state.presets.size > 1) {
        item(key = "presets") {
            KeyBank(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                state.presets.forEach { genre ->
                    PianoKey(
                        genre.stationLabel(),
                        pressed = genre == chart.genre,
                        onClick = { onTune(genre) },
                        modifier = Modifier.testTag("preset:${genre?.id.orEmpty()}"),
                    )
                }
            }
        }
    }
    item(key = "dial") {
        val stations = STATIONS.map { it.stationLabel() }
        TuningDial(
            stations = stations,
            tuned = STATIONS.indexOf(chart.genre),
            onTune = { onTune(STATIONS[it]) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            tag = { "station:${STATIONS[it]?.id.orEmpty()}" },
        )
    }
    item(key = "chart-heading") {
        val currentOnChartVisible by rememberUpdatedState(onChartVisible)
        LaunchedEffect(chart.genre, chart.generation) { currentOnChartVisible(chart.genre) }
        EngravedHeading(
            chart.genre?.let { stringResource(Res.string.discovery_popular, it.label()) }
                ?: stringResource(Res.string.discovery_top),
            Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp),
        ) {
            KeyBank {
                MomentaryKey(
                    stringResource(Res.string.discovery_more),
                    { navigator.pushDetail(DirectoryKey("", chart.genre?.id)) },
                )
            }
        }
    }
    when {
        chart.failure != null -> {
            item(key = "chart-failure") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(Res.string.discovery_charts_unavailable))
                    KeyBank { MomentaryKey(stringResource(Res.string.action_retry), onChartRetry) }
                }
            }
        }

        chart.hits == null -> {
            items(CHART_PLACEHOLDERS, key = { "chart-skeleton-$it" }) { SkeletonSlip() }
        }

        else -> {
            val inline = chart.hits.take(INLINE_LIMIT)
            val keys = hitKeys(inline)
            itemsIndexed(inline, key = { index, _ -> "chart:${keys[index]}" }) { _, hit ->
                DirectoryRow(hit) { navigator.openHit(hit) }
            }
        }
    }
}

/** The dial's stations: the top chart, then every genre in the directory's order. */
private val STATIONS: List<ChartGenre?> = listOf<ChartGenre?>(null) + ChartGenre.entries

@Composable
private fun ChartGenre?.stationLabel(): String = this?.label() ?: stringResource(Res.string.discovery_station_top)

private const val INLINE_LIMIT = 10

private const val CHART_PLACEHOLDERS = 5
