// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The chart the station dial is tuned to: [genre] null is the top chart. [hits] stay null while
 * the chart loads; [failure] says why it did not ([ProviderStatus.Disabled] when Apple, the only
 * chart source, is switched off). [generation] changes whenever the chart must be fetched again.
 */
internal data class TunedChart(
    val genre: ChartGenre? = null,
    val hits: List<DirectoryHit>? = null,
    val failure: ProviderStatus? = null,
    val generation: Int = 0,
)

internal data class DiscoverState(
    val input: String = "",
    val results: SearchResults? = null,
    val providers: List<ProviderInfo>? = null,
    /** Preset stations: the top chart, then each genre a group's name maps to (03), once. */
    val presets: List<ChartGenre?> = listOf(null),
    val chart: TunedChart = TunedChart(),
)

@ViewModelKey(DiscoverViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class DiscoverViewModel(
    private val repository: SearchRepository,
    groups: GroupRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiscoverState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var disclosedProviders: List<ProviderInfo>? = null
    private var pendingSearchTiming = SearchTiming.DEBOUNCED
    private var chartJob: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            repository.observeProviders().collect { providers ->
                val previous = mutableState.value.providers
                val changed = previous?.configuration() != providers.configuration()
                if (changed) {
                    disclosedProviders = null
                    pendingSearchTiming = if (previous == null) SearchTiming.DEBOUNCED else SearchTiming.IMMEDIATE
                }
                mutableState.update { it.copy(providers = providers) }
                if (changed) restartSearch(pendingSearchTiming)
            }
        }
        viewModelScope.launch {
            // Charts come from Apple: a changed directory configuration changes their answer.
            repository
                .observeProviders()
                .map { providers -> providers.configuration() }
                .distinctUntilChanged()
                .drop(1)
                .collect { refetchChart() }
        }
        viewModelScope.launch {
            groups.observeGroups().map { rows -> rows.map { it.name } }.distinctUntilChanged().collect { names ->
                val presets =
                    (listOf<ChartGenre?>(null) + names.mapNotNull(repository::genreForGroupName)).distinct()
                mutableState.update { it.copy(presets = presets) }
            }
        }
    }

    /** Tunes the dial; the screen asks for the chart with [loadChart] once it shows it. */
    fun tune(genre: ChartGenre?) {
        if (genre == mutableState.value.chart.genre) return
        chartJob?.cancel()
        mutableState.update { it.copy(chart = TunedChart(genre, generation = it.chart.generation + 1)) }
    }

    /** Fetches the tuned chart again after a failure. */
    fun retryChart() = refetchChart()

    /** The screen calls this as the tuned chart enters composition; repeated calls share one request. */
    fun loadChart(genre: ChartGenre?) {
        val chart = mutableState.value.chart
        if (genre != chart.genre || chart.hits != null || chart.failure != null || chartJob?.isActive == true) return
        chartJob =
            viewModelScope.launch {
                val result = repository.charts(genre)
                ensureActive()
                mutableState.update { state ->
                    if (state.chart.genre != genre || state.chart.generation != chart.generation) {
                        state
                    } else {
                        when (result) {
                            is Outcome.Success -> state.copy(chart = state.chart.copy(hits = result.value))
                            is Outcome.Failure -> state.copy(chart = state.chart.copy(failure = result.error))
                        }
                    }
                }
            }
    }

    /** Provider and group changes can change the chart's answer: drop it and wait to be shown. */
    private fun refetchChart() {
        chartJob?.cancel()
        mutableState.update { it.copy(chart = TunedChart(it.chart.genre, generation = it.chart.generation + 1)) }
    }

    /** Uses Directory's disclosure acknowledgement, including its frame boundary. */
    fun onProvidersVisible(providers: List<ProviderInfo>) {
        val current = mutableState.value.providers ?: return
        if (providers.configuration() != current.configuration() ||
            disclosedProviders?.configuration() == current.configuration()
        ) {
            return
        }
        disclosedProviders = current
        restartSearch(pendingSearchTiming)
    }

    fun inputChanged(input: String) {
        mutableState.update { it.copy(input = input) }
        pendingSearchTiming = SearchTiming.DEBOUNCED
        restartSearch(pendingSearchTiming)
    }

    fun retry() {
        pendingSearchTiming = SearchTiming.IMMEDIATE
        restartSearch(pendingSearchTiming)
    }

    private fun restartSearch(timing: SearchTiming) {
        searchJob?.cancel()
        val run = ++generation
        val query = mutableState.value.input.trim()
        val providers = mutableState.value.providers
        if (providers == null || disclosedProviders?.configuration() != providers.configuration() ||
            query.length < MIN_QUERY_LENGTH || isLinkInput(query)
        ) {
            mutableState.update { it.copy(results = null) }
            return
        }
        searchJob =
            viewModelScope.launch {
                if (timing == SearchTiming.DEBOUNCED) delay(SEARCH_DEBOUNCE_MS)
                repository.search(query).collect { results ->
                    ensureActive()
                    if (run == generation) {
                        mutableState.update { state ->
                            // Keep the previous list while the new query has only Pending answers.
                            val visible =
                                if (!results.complete && results.hits.isEmpty()) {
                                    results.copy(hits = state.results?.hits ?: results.hits)
                                } else {
                                    results
                                }
                            state.copy(results = visible)
                        }
                    }
                }
            }
    }
}

// Conservatively keep addresses, handles and pasted link-bearing prose out of directory queries.
// Add owns normalization and validation; this boundary only decides whether disclosure is enough.
internal fun isLinkInput(text: String): Boolean =
    text.split(Regex("\\s+")).any {
        it.contains(':') || it.contains('/') || it.contains('\\') || it.trimEnd('.').contains('.') ||
            it.contains('@') || it.equals("localhost", ignoreCase = true) ||
            (it.startsWith("UC") && it.length == YOUTUBE_CHANNEL_ID_LENGTH)
    }

internal const val MIN_QUERY_LENGTH = 3
internal const val SEARCH_DEBOUNCE_MS = 600L
private const val YOUTUBE_CHANNEL_ID_LENGTH = 24

private enum class SearchTiming { IMMEDIATE, DEBOUNCED }

internal fun List<ProviderInfo>.configuration() = map { Triple(it.id, it.effective, it.configurationVersion) }
