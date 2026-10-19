// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeSearchRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest : MainDispatcherTest() {
    private val repository = FakeSearchRepository()
    private val groups = FakeGroupRepository()

    @Test
    fun debounceEmitsAt600ButNot599() =
        runTest {
            val vm = DiscoverViewModel(repository, groups)
            vm.state.test {
                awaitItem()
                vm.inputChanged("news")
                assertEquals("news", awaitItem().input)
                advanceTimeBy(599)
                assertTrue(repository.queries.isEmpty())
                advanceTimeBy(1)
                runCurrent()
                assertEquals(listOf("news"), repository.queries)
                assertEquals("news", expectMostRecentItem().results?.query)
                cancelAndIgnoreRemainingEvents()
            }
            vm.viewModelScope.cancel()
        }

    @Test
    fun shortAndPrivateLinkInputNeverSearchAutomatically() =
        runTest {
            val vm = DiscoverViewModel(repository, groups)
            runCurrent()
            for (input in listOf(
                "ab",
                "https://private.test/feed?token=secret",
                "feed:private",
                "@handle",
                "Listen https://private.test/x",
                "192.168.1.2",
                "localhost/feed",
            )) {
                vm.inputChanged(input)
                advanceUntilIdle()
                assertNull(vm.state.value.results)
            }
            assertTrue(repository.queries.isEmpty())
            vm.viewModelScope.cancel()
        }

    @Test
    fun cancelledStaleSearchCannotReplaceNewerAnswer() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            repository.searchAnswer = { query ->
                flow {
                    if (query == "old") withContext(NonCancellable) { gate.await() }
                    emit(results(query))
                }
            }
            val vm = DiscoverViewModel(repository, groups)
            runCurrent()
            vm.inputChanged("old")
            advanceTimeBy(600)
            runCurrent()
            vm.inputChanged("new")
            advanceTimeBy(600)
            runCurrent()
            assertEquals(
                "new",
                vm.state.value.results
                    ?.query,
            )
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "new",
                vm.state.value.results
                    ?.query,
            )
            vm.viewModelScope.cancel()
        }

    @Test
    fun providerChangeDoesNotWaitForTheFirstGroupSnapshot() =
        runTest {
            val pendingGroups =
                object : GroupRepository by groups {
                    override fun observeGroups(): Flow<List<Group>> = flow { awaitCancellation() }
                }
            val vm = DiscoverViewModel(repository, pendingGroups)
            vm.inputChanged("news")
            advanceUntilIdle()
            repository.providers.value =
                repository.providers.value
                    .map { it.copy(configurationVersion = 1) }
                    .toImmutableList()
            advanceUntilIdle()
            assertEquals(listOf("news", "news"), repository.queries)
            vm.viewModelScope.cancel()
        }

    @Test
    fun configurationChangeRestartsQueryAndHidesFailedCharts() =
        runTest {
            repository.chartAnswer = Outcome.Failure(ProviderStatus.RateLimited)
            val vm = DiscoverViewModel(repository, groups)
            runCurrent()
            vm.loadChart(null)
            runCurrent()
            assertTrue(
                vm.state.value.charts
                    .isEmpty(),
            )
            vm.inputChanged("news")
            advanceUntilIdle()
            repository.providers.value =
                repository.providers.value
                    .map { it.copy(configurationVersion = 1) }
                    .toImmutableList()
            advanceUntilIdle()
            vm.loadChart(null)
            advanceUntilIdle()
            assertEquals(listOf("news", "news"), repository.queries)
            assertEquals(2, repository.chartRequests.size)
            vm.viewModelScope.cancel()
        }

    @Test
    fun genreRowsWaitForVisibilityAndCoalesceMatchingGroups() =
        runTest {
            repository.groupGenres["Tech"] = ChartGenre.TECHNOLOGY
            repository.groupGenres["Technology"] = ChartGenre.TECHNOLOGY
            groups.create(GroupDraft("Tech"))
            groups.create(GroupDraft("Technology"))
            val vm = DiscoverViewModel(repository, groups)
            advanceUntilIdle()
            assertTrue(repository.chartRequests.isEmpty())
            vm.loadChart(null)
            advanceUntilIdle()
            assertEquals(listOf<ChartGenre?>(null), repository.chartRequests)
            vm.loadChart(ChartGenre.TECHNOLOGY)
            vm.loadChart(ChartGenre.TECHNOLOGY)
            advanceUntilIdle()
            assertEquals(listOf(null, ChartGenre.TECHNOLOGY), repository.chartRequests)
            assertTrue(
                vm.state.value.charts
                    .all { it.hits != null },
            )
            vm.viewModelScope.cancel()
        }

    @Test
    fun groupRowsFollowRealMatchingGroupsAndDeletion() =
        runTest {
            repository.groupGenres["Tech"] = ChartGenre.TECHNOLOGY
            val vm = DiscoverViewModel(repository, groups)
            advanceUntilIdle()
            val id = (groups.create(GroupDraft("Tech")) as Outcome.Success).value
            groups.create(GroupDraft("Unmapped"))
            advanceUntilIdle()
            assertEquals(
                listOf(null, "Tech"),
                vm.state.value.charts
                    .map { it.title },
            )
            groups.delete(id)
            advanceUntilIdle()
            assertEquals(
                listOf<String?>(null),
                vm.state.value.charts
                    .map { it.title },
            )
            vm.viewModelScope.cancel()
        }

    @Test
    fun directoryExplicitShortQueryIsNotDebouncedAndIgnoresGenre() =
        runTest {
            val vm = DirectoryViewModel(DirectoryKey("AI", "invalid"), repository)
            runCurrent()
            assertEquals(listOf("AI"), repository.queries)
            assertFalse(vm.state.value.loading)
            assertTrue(repository.chartRequests.isEmpty())
            vm.viewModelScope.cancel()
        }

    @Test
    fun unknownGenreAndPrivateQueryNeverFetch() =
        runTest {
            for (key in listOf(DirectoryKey("", "invalid"), DirectoryKey("https://private.test/x", null))) {
                val vm = DirectoryViewModel(key, repository)
                advanceUntilIdle()
                assertTrue(vm.state.value.invalid)
                vm.viewModelScope.cancel()
            }
            assertTrue(repository.queries.isEmpty())
            assertTrue(repository.chartRequests.isEmpty())
        }

    @Test
    fun explicitChartDirectoryShowsFailureThenRetries() =
        runTest {
            repository.chartAnswer = Outcome.Failure(ProviderStatus.Failed(NetError.Offline))
            val vm = DirectoryViewModel(DirectoryKey("", ChartGenre.NEWS.id), repository)
            advanceUntilIdle()
            assertEquals(ProviderStatus.Failed(NetError.Offline), vm.state.value.chartError)
            repository.chartAnswer = Outcome.Success(persistentListOf(hit))
            vm.retry()
            advanceUntilIdle()
            assertNull(vm.state.value.chartError)
            assertEquals(listOf(hit), vm.state.value.hits)
            assertEquals(listOf<ChartGenre?>(ChartGenre.NEWS, ChartGenre.NEWS), repository.chartRequests)
            vm.viewModelScope.cancel()
        }

    @Test
    fun directoryPreservesPartialEmptyDisabledAndFailureStatuses() =
        runTest {
            for (status in listOf(
                ProviderStatus.Pending,
                ProviderStatus.Ok,
                ProviderStatus.Disabled,
                ProviderStatus.RateLimited,
                ProviderStatus.Failed(NetError.Offline),
            )) {
                val answer =
                    results("news").copy(
                        statuses = persistentMapOf(ProviderId.APPLE to status),
                        complete =
                            status != ProviderStatus.Pending,
                    )
                repository.searchAnswer = { flowOf(answer) }
                val vm = DirectoryViewModel(DirectoryKey("news", null), repository)
                advanceUntilIdle()
                assertEquals(answer, vm.state.value.results)
                assertEquals(!answer.complete, vm.state.value.loading)
                vm.viewModelScope.cancel()
            }
        }

    private fun results(query: String) =
        SearchResults(
            query,
            persistentListOf(),
            persistentMapOf(ProviderId.APPLE to ProviderStatus.Ok),
            true,
        )

    private val hit = DirectoryHit("https://example.test/feed", "Show", providers = persistentSetOf(ProviderId.APPLE))
}
