// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.youtube

import ch.lkmc.neutrodyne.core.navigation.YouTubeSearchKey
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeChannelSearch
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.youtube.api.ChannelHit
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineStatus
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.SearchCursor
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The explicit channel-search page (W10): capable start searches exactly once, pages are bounded
 * to three, cursor expiry ends paging while hits survive, a capability flip drops everything and
 * a superseded page answer cannot overwrite newer state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeSearchViewModelTest : MainDispatcherTest() {
    private val search = FakeYouTubeChannelSearch()
    private val capabilities =
        FakeYouTubeCapabilitiesSource().also {
            it.capabilities.value = it.capabilities.value.copy(channelSearch = true, externalReason = null)
        }
    private val engine = RecordingEngine()
    private val key = YouTubeSearchKey("synthetic")

    /** Valid `UC…` IDs per [ch.lkmc.neutrodyne.youtube.api.YouTubeIds.CHANNEL]. */
    private fun hit(index: Int = 0) =
        ChannelHit(
            "UC" + index.toString().padStart(21, 'a') + "w",
            "Channel $index",
            null,
            null,
            null,
            false,
        )

    @Test fun capableStartSearchesOnceAndPrewarmsSearch() =
        runTest {
            search.result = ChannelSearchResult.Ok(listOf(hit()), null)
            val viewModel = YouTubeSearchViewModel(key, search, capabilities, engine)
            advanceUntilIdle()
            assertEquals(listOf("synthetic" to null), search.calls.map { it.first to it.second })
            assertEquals(listOf(PrewarmReason.SEARCH), engine.prewarms)
            val state = viewModel.state.value
            assertEquals(1, state.hits.size)
            assertTrue(state.ended)
            assertTrue(state.attempted)
        }

    @Test fun incapableStartNeverCallsThePortOrPrewarms() =
        runTest {
            val incapable = FakeYouTubeCapabilitiesSource()
            val viewModel = YouTubeSearchViewModel(key, search, incapable, engine)
            advanceUntilIdle()
            assertTrue(search.calls.isEmpty())
            assertTrue(engine.prewarms.isEmpty())
            assertEquals(false, viewModel.state.value.capable)
        }

    @Test fun threePagesCapTheListAndStopPaging() =
        runTest {
            search.result = ChannelSearchResult.Ok(listOf(hit(1)), SearchCursor("c2"))
            val viewModel = YouTubeSearchViewModel(key, search, capabilities, engine)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canPage)
            search.result = ChannelSearchResult.Ok(listOf(hit(2)), SearchCursor("c3"))
            viewModel.loadMore()
            advanceUntilIdle()
            search.result = ChannelSearchResult.Ok(listOf(hit(3)), SearchCursor("c4"))
            viewModel.loadMore()
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(3, state.hits.size)
            assertTrue(state.ended)
            assertEquals(
                listOf("c2", "c3").map(::SearchCursor),
                search.calls.drop(1).map { it.second },
            )
            // The cap is terminal: further paging requests are ignored.
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(3, search.calls.size)
        }

    @Test fun cursorExpiryEndsPagingAndKeepsLoadedHits() =
        runTest {
            search.result = ChannelSearchResult.Ok(listOf(hit(1), hit(2)), SearchCursor("c2"))
            val viewModel = YouTubeSearchViewModel(key, search, capabilities, engine)
            advanceUntilIdle()
            // The engine turns a dead cursor into an empty terminal page.
            search.result = ChannelSearchResult.Ok(emptyList(), null)
            viewModel.loadMore()
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(2, state.hits.size)
            assertNull(state.error)
            assertTrue(state.ended)
        }

    @Test fun failureRetriesTheSamePage() =
        runTest {
            search.result = ChannelSearchResult.Ok(listOf(hit(1)), SearchCursor("c2"))
            val viewModel = YouTubeSearchViewModel(key, search, capabilities, engine)
            advanceUntilIdle()
            search.result = ChannelSearchResult.Failed(TransientKind.RATE_LIMITED)
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(TransientKind.RATE_LIMITED, viewModel.state.value.error)
            assertEquals(1, viewModel.state.value.hits.size)
            search.result = ChannelSearchResult.Ok(listOf(hit(2)), null)
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(2, viewModel.state.value.hits.size)
            assertEquals(SearchCursor("c2"), search.calls.last().second)
        }

    @Test fun capabilityFlipDropsStaleResultsAndStopsThePort() =
        runTest {
            val localSearch =
                FakeYouTubeChannelSearch().also {
                    it.result = ChannelSearchResult.Ok(listOf(hit(1)), SearchCursor("c2"))
                }
            val viewModel = YouTubeSearchViewModel(key, localSearch, capabilities, engine)
            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.hits.size)
            // The flip invalidates everything: no further port calls, no stale hits.
            capabilities.capabilities.value =
                capabilities.capabilities.value.copy(channelSearch = false)
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(false, state.capable)
            assertTrue(state.hits.isEmpty())
            assertNull(state.error)
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(1, localSearch.calls.size)
            // A flip back to capable restarts from page one on the same screen.
            localSearch.result = ChannelSearchResult.Ok(listOf(hit(7)), null)
            capabilities.capabilities.value =
                capabilities.capabilities.value.copy(channelSearch = true)
            advanceUntilIdle()
            assertEquals(
                "Channel 7",
                viewModel.state.value.hits
                    .single()
                    .title,
            )
        }

    @Test fun capabilityOffDropsInFlightPageAndReenableRestartsFromFirstPage() =
        runTest {
            // The parked page ignores cooperative cancellation: its late answer still lands on
            // the coroutine and must be fenced by cancellation + generation, never mutate state.
            val gate = CompletableDeferred<Unit>()
            search.search = { _, _ ->
                withContext(NonCancellable) { gate.await() }
                ChannelSearchResult.Ok(listOf(hit(9)), SearchCursor("late"))
            }
            val viewModel = YouTubeSearchViewModel(key, search, capabilities, engine)
            runCurrent()
            assertTrue(viewModel.state.value.loading)

            capabilities.capabilities.value = capabilities.capabilities.value.copy(channelSearch = false)
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(YouTubeSearchState(capable = false), viewModel.state.value)

            capabilities.capabilities.value = capabilities.capabilities.value.copy(channelSearch = true)
            advanceUntilIdle()
            assertEquals(
                listOf<Pair<String, SearchCursor?>>("synthetic" to null, "synthetic" to null),
                search.calls,
            )
            assertTrue(viewModel.state.value.capable)
        }

    private class RecordingEngine : YouTubeEngine {
        val prewarms = mutableListOf<PrewarmReason>()
        override val status = MutableStateFlow(EngineStatus(EngineAvailability.STOPPED))

        override fun prewarm(reason: PrewarmReason) {
            prewarms += reason
        }

        override fun retryStart() = Unit
    }
}
