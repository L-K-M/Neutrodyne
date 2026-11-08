// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YtRef
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpChannelLookupTest {
    @Test fun handleLookupAnswersExtractorIdentity() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { lookupWire() }
            val result =
                assertIs<ChannelResolution.Resolved>(
                    f.lookup().lookup(YtRef.Handle("@synthetic"), MetadataDepth.AVATAR),
                )
            assertEquals(CHANNEL, result.channelId)
            assertEquals("Synthetic channel", result.title)
            assertEquals("https://yt3.example.invalid/avatar", result.avatarUrl)
            assertNull(result.bannerUrl)
            assertNull(result.description)
            assertEquals(ResolvedVia.EXTRACTOR, result.via)
            val request =
                Json.decodeFromString<YtxLookupRequest>(
                    f.transport.calls
                        .single()
                        .json,
                )
            assertEquals("https://www.youtube.com/@synthetic", request.url)
            assertEquals(listOf(YtxMethod.LOOKUP), f.transport.calls.map { it.method })
            assertEquals(
                YtxDeadline(20_000),
                f.transport.calls
                    .single()
                    .deadline,
            )
            assertEquals(listOf(ExtractionOutcome.Success), f.health.completions.map { it.second })
        }

    @Test fun everyChannelRefKindGetsItsCanonicalUrl() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { lookupWire() }
            val lookup = f.lookup()
            val urls = mutableListOf<String>()
            f.transport.answer = { call ->
                urls += Json.decodeFromString<YtxLookupRequest>(call.json).url
                lookupWire()
            }
            lookup.lookup(YtRef.Channel(CHANNEL), MetadataDepth.FULL)
            lookup.lookup(YtRef.LegacyPath("c/legacy"), MetadataDepth.AVATAR)
            lookup.lookup(YtRef.LegacyPath("user/olduser"), MetadataDepth.AVATAR)
            lookup.lookup(YtRef.LegacyPath("custom"), MetadataDepth.AVATAR)
            assertEquals(
                listOf(
                    "https://www.youtube.com/channel/$CHANNEL",
                    "https://www.youtube.com/c/legacy",
                    "https://www.youtube.com/user/olduser",
                    "https://www.youtube.com/custom",
                ),
                urls,
            )
        }

    @Test fun unicodeAndEscapedHandlesPercentEncodeLikeLayerA() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val urls = mutableListOf<String>()
            f.transport.answer = { call ->
                urls += Json.decodeFromString<YtxLookupRequest>(call.json).url
                lookupWire()
            }
            f.lookup().lookup(YtRef.Handle("@Bäcker"), MetadataDepth.ID_ONLY)
            f.lookup().lookup(YtRef.Handle("@a b?c"), MetadataDepth.ID_ONLY)
            assertEquals(
                listOf(
                    "https://www.youtube.com/@B%C3%A4cker",
                    "https://www.youtube.com/@a%20b%3Fc",
                ),
                urls,
            )
        }

    @Test fun nonChannelRefsStayLayerAOnly() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val lookup = f.lookup()
            assertNull(lookup.lookup(YtRef.Video(VIDEO), MetadataDepth.FULL))
            assertNull(lookup.lookup(YtRef.Playlist("PLxxxxxxxxxxxxxxx"), MetadataDepth.AVATAR))
            assertNull(lookup.lookup(YtRef.Query("synthetic"), MetadataDepth.FULL))
            assertTrue(f.transport.calls.isEmpty())
            assertTrue(f.health.reservations.isEmpty())
        }

    @Test fun depthTrimsFieldsExactly() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { lookupWire() }
            val lookup = f.lookup()
            val idOnly =
                assertIs<ChannelResolution.Resolved>(
                    lookup.lookup(YtRef.Handle("@synthetic"), MetadataDepth.ID_ONLY),
                )
            assertEquals(CHANNEL, idOnly.channelId)
            assertNull(idOnly.title)
            assertNull(idOnly.avatarUrl)
            val full =
                assertIs<ChannelResolution.Resolved>(
                    lookup.lookup(YtRef.Handle("@synthetic"), MetadataDepth.FULL),
                )
            assertEquals("Synthetic description", full.description)
            assertEquals("https://yt3.example.invalid/banner", full.bannerUrl)
            assertEquals("https://yt3.example.invalid/avatar", full.avatarUrl)
        }

    @Test fun invalidImageUrlsDropWithoutPoisoningIdentity() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = {
                lookupWire(
                    avatars =
                        listOf(
                            YtxImage("http://insecure.invalid/a", 0, 0),
                            YtxImage("https://yt3.example.invalid/ok", 48, 48),
                        ),
                    banners = listOf(YtxImage("notaurl", 0, 0)),
                )
            }
            val result =
                assertIs<ChannelResolution.Resolved>(f.lookup().lookup(YtRef.Handle("@s"), MetadataDepth.FULL))
            assertEquals(CHANNEL, result.channelId)
            assertEquals("https://yt3.example.invalid/ok", result.avatarUrl)
            assertNull(result.bannerUrl)
        }

    @Test fun malformedChannelIdFailsClosedAsExtraction() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { lookupWire(channelId = "XXnotachannel") }
            val result = f.lookup().lookup(YtRef.Handle("@synthetic"), MetadataDepth.AVATAR)
            val failed = assertIs<ChannelResolution.Failed>(result)
            assertEquals(FailReason.PAGE_UNREADABLE, failed.reason)
            assertFalse(failed.retryable)
            assertEquals(listOf(ExtractionOutcome.ParseFailure(null)), f.health.completions.map { it.second })
        }

    @Test fun coarseUnavailableFallsBackToLayerANeverNotFound() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { throw YtxCallException("UNAVAILABLE") }
            // UNAVAILABLE also covers removed/terminated text and URL rejection; only Layer A's
            // definite 404/410 may answer NotFound, so the extractor must stay ambiguous here.
            val result = f.lookup().lookup(YtRef.Handle("@synthetic"), MetadataDepth.AVATAR)
            val failed = assertIs<ChannelResolution.Failed>(result)
            assertEquals(FailReason.PAGE_UNREADABLE, failed.reason)
            assertFalse(failed.retryable)
            assertEquals(listOf(ExtractionOutcome.Inconclusive), f.health.completions.map { it.second })
        }

    @Test fun transientAndRateLimitedFailuresKeepTheirReason() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val lookup = f.lookup()
            f.transport.answer = { throw YtxCallException("TIMEOUT") }
            val timeout = assertIs<ChannelResolution.Failed>(lookup.lookup(YtRef.Handle("@s"), MetadataDepth.AVATAR))
            assertEquals(FailReason.NETWORK, timeout.reason)
            assertTrue(timeout.retryable)
            f.transport.answer = { throw YtxCallException("RATE_LIMITED") }
            val limited = assertIs<ChannelResolution.Failed>(lookup.lookup(YtRef.Handle("@s"), MetadataDepth.AVATAR))
            assertEquals(FailReason.RATE_LIMITED, limited.reason)
            assertTrue(limited.retryable)
            assertEquals(2, f.health.completions.size)
            assertTrue(f.health.reports.isNotEmpty())
        }

    @Test fun capabilityOffIsInertAndDeniedAdmissionStaysOffWire() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.capabilities.value = f.capabilities.value.copy(channelSearch = false)
            val lookup = f.lookup()
            assertNull(lookup.lookup(YtRef.Handle("@s"), MetadataDepth.FULL))
            assertTrue(f.transport.calls.isEmpty())
            assertTrue(f.health.reservations.isEmpty())
            f.capabilities.value = f.capabilities.value.copy(channelSearch = true)
            // An open breaker means the extractor cannot help; null keeps Layer A eligible.
            f.health.reserve = { ExtractionAdmission.Denied(ExtractionGate.Deny(5_000, TransientKind.BREAKER_OPEN)) }
            assertNull(lookup.lookup(YtRef.Handle("@s"), MetadataDepth.FULL))
            assertTrue(f.transport.calls.isEmpty())
            f.health.reserve = { ExtractionAdmission.Denied(ExtractionGate.Deny(5_000, TransientKind.RATE_LIMITED)) }
            val denied = assertIs<ChannelResolution.Failed>(lookup.lookup(YtRef.Handle("@s"), MetadataDepth.FULL))
            assertEquals(FailReason.RATE_LIMITED, denied.reason)
            assertTrue(f.health.completions.isEmpty())
        }

    @Test fun cancellationStillCompletesTheReservationOnce() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { kotlinx.coroutines.awaitCancellation() }
            val call = async { f.lookup().lookup(YtRef.Handle("@s"), MetadataDepth.FULL) }
            runCurrent()
            call.cancelAndJoin()
            assertEquals(1, f.health.reservations.size)
            assertEquals(1, f.health.completions.size)
        }

    private fun ResolverFixture.lookup() =
        YtDlpChannelLookup(client, caps, health, { YtxContext("en", "US", "test-agent") }, clock)

    private fun lookupWire(
        channelId: String = CHANNEL,
        avatars: List<YtxImage> = listOf(YtxImage("https://yt3.example.invalid/avatar", 48, 48)),
        banners: List<YtxImage> = listOf(YtxImage("https://yt3.example.invalid/banner", 0, 0)),
    ) = Json.encodeToString(
        YtxLookup.serializer(),
        YtxLookup(
            channelId,
            "Synthetic channel",
            "@synthetic",
            "Synthetic description",
            1234L,
            avatars,
            banners,
        ),
    )

    private companion object {
        val CHANNEL = YouTubeFeedUrls.YOUTUBE_CONTROL_CHANNEL_ID
    }
}
