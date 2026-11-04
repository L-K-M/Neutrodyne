// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DIRECTORY_PROVIDER_ORDER
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Script operation timing/failures with searchAnswer; default is an empty successful directory. */
class FakeSearchRepository : SearchRepository {
    val providers = MutableStateFlow(DIRECTORY_PROVIDER_ORDER.map { ProviderInfo(it, true) }.toImmutableList())
    val queries = mutableListOf<String>()
    val chartRequests = mutableListOf<ChartGenre?>()
    val groupGenres = mutableMapOf<String, ChartGenre>()
    var searchAnswer: ((String) -> Flow<SearchResults>)? = null
    var chartAnswer: Outcome<ImmutableList<DirectoryHit>, ProviderStatus> = Outcome.Success(persistentListOf())
    var chartGate: CompletableDeferred<Unit>? = null
    var keyVerification: Outcome<Unit, ProviderStatus> = Outcome.Success(Unit)
    var keyGate: CompletableDeferred<Unit>? = null
    var buildKeySource = DirectoryKeySource.NONE

    override fun search(query: String): Flow<SearchResults> =
        flow {
            val trimmed = query.trim()
            queries += trimmed
            val statuses =
                providers.value
                    .associate { provider ->
                        val active = provider.effective && trimmed.isNotEmpty()
                        provider.id to if (active) ProviderStatus.Pending else ProviderStatus.Disabled
                    }.toImmutableMap()
            if (statuses.values.none { it == ProviderStatus.Pending }) {
                emit(SearchResults(trimmed, persistentListOf(), statuses, true))
            } else if (searchAnswer != null) {
                emitAll(checkNotNull(searchAnswer)(trimmed))
            } else {
                emit(SearchResults(trimmed, persistentListOf(), statuses, false))
                val final =
                    statuses.mapValues { entry ->
                        if (entry.value == ProviderStatus.Pending) ProviderStatus.Ok else entry.value
                    }
                emit(SearchResults(trimmed, persistentListOf(), final.toImmutableMap(), true))
            }
        }

    override suspend fun charts(genre: ChartGenre?): Outcome<ImmutableList<DirectoryHit>, ProviderStatus> {
        chartRequests += genre
        if (providers.value.none { it.id == ProviderId.APPLE && it.effective }) {
            return Outcome.Failure(ProviderStatus.Disabled)
        }
        chartGate?.await()
        return chartAnswer
    }

    override fun genreForGroupName(name: String): ChartGenre? = groupGenres[name]

    override fun observeProviders(): Flow<ImmutableList<ProviderInfo>> =
        providers
            .map { current ->
                DIRECTORY_PROVIDER_ORDER.mapNotNull { id -> current.firstOrNull { it.id == id } }.toImmutableList()
            }.distinctUntilChanged()

    override suspend fun setPodcastIndexKey(
        key: String,
        secret: CharArray,
    ): Outcome<Unit, ProviderStatus> {
        keyGate?.await()
        if (keyVerification is Outcome.Success) updateKeySource(DirectoryKeySource.USER)
        return keyVerification
    }

    override suspend fun clearPodcastIndexKey() = updateKeySource(buildKeySource)

    private fun updateKeySource(source: DirectoryKeySource) {
        providers.value =
            providers.value
                .map {
                    if (it.id ==
                        ProviderId.PODCAST_INDEX
                    ) {
                        it.copy(
                            keySource = source,
                            configurationVersion =
                                it.configurationVersion + 1,
                        )
                    } else {
                        it
                    }
                }.toImmutableList()
    }
}
