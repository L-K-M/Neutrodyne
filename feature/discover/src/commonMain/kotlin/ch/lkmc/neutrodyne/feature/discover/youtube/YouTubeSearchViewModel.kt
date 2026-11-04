// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover.youtube

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.YouTubeSearchKey
import ch.lkmc.neutrodyne.youtube.api.ChannelHit
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.SearchCursor
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
internal data class YouTubeSearchState(
    /** False while [YouTubeCapabilitiesSource] reports no channelSearch capability. */
    val capable: Boolean = false,
    val loading: Boolean = false,
    /** [attempted] separates "search ran, zero hits" from "never searched / incapable". */
    val attempted: Boolean = false,
    val hits: List<ChannelHit> = emptyList(),
    val error: TransientKind? = null,
    /** True while a live cursor under the page cap remains; [loadMore] is allowed. */
    val canPage: Boolean = false,
    /** True once the cursor ended or expired, or the third page landed. */
    val ended: Boolean = false,
)

/**
 * The explicit YouTube channel search page (W10 D1/D8): [YouTubeSearchKey.query] is fixed for the
 * screen's lifetime, so "latest query wins" is the generation fence against superseded page
 * requests and capability flips — a late answer from an older generation is dropped before it can
 * mutate state. At most [MAX_PAGES] pages of [ch.lkmc.neutrodyne.youtube.api.ChannelHit]s load; a
 * cursor that expires mid-list ends paging while the already loaded hits stay.
 */
internal class YouTubeSearchViewModel
    @AssistedInject
    constructor(
        @Assisted private val key: YouTubeSearchKey,
        private val search: YouTubeChannelSearch,
        private val capabilities: YouTubeCapabilitiesSource,
        private val engine: YouTubeEngine,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(YouTubeSearchState())
        val state = mutableState.asStateFlow()

        private var job: Job? = null
        private var generation = 0
        private var cursor: SearchCursor? = null
        private var pages = 0
        private var prewarmed = false

        init {
            viewModelScope.launch {
                capabilities.capabilities.collect { caps ->
                    if (caps.channelSearch) {
                        mutableState.update { it.copy(capable = true) }
                        if (!mutableState.value.attempted) loadMore()
                    } else {
                        // A capability flip invalidates in-flight work and any stale results.
                        job?.cancel()
                        generation++
                        cursor = null
                        pages = 0
                        mutableState.update {
                            YouTubeSearchState(capable = false)
                        }
                    }
                }
            }
        }

        /** Retries a failed page or loads the next one; ignored while loading or at the cap. */
        fun loadMore() {
            val state = mutableState.value
            if (!state.capable || state.loading || state.ended) return
            if (state.attempted && state.error == null && cursor == null) return
            if (pages >= MAX_PAGES) return
            if (!prewarmed) {
                // Prewarm is a consequence of the explicit search action, never of plain browsing.
                engine.prewarm(PrewarmReason.SEARCH)
                prewarmed = true
            }
            // A retry after Failed re-issues the same page: the cursor still names it.
            val token = cursor
            val gen = ++generation
            job?.cancel()
            mutableState.update { it.copy(loading = true, error = null, attempted = true) }
            job =
                viewModelScope.launch {
                    val result =
                        if (token == null) {
                            search.search(key.query)
                        } else {
                            search.search(key.query, token)
                        }
                    ensureActive()
                    mutableState.update { current ->
                        if (generation != gen) return@update current
                        when (result) {
                            is ChannelSearchResult.Ok -> {
                                if (result.next != null) {
                                    pages++
                                    cursor = result.next
                                    current.copy(
                                        loading = false,
                                        hits = current.hits + result.hits,
                                        canPage = pages < MAX_PAGES,
                                        ended = pages >= MAX_PAGES,
                                    )
                                } else {
                                    pages++
                                    cursor = null
                                    current.copy(
                                        loading = false,
                                        hits = current.hits + result.hits,
                                        canPage = false,
                                        ended = true,
                                    )
                                }
                            }

                            ChannelSearchResult.Unsupported -> {
                                cursor = null
                                current.copy(loading = false, capable = false, error = null)
                            }

                            is ChannelSearchResult.Failed -> {
                                current.copy(loading = false, error = result.kind)
                            }
                        }
                    }
                }
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        fun interface Factory : ManualViewModelAssistedFactory {
            fun create(key: YouTubeSearchKey): YouTubeSearchViewModel
        }

        private companion object {
            const val MAX_PAGES = 3
        }
    }
