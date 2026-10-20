// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpEnricherTest {
    @Test fun factsNeedsNoAudioAndPreservesDescriptionCompleteness() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val engine = f.enricher()
            f.transport.answer =
                {
                    Json.encodeToString(
                        YtxFacts.serializer(),
                        YtxFacts(
                            VIDEO,
                            duration = 15.25,
                            liveStatus = "not_live",
                            description = "0:00 One\n0:10 Two",
                            descriptionTruncated = true,
                        ),
                    )
                }
            val facts = assertIs<VideoFactsResult.Ok>(engine.facts(VIDEO)).facts
            assertEquals(15_250, facts.durationMs)
            assertEquals(Availability.AVAILABLE, facts.availability)
            assertTrue(facts.descriptionTruncated)
            assertEquals(listOf(YtxMethod.FACTS), f.transport.calls.map { it.method })
            assertEquals(
                YtxDeadline(45_000),
                f.transport.calls
                    .single()
                    .deadline,
            )
            assertEquals(listOf(ExtractionOutcome.Success), f.health.completions.map { it.second })
        }

    @Test fun existingW3ReplayFactsDecodeThroughRealAdapter() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { checkNotNull(javaClass.getResource("/recorded/facts/result.json")).readText() }
            val facts = assertIs<VideoFactsResult.Ok>(f.enricher().facts(VIDEO)).facts
            assertEquals(120_000, facts.durationMs)
            assertEquals("Synthetic episode", facts.title)
            assertEquals("0:00 Synthetic chapter", facts.description)
            assertFalse(facts.descriptionTruncated)
        }

    @Test fun unknownFieldsStayUnknownAndPartialNumbersCannotErasePositiveValues() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { Json.encodeToString(YtxFacts.serializer(), YtxFacts(VIDEO, duration = -1.0)) }
            val facts = assertIs<VideoFactsResult.Ok>(f.enricher().facts(VIDEO)).facts
            assertNull(facts.durationMs)
            assertNull(facts.availability)
            assertNull(facts.description)
            assertNull(facts.isShort)
        }

    @Test fun multiTabEnrichmentReservesOnceAndStopsAtFirstNeededPage() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { tab(listOf(tabItem(VIDEO))).json() }
            val facts = assertIs<EnrichResult.Ok>(f.enricher().enrich(CHANNEL, setOf(VIDEO), 7)).facts
            assertEquals(listOf(VIDEO), facts.map { it.videoId })
            assertEquals(1, f.transport.calls.size)
            assertEquals(
                "videos",
                Json
                    .decodeFromString<YtxTabRequest>(
                        f.transport.calls
                            .single()
                            .json,
                    ).tab,
            )
            assertEquals(1, f.health.reservations.size)
            assertEquals(1, f.health.completions.size)
            f.transport.calls.clear()
            f.transport.answer = { call ->
                if (Json.decodeFromString<YtxTabRequest>(call.json).tab == "shorts") {
                    tab(listOf(tabItem(OTHER_VIDEO))).json()
                } else {
                    tab(emptyList()).json()
                }
            }
            val short = assertIs<EnrichResult.Ok>(f.enricher().enrich(CHANNEL, setOf(OTHER_VIDEO), 7)).facts.single()
            assertEquals(true, short.isShort)
            assertEquals(2, f.transport.calls.size)
            assertTrue(f.transport.calls.all { it.deadline == YtxDeadline(20_000) })
            assertEquals(2, f.health.completions.size)
        }

    @Test fun tabDatesAreUtcDaysAndCursorsRestartWithoutAnotherAdmission() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { call ->
                if (call.method == YtxMethod.TAB_NEXT) throw YtxCallException("CURSOR_EXPIRED")
                tab(listOf(tabItem(VIDEO).copy(timestamp = 1_700_000_123)), "fresh").json()
            }
            val page = assertIs<UploadsPageResult.Ok>(f.enricher().uploadsPage(CHANNEL, 4, UploadsCursor("expired")))
            assertEquals(1_699_920_000_000, page.items.single().publishedAtMs)
            assertTrue(page.items.single().publishedApprox)
            assertEquals(UploadsCursor("fresh"), page.next)
            assertEquals(listOf(YtxMethod.TAB_NEXT, YtxMethod.TAB_OPEN), f.transport.calls.map { it.method })
            assertEquals(1, f.health.reservations.size)
            assertEquals(listOf(ExtractionOutcome.Success), f.health.completions.map { it.second })
        }

    @Test fun malformedPartialAndOversizedResultsFailClosedAndRecover() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val engine = f.enricher()
            for (text in listOf(
                "{",
                "{}",
                Json.encodeToString(YtxFacts.serializer(), YtxFacts(OTHER_VIDEO)),
                Json.encodeToString(YtxFacts.serializer(), YtxFacts(VIDEO, description = "x".repeat(32769))),
            )) {
                f.transport.answer = { text }
                assertEquals(TransientKind.EXTRACTION, assertIs<VideoFactsResult.Failed>(engine.facts(VIDEO)).kind)
            }
            assertEquals(List(4) { ExtractionOutcome.ParseFailure(VIDEO) }, f.health.completions.map { it.second })
            for (page in listOf(
                tab(
                    List(31) {
                        tabItem(VIDEO)
                    },
                ),
                tab(listOf(tabItem("bad"))),
                YtxTabPage(null, emptyList(), true),
            )) {
                f.transport.answer = { page.json() }
                assertIs<UploadsPageResult.Failed>(engine.uploadsPage(CHANNEL, 1, null))
            }
            f.transport.answer =
                { Json.encodeToString(YtxFacts.serializer(), YtxFacts(VIDEO, liveStatus = "not_live")) }
            assertIs<VideoFactsResult.Ok>(engine.facts(VIDEO))
            assertEquals(
                ExtractionOutcome.Success,
                f.health.completions
                    .last()
                    .second,
            )
        }

    @Test fun unavailableClusterAndRateReportsHaveOneOwner() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val engine = f.enricher()
            f.transport.answer = { throw YtxCallException("UNAVAILABLE") }
            assertIs<VideoFactsResult.Ok>(engine.facts(VIDEO))
            assertIs<VideoFactsResult.Ok>(engine.facts(OTHER_VIDEO))
            assertEquals(TransientKind.EXTRACTION, assertIs<VideoFactsResult.Failed>(engine.facts(THIRD_VIDEO)).kind)
            assertEquals(
                ExtractionOutcome.ParseFailure(THIRD_VIDEO),
                f.health.completions
                    .last()
                    .second,
            )
            for (code in listOf("NETWORK", "TIMEOUT", "ENGINE_UNAVAILABLE", "RATE_LIMITED")) {
                f.transport.answer = { throw YtxCallException(code) }
                assertEquals(code, assertIs<VideoFactsResult.Failed>(engine.facts(VIDEO)).kind.name)
                assertEquals(
                    ExtractionOutcome.Inconclusive,
                    f.health.completions
                        .last()
                        .second,
                )
            }
            assertEquals("rate" to f.clock.now(), f.health.reports.single())
        }

    @Test fun cancelledAndLateResultsCompleteInconclusiveAndNeverMapUnavailable() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val late = CompletableDeferred<String>()
            f.transport.answer = { withContext(NonCancellable) { late.await() } }
            val call = async { f.enricher().facts(VIDEO) }
            runCurrent()
            call.cancel()
            late.complete(Json.encodeToString(YtxFacts.serializer(), YtxFacts(VIDEO, liveStatus = "not_live")))
            call.join()
            runCurrent()
            assertEquals(listOf(ExtractionOutcome.Inconclusive), f.health.completions.map { it.second })
        }

    @Test fun deadlineIncludesQueueAndCancellationReleasesReservationExactlyOnce() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { CompletableDeferred<String>().await() }
            val call = async { f.enricher().enrich(CHANNEL, setOf(VIDEO), 7) }
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(TransientKind.TIMEOUT, assertIs<EnrichResult.Failed>(call.await()).kind)
            assertEquals(listOf(ExtractionOutcome.Inconclusive), f.health.completions.map { it.second })
        }

    @Test fun externalAndDeniedOperationsAreInert() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.capabilities.value = f.capabilities.value.copy(enrichment = false, backCatalogue = false)
            val engine = f.enricher()
            assertEquals(VideoFactsResult.Unsupported, engine.facts(VIDEO))
            assertEquals(EnrichResult.Unsupported, engine.enrich(CHANNEL, setOf(VIDEO), 1))
            assertEquals(UploadsPageResult.Unsupported, engine.uploadsPage(CHANNEL, 1, null))
            assertTrue(f.health.reservations.isEmpty())
            f.capabilities.value = f.capabilities.value.copy(enrichment = true, backCatalogue = true)
            f.health.reserve = { ExtractionAdmission.Denied(ExtractionGate.Deny(5000, TransientKind.BREAKER_OPEN)) }
            assertEquals(TransientKind.BREAKER_OPEN, assertIs<VideoFactsResult.Failed>(engine.facts(VIDEO)).kind)
            assertTrue(f.transport.calls.isEmpty())
            assertTrue(f.health.completions.isEmpty())
        }

    private fun ResolverFixture.enricher() =
        YtDlpEnricher(client, caps, health, YtDlpErrorMapper(clock), {
            YtxContext("en", "US", "test-agent")
        }, clock)

    private fun tab(
        items: List<YtxTabItem>,
        cursor: String? = null,
    ) = YtxTabPage(cursor, items, cursor != null)

    private fun tabItem(id: String) = YtxTabItem(id, "Title", 120.0, "not_live", null, null)

    private fun YtxTabPage.json() = Json.encodeToString(YtxTabPage.serializer(), this)

    private companion object {
        val CHANNEL = YouTubeFeedUrls.YOUTUBE_CONTROL_CHANNEL_ID
    }
}
