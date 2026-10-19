// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataResult
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.YouTubeDuplicatePolicy
import ch.lkmc.neutrodyne.core.domain.YouTubeOpenContext
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.FeedGate
import ch.lkmc.neutrodyne.youtube.api.FeedOutcome
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.SearchCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.YtRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class YouTubeContractsTest {
    @Test fun resolverForwardsDepthAndCancellation() =
        runTest {
            val fake = FakeYouTubeChannelResolver()
            val blocked = CompletableDeferred<Unit>()
            fake.resolve = { _, _ ->
                blocked.await()
                channel
            }
            val job = async { fake.resolve(YtRef.Handle("@show"), MetadataDepth.ID_ONLY) }
            runCurrent()
            assertEquals(listOf(YtRef.Handle("@show") to MetadataDepth.ID_ONLY), fake.calls.toList())
            job.cancelAndJoin()
            fake.resolve = { _, _ -> ChannelResolution.NotFound }
            assertEquals(ChannelResolution.NotFound, fake.resolve(YtRef.Channel(ID)))
            assertEquals(MetadataDepth.AVATAR, fake.calls.last().second)
        }

    @Test fun optionalEnginePortsPreserveCursorsAndUnsupportedResults() =
        runTest {
            val lookup = FakeExtractorChannelLookup()
            assertNull(lookup.lookup(YtRef.Channel(ID), MetadataDepth.FULL))
            lookup.result = channel
            assertEquals(channel, lookup.lookup(YtRef.Channel(ID), MetadataDepth.ID_ONLY))
            val search = FakeYouTubeChannelSearch()
            val cursor = SearchCursor("opaque")
            assertEquals(ChannelSearchResult.Unsupported, search.search("show", cursor))
            assertEquals(listOf("show" to cursor), search.calls.toList())
            val enricher = FakeYouTubeEnricher()
            assertEquals(EnrichResult.Unsupported, enricher.enrich(ID, setOf("video"), 3))
            val page = UploadsCursor("page")
            assertEquals(UploadsPageResult.Unsupported, enricher.uploadsPage(ID, 2, page))
            assertEquals(listOf(Triple(ID, setOf("video"), 3)), enricher.calls)
            assertEquals(listOf(Triple(ID, 2, page)), enricher.pages.toList())
            val reader = FakeYouTubeFeedMetadataReader()
            assertNull(reader.read("feed".encodeToByteArray()))
            assertEquals("feed", reader.bodies.single().decodeToString())
        }

    @Test fun healthAndMonitorExposeReportsWithoutSimulatingProductionPolicy() =
        runTest {
            val health = FakeYouTubeHealth()
            health.awaitLoaded()
            health.gate = ExtractionGate.AllowTrial
            assertEquals(ExtractionGate.AllowTrial, health.extractionGate(10))
            health.reportExtraction(ExtractionOutcome.Inconclusive)
            health.reportFeedOutage(10)
            health.reportFeedRateLimited(11, 20)
            health.reportFeedRecovered()
            health.retryNow()
            assertEquals(1, health.loads)
            assertEquals(1, health.retries)
            val reports = listOf(ExtractionOutcome.Inconclusive, "outage" to 10L, 11L to 20L, "recovered")
            assertEquals(reports, health.reports)
            val monitor = FakeYouTubeFeedOutageMonitor()
            monitor.gate = { _, _ -> FeedGate.Deferred(100) }
            assertEquals(FeedGate.Deferred(100), monitor.gate(1, 10))
            monitor.report(1, FeedOutcome.INCONCLUSIVE, 11)
            monitor.onRunFinished(4, 12)
            assertEquals(listOf(Triple(1L, FeedOutcome.INCONCLUSIVE, 11L)), monitor.reports)
            assertEquals(listOf(4 to 12L), monitor.finished)
        }

    @Test fun domainFakesRecordMutationsAndNeverInventSubscriptionSuccess() =
        runTest {
            val repo = FakeYouTubeChannelRepository()
            repo.setVariants(1, 2)
            assertEquals(mapOf(1L to 2), repo.variants)
            assertFailsWith<IllegalArgumentException> { repo.setVariants(1, 0) }
            repo.ensureChannelArt(1)
            repo.loadOlder(1)
            repo.findRssAlternative("show")
            repo.recheckAvailability(2)
            assertEquals(listOf(1L), repo.art)
            assertEquals(listOf(1L), repo.older)
            assertEquals(listOf("show"), repo.searches)
            assertEquals(listOf(2L), repo.rechecks)
            val writer = FakeYouTubeSubscriptionWriter()
            assertEquals(
                Outcome.Failure(SubscribeError.InvalidUrl),
                writer.write(channel, 0, emptySet(), YouTubeDuplicatePolicy.REJECT),
            )
            assertEquals(emptyList(), writer.calls)
            assertEquals(
                Outcome.Failure(SubscribeError.YouTubeNotYetAvailable),
                writer.write(channel, 2, setOf(3), YouTubeDuplicatePolicy.KEEP_EXISTING, "custom"),
            )
            assertEquals("custom", writer.calls.single().customTitle)
            assertEquals(YouTubeDuplicatePolicy.KEEP_EXISTING, writer.calls.single().policy)
            val subscribe = FakeSubscribeUseCase()
            subscribe.nextOutcome = Outcome.Failure(SubscribeError.AlreadySubscribed(5))
            assertEquals(subscribe.nextOutcome, subscribe.youTube(channel, 4, setOf(3)))
            assertEquals(listOf(Triple(channel, 4, setOf(3L))), subscribe.youtubeSubscriptions)
        }

    @Test fun metadataAndSuccessfulOpenCallbacksCanSuspendAndFail() =
        runTest {
            val metadata = FakeYouTubeChannelMetadata()
            assertEquals(ChannelMetadataResult.DEFERRED, metadata.refresh(1, ChannelMetadataReason.DETAIL))
            assertEquals(listOf(1L to ChannelMetadataReason.DETAIL), metadata.calls)
            val opened = FakeYouTubeExternalOpen()
            assertFalse(opened.onOpened(1, YouTubeOpenContext.PLAYABLE_OVERFLOW))
            opened.onOpened = { _, _ -> error("storage failure") }
            assertFailsWith<IllegalStateException> { opened.onOpened(2, YouTubeOpenContext.EXTERNAL_OR_UNPLAYABLE) }
            assertEquals(2, opened.calls.size)
        }

    private companion object {
        const val ID = "UCBJycsmduvYEL83R_U4JriQ"
        val channel = ChannelResolution.Resolved(ID, "show", null, null, null, ResolvedVia.STATIC)
    }
}
