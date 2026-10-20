// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ExtractionReservation
import ch.lkmc.neutrodyne.youtube.api.SearchCursor
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpChannelSearchTest {
    @Test fun openSearchMapsHitsAndKeepsOpaqueCursor() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { page(listOf(hit()), cursor = "opaque-host-token", hasMore = true) }
            val result = assertIs<ChannelSearchResult.Ok>(f.search().search("synthetic"))
            val hit = result.hits.single()
            assertEquals(CHANNEL, hit.channelId)
            assertEquals("Synthetic channel", hit.title)
            assertEquals("https://yt3.example.invalid/avatar", hit.avatarUrl)
            assertEquals(1234L, hit.subscriberCount)
            assertEquals("Synthetic description", hit.description)
            assertTrue(hit.verified)
            assertEquals(SearchCursor("opaque-host-token"), result.next)
            val call = f.transport.calls.single()
            assertEquals(YtxMethod.SEARCH_OPEN, call.method)
            assertEquals(YtxDeadline(20_000), call.deadline)
            val request = Json.decodeFromString<YtxSearchRequest>(call.json)
            assertEquals("synthetic", request.query)
            assertEquals(YtxLimits.SEARCH_PAGE_SIZE, request.pageSize)
            assertEquals(listOf(ExtractionOutcome.Success), f.health.completions.map { it.second })
        }

    @Test fun nextPageSendsTheCursorVerbatim() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { page(listOf(hit()), cursor = null, hasMore = false) }
            val result = assertIs<ChannelSearchResult.Ok>(f.search().search("ignored", SearchCursor("tok-123:ä")))
            assertNull(result.next)
            assertEquals(1, result.hits.size)
            val call = f.transport.calls.single()
            assertEquals(YtxMethod.SEARCH_NEXT, call.method)
            assertEquals("tok-123:ä", Json.decodeFromString<YtxCursorRequest>(call.json).cursor)
        }

    @Test fun pageSizeIsBoundedAndMalformedHitsFailClosed() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val search = f.search()
            f.transport.answer = { page(List(21) { hit() }, null, false) }
            assertEquals(
                TransientKind.EXTRACTION,
                assertIs<ChannelSearchResult.Failed>(search.search("synthetic")).kind,
            )
            f.transport.answer = { page(listOf(hit().copy(channelId = "bad")), null, false) }
            assertEquals(
                TransientKind.EXTRACTION,
                assertIs<ChannelSearchResult.Failed>(search.search("synthetic")).kind,
            )
            f.transport.answer = { page(listOf(hit()), null, hasMore = true) }
            assertEquals(
                TransientKind.EXTRACTION,
                assertIs<ChannelSearchResult.Failed>(search.search("synthetic")).kind,
            )
            assertEquals(3, f.health.completions.size)
            assertTrue(f.health.completions.all { it.second == ExtractionOutcome.ParseFailure(null) })
        }

    @Test fun cursorExpiryEndsPagingWithoutErroring() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { throw YtxCallException("CURSOR_EXPIRED") }
            val result = assertIs<ChannelSearchResult.Ok>(f.search().search("synthetic", SearchCursor("dead")))
            assertTrue(result.hits.isEmpty())
            assertNull(result.next)
            assertEquals(listOf(ExtractionOutcome.Inconclusive), f.health.completions.map { it.second })
        }

    @Test fun queryValidationIsBoundedBeforeAnyWireCall() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val search = f.search()
            for (query in listOf("", "   ", "x".repeat(201))) {
                assertEquals(
                    TransientKind.EXTRACTION,
                    assertIs<ChannelSearchResult.Failed>(search.search(query)).kind,
                )
            }
            assertTrue(f.transport.calls.isEmpty())
            assertTrue(f.health.reservations.isEmpty())
            f.transport.answer = { page(emptyList(), null, false) }
            assertIs<ChannelSearchResult.Ok>(search.search("x".repeat(200)))
            assertEquals(1, f.transport.calls.size)
        }

    @Test fun capabilityOffIsUnsupportedAndInert() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.capabilities.value = f.capabilities.value.copy(channelSearch = false)
            val search = f.search()
            assertEquals(ChannelSearchResult.Unsupported, search.search("synthetic"))
            assertEquals(ChannelSearchResult.Unsupported, search.search("synthetic", SearchCursor("tok")))
            assertTrue(f.transport.calls.isEmpty())
            assertTrue(f.health.reservations.isEmpty())
        }

    @Test fun deniedAdmissionAndTransientCodesMapToFailedKinds() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val search = f.search()
            f.health.reserve = { ExtractionAdmission.Denied(ExtractionGate.Deny(5_000, TransientKind.BREAKER_OPEN)) }
            assertEquals(
                TransientKind.BREAKER_OPEN,
                assertIs<ChannelSearchResult.Failed>(search.search("synthetic")).kind,
            )
            assertTrue(f.transport.calls.isEmpty())
            f.health.reserve = { ExtractionAdmission.Admitted(ExtractionReservation(7)) }
            f.transport.answer = { throw YtxCallException("RATE_LIMITED") }
            assertEquals(
                TransientKind.RATE_LIMITED,
                assertIs<ChannelSearchResult.Failed>(search.search("synthetic")).kind,
            )
            assertTrue(f.health.reports.isNotEmpty())
            assertEquals(1, f.health.completions.size)
        }

    @Test fun cancellationCompletesTheReservationOnce() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { awaitCancellation() }
            val call = async { f.search().search("synthetic") }
            runCurrent()
            call.cancelAndJoin()
            assertEquals(1, f.health.reservations.size)
            assertEquals(1, f.health.completions.size)
        }

    private fun ResolverFixture.search() =
        YtDlpChannelSearch(client, caps, health, { YtxContext("en", "US", "test-agent") }, clock)

    private fun hit() =
        YtxSearchHit(
            CHANNEL,
            "Synthetic channel",
            "@synthetic",
            1234L,
            "https://yt3.example.invalid/avatar",
            "Synthetic description",
            true,
        )

    private fun page(
        hits: List<YtxSearchHit>,
        cursor: String?,
        hasMore: Boolean,
    ) = Json.encodeToString(YtxSearchPage.serializer(), YtxSearchPage(cursor, hits, hasMore))

    private companion object {
        val CHANNEL = YouTubeFeedUrls.YOUTUBE_CONTROL_CHANNEL_ID
    }
}
