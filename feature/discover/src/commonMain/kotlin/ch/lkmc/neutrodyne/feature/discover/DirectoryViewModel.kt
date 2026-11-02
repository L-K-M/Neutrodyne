// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class DirectoryState(
    val loading: Boolean = true,
    val results: SearchResults? = null,
    val hits: List<DirectoryHit> = emptyList(),
    val chartError: ProviderStatus? = null,
    val invalid: Boolean = false,
)

internal class DirectoryViewModel
    @AssistedInject
    constructor(
        @Assisted val key: DirectoryKey,
        private val repository: SearchRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(DirectoryState())
        val state = mutableState.asStateFlow()
        private var job: Job? = null

        init {
            viewModelScope.launch {
                repository
                    .observeProviders()
                    .map { rows ->
                        rows.map { Triple(it.id, it.effective, it.configurationVersion) }
                    }.distinctUntilChanged()
                    .collect { retry() }
            }
        }

        fun retry() {
            job?.cancel()
            val query = key.query.trim()
            val genre = key.genreId?.let(ChartGenre::fromId)
            if ((query.isNotEmpty() && isLinkInput(query)) ||
                (query.isEmpty() && key.genreId != null && genre == null)
            ) {
                mutableState.value = DirectoryState(loading = false, invalid = true)
                return
            }
            mutableState.update { it.copy(loading = true, chartError = null) }
            job =
                viewModelScope.launch {
                    if (query.isNotEmpty()) {
                        repository.search(query).collect { results ->
                            ensureActive()
                            mutableState.value = DirectoryState(!results.complete, results, results.hits)
                        }
                    } else {
                        val result = repository.charts(genre)
                        ensureActive()
                        mutableState.value =
                            when (result) {
                                is Outcome.Success -> DirectoryState(loading = false, hits = result.value)
                                is Outcome.Failure -> DirectoryState(loading = false, chartError = result.error)
                            }
                    }
                }
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(key: DirectoryKey): DirectoryViewModel
        }
    }
