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
import ch.lkmc.neutrodyne.core.model.SearchResults
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChartRow(
    val title: String?,
    val genre: ChartGenre?,
    val hits: List<DirectoryHit>?,
    val generation: Int = 0,
)

internal data class DiscoverState(
    val input: String = "",
    val results: SearchResults? = null,
    val providers: List<ProviderInfo> = emptyList(),
    val charts: List<ChartRow> = emptyList(),
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
    private val chartJobs = mutableMapOf<ChartGenre?, Job>()
    private var chartGeneration = 0
    private var generation = 0

    init {
        viewModelScope.launch {
            repository.observeProviders().collect { providers ->
                val previous = mutableState.value.providers
                val changed = previous.isNotEmpty() && previous.configuration() != providers.configuration()
                mutableState.update { it.copy(providers = providers) }
                if (changed) restartSearch(SearchTiming.IMMEDIATE)
            }
        }
        viewModelScope.launch {
            combine(
                repository
                    .observeProviders()
                    .map { providers ->
                        providers.configuration()
                    }.distinctUntilChanged(),
                groups.observeGroups().map { rows -> rows.map { it.name } }.distinctUntilChanged(),
            ) { _, names -> names }.collect { names ->
                chartJobs.values.forEach { it.cancel() }
                chartJobs.clear()
                val chartRun = ++chartGeneration
                val rows =
                    listOf(ChartRow(null, null, null, chartRun)) +
                        names.mapNotNull { name ->
                            repository.genreForGroupName(name)?.let { ChartRow(name, it, null, chartRun) }
                        }
                mutableState.update { it.copy(charts = rows) }
            }
        }
    }

    /** LazyColumn calls this as a row enters composition; matching groups share one request. */
    fun loadChart(genre: ChartGenre?) {
        if (genre in chartJobs || mutableState.value.charts.none { it.genre == genre }) return
        val run = chartGeneration
        chartJobs[genre] =
            viewModelScope.launch {
                val result = repository.charts(genre)
                ensureActive()
                mutableState.update { state ->
                    state.copy(
                        charts =
                            state.charts.mapNotNull { row ->
                                if (row.genre != genre || row.generation != run) {
                                    row
                                } else {
                                    when (result) {
                                        is Outcome.Success -> row.copy(hits = result.value)
                                        is Outcome.Failure -> null
                                    }
                                }
                            },
                    )
                }
            }
    }

    fun inputChanged(input: String) {
        mutableState.update { it.copy(input = input) }
        restartSearch(SearchTiming.DEBOUNCED)
    }

    fun retry() = restartSearch(SearchTiming.IMMEDIATE)

    private fun restartSearch(timing: SearchTiming) {
        searchJob?.cancel()
        val run = ++generation
        val query = mutableState.value.input.trim()
        if (query.length < MIN_QUERY_LENGTH || isLinkInput(query)) {
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

private fun List<ProviderInfo>.configuration() = map { Triple(it.id, it.effective, it.configurationVersion) }
