// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DIRECTORY_PROVIDER_ORDER
import ch.lkmc.neutrodyne.core.model.DirectoryAccess
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.feeds.discovery.DiscoveredFeed
import ch.lkmc.neutrodyne.feeds.discovery.DiscoverySource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryContractsTest {
    @Test fun keyReplacementPreservesOldSourceOnFailureAndClearFallsBack() =
        runTest {
            val repo = FakeSearchRepository().apply { buildKeySource = DirectoryKeySource.BUILD }
            repo.clearPodcastIndexKey()
            repo.keyVerification = Outcome.Failure(ProviderStatus.Failed(null))
            repo.setPodcastIndexKey("key", charArrayOf('s'))

            fun source() =
                repo.providers.value
                    .first { it.needsKey }
                    .keySource
            assertEquals(DirectoryKeySource.BUILD, source())
            repo.keyVerification = Outcome.Success(Unit)
            repo.setPodcastIndexKey("key", charArrayOf('s'))
            assertEquals(DirectoryKeySource.USER, source())
            val version =
                repo.providers.value
                    .first { it.needsKey }
                    .configurationVersion
            repo.setPodcastIndexKey("replacement", charArrayOf('s'))
            assertEquals(
                version + 1,
                repo.providers.value
                    .first { it.needsKey }
                    .configurationVersion,
            )
            repo.clearPodcastIndexKey()
            assertEquals(DirectoryKeySource.BUILD, source())
        }

    @Test fun defaultSearchIsColdRetryableAndAllDisabledCompletes() =
        runTest {
            val fake = FakeSearchRepository()
            val stream = fake.search(" news ")
            assertTrue(fake.queries.isEmpty())
            val snapshots = stream.assertSearchContract("news")
            assertEquals(2, snapshots.size)
            assertEquals(ProviderStatus.Disabled, snapshots.last().statuses[ProviderId.PODCAST_INDEX])
            stream.assertSearchContract("news")
            assertEquals(listOf("news", "news"), fake.queries)
            assertEquals(DIRECTORY_PROVIDER_ORDER, fake.observeProviders().first().map { it.id })
            assertEquals(1, fake.search(" ").assertSearchContract("").size)
            fake.providers.value =
                fake.providers.value
                    .map { it.copy(enabled = false) }
                    .toImmutableList()
            assertTrue(
                fake
                    .search("news")
                    .assertSearchContract("news")
                    .single()
                    .hits
                    .isEmpty(),
            )
            assertEquals(Outcome.Failure(ProviderStatus.Disabled), fake.charts(null))
        }

    @Test fun partialFailureRetainsRanksAndCallerCancellationClosesScript() =
        runTest {
            val fake = FakeSearchRepository()
            val states =
                persistentMapOf<ProviderId, ProviderStatus>(
                    ProviderId.APPLE to ProviderStatus.Pending,
                    ProviderId.FYYD to ProviderStatus.Pending,
                    ProviderId.PODCAST_INDEX to ProviderStatus.Disabled,
                )
            val initial = SearchResults("news", persistentListOf(), states, false)
            val hit =
                DirectoryHit(
                    "https://example.invalid/feed",
                    "Example",
                    providers = persistentSetOf(ProviderId.APPLE),
                    ranks = persistentMapOf(ProviderId.APPLE to 1),
                )
            val partial =
                initial.copy(
                    hits = persistentListOf(hit),
                    statuses = states.put(ProviderId.APPLE, ProviderStatus.Ok),
                )
            val failed =
                states
                    .put(ProviderId.APPLE, ProviderStatus.Ok)
                    .put(ProviderId.FYYD, ProviderStatus.Failed(NetError.Timeout))
            val final = partial.copy(statuses = failed, complete = true)
            fake.searchAnswer = { flowOf(initial, partial, final) }
            assertEquals(listOf(initial, partial, final), fake.search("news").assertSearchContract("news"))
            var cancelled = false
            fake.searchAnswer = {
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
            fake.search("old").test { cancelAndIgnoreRemainingEvents() }
            assertTrue(cancelled)
        }

    @Test fun extractionKeepsSourceOwnershipAndGenreIdsAreValidated() {
        val html = Buffer().writeUtf8("<html/>")
        val parser = FakeAutodiscovery()
        parser.answer = listOf(DiscoveredFeed("https://example.invalid/feed", null, DiscoverySource.ALTERNATE_LINK))
        assertEquals(parser.answer, parser.candidates(html, "UTF-8", "https://example.invalid"))
        assertEquals("<html/>", html.readUtf8())
        assertEquals("1318", ChartGenre.TECHNOLOGY.id)
        for (genre in ChartGenre.entries) assertEquals(genre, ChartGenre.fromId(genre.id))
        assertEquals(null, ChartGenre.fromId("NEWS"))
    }
}
