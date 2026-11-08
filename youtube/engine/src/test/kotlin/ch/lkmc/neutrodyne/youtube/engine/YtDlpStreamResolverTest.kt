// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

class YtDlpStreamResolverTest {
    @Test fun invalidAndExternalNeverTouchHostHealthOrCache() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            assertEquals(
                ResolveResult.Unavailable(Availability.UNAVAILABLE),
                f.resolver.resolveAudio("invalid", AudioPref()),
            )
            f.cache.put(VIDEO, AudioPref(), resolved(f.clock))
            f.capabilities.value =
                f.capabilities.value
                    .copy(inAppPlayback = false, externalReason = ExternalReason.NOT_YET_AVAILABLE)
            assertEquals(ResolveResult.Unsupported, f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertTrue(f.transport.calls.isEmpty())
            assertTrue(f.health.reservations.isEmpty())
        }

    @Test fun recordedResolveSingleFlightAndPinnedCacheHitHaveOneHealthOwner() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val release = CompletableDeferred<String>()
            f.transport.answer = { release.await() }
            val a = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            val b = async { f.resolver.resolveAudio(VIDEO, AudioPref(), ResolvePurpose.BACKGROUND) }
            runCurrent()
            assertEquals(1, f.transport.calls.size)
            release.complete(recordedResolve())
            runCurrent()
            val result = assertIs<ResolveResult.Ok>(a.await())
            assertEquals(result, b.await())
            assertEquals(result, f.resolver.resolveAudio(VIDEO, AudioPref(pinnedItag = 140)))
            assertEquals(1, f.transport.calls.size)
            assertEquals(1, f.health.reservations.size)
            assertEquals(listOf(ExtractionOutcome.Success), f.health.completions.map { it.second })
            assertTrue(f.health.reports.isEmpty())
            assertEquals(IpFamily.V4, f.hints.family)
        }

    @Test fun cancellationOfOneWaiterLeavesOtherAndLastWaiterCancelsHost() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val release = CompletableDeferred<String>()
            f.transport.answer = { release.await() }
            val a = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            val b = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            a.cancelAndJoin()
            runCurrent()
            assertTrue(f.transport.cancelled.isEmpty())
            release.complete(recordedResolve())
            assertIs<ResolveResult.Ok>(b.await())
            f.resolver.invalidateAll()
            f.transport.answer = { CompletableDeferred<String>().await() }
            val last = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            last.cancelAndJoin()
            runCurrent()
            assertEquals(listOf(2L), f.transport.cancelled)
            assertEquals(
                listOf(ExtractionOutcome.Success, ExtractionOutcome.Inconclusive),
                f.health.completions.map { it.second },
            )
            f.transport.answer = { recordedResolve() }
            assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertEquals(3, f.health.completions.size)
        }

    @Test fun backgroundFlightSurvivesShortPlaybackWaiterBudget() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.status.value =
                f.transport.status.value
                    .copy(state = HostState.READY)
            val release = CompletableDeferred<String>()
            f.transport.answer = { release.await() }
            val background = async { f.resolver.resolveAudio(VIDEO, AudioPref(), ResolvePurpose.BACKGROUND) }
            runCurrent()
            advanceTimeBy(1000)
            val playback = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            advanceTimeBy(20000)
            runCurrent()
            assertEquals(TransientKind.TIMEOUT, assertIs<ResolveResult.Transient>(playback.await()).kind)
            assertFalse(background.isCompleted)
            assertTrue(f.transport.cancelled.isEmpty())
            assertEquals(
                YtxDeadline(45000),
                f.transport.calls
                    .single()
                    .deadline,
            )
            release.complete(recordedResolve())
            assertIs<ResolveResult.Ok>(background.await())
            assertEquals(1, f.health.completions.size)
        }

    @Test fun backgroundJoinDoesNotExtendColdFlightAndRetriesWithinOwnBudget() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { if (it.id == 1L) CompletableDeferred<String>().await() else recordedResolve() }
            val playback = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            advanceTimeBy(1000)
            val background = async { f.resolver.resolveAudio(VIDEO, AudioPref(), ResolvePurpose.BACKGROUND) }
            runCurrent()
            advanceTimeBy(24000)
            runCurrent()
            assertEquals(TransientKind.TIMEOUT, assertIs<ResolveResult.Transient>(playback.await()).kind)
            assertIs<ResolveResult.Ok>(background.await())
            assertEquals(listOf(YtxDeadline(25000), YtxDeadline(46000)), f.transport.calls.map { it.deadline })
            assertEquals(listOf(1L), f.transport.cancelled)
            assertEquals(
                listOf(ExtractionOutcome.Inconclusive, ExtractionOutcome.Success),
                f.health.completions.map { it.second },
            )
        }

    @Test fun warmColdAndBackgroundDeadlinesIncludeAdmissionWait() =
        runTest {
            for ((state, purpose, budget) in listOf(
                Triple(HostState.READY, ResolvePurpose.PLAYBACK, 20000L),
                Triple(HostState.STOPPED, ResolvePurpose.PLAYBACK, 25000L),
                Triple(HostState.READY, ResolvePurpose.BACKGROUND, 45000L),
            )) {
                val clock = TestClock.from(testScheduler)
                val f = ResolverFixture(backgroundScope, clock)
                f.transport.status.value =
                    f.transport.status.value
                        .copy(state = state)
                val start = clock.elapsedRealtime()
                assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref(), purpose))
                assertEquals(
                    YtxDeadline(start + budget),
                    f.transport.calls
                        .single()
                        .deadline,
                )
            }
        }

    @Test fun authoritativeGateCheckedAfterAdmissionAndCacheNeverReservesTrial() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.status.value =
                f.transport.status.value
                    .copy(workers = 1)
            val release = CompletableDeferred<String>()
            f.transport.answer = { release.await() }
            val blocker = async { f.client.call(YtxMethod.FACTS, "{}", YtxDeadline(1000)) }
            runCurrent()
            val resolve = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            assertTrue(f.health.reservations.isEmpty())
            f.health.reserve = { ExtractionAdmission.Denied(ExtractionGate.Deny(5000, TransientKind.BREAKER_OPEN)) }
            release.complete("{}")
            blocker.await()
            assertEquals(TransientKind.BREAKER_OPEN, assertIs<ResolveResult.Transient>(resolve.await()).kind)
            assertEquals(1, f.transport.calls.size)
            assertTrue(f.health.completions.isEmpty())
            f.cache.put(VIDEO, AudioPref(), resolved(f.clock))
            assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertEquals(1, f.health.reservations.size)
        }

    @Test fun timeoutBeforeAdmissionDoesNotReserveOrCompleteHealth() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.status.value =
                f.transport.status.value
                    .copy(workers = 1)
            f.transport.answer = { CompletableDeferred<String>().await() }
            val blocker = async { f.client.call(YtxMethod.FACTS, "{}", YtxDeadline(60000)) }
            runCurrent()
            val resolve = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            advanceTimeBy(25000)
            runCurrent()
            assertEquals(TransientKind.TIMEOUT, assertIs<ResolveResult.Transient>(resolve.await()).kind)
            assertTrue(f.health.reservations.isEmpty())
            assertTrue(f.health.completions.isEmpty())
            blocker.cancelAndJoin()
        }

    @Test fun pinAndLanguageChoicesNeverJoinIncompatibleFlight() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val release = CompletableDeferred<Unit>()
            f.transport.answer = {
                release.await()
                wire().copy(formats = listOf(wireFormat(140), wireFormat(251))).json()
            }
            val plain = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            val pinned = async { f.resolver.resolveAudio(VIDEO, AudioPref(pinnedItag = 251)) }
            runCurrent()
            assertEquals(2, f.transport.calls.size)
            release.complete(Unit)
            assertEquals(140, assertIs<ResolveResult.Ok>(plain.await()).audio.itag)
            assertEquals(251, assertIs<ResolveResult.Ok>(pinned.await()).audio.itag)
            assertEquals(251, f.cache.get(VIDEO, AudioPref())?.itag)
            assertEquals(2, f.health.completions.size)
        }

    @Test fun invalidationDuringFlightPreventsLateCacheRefillAndDoesNotRemoveReplacement() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val old = CompletableDeferred<String>()
            val fresh = CompletableDeferred<String>()
            f.transport.answer = { if (it.id == 1L) old.await() else fresh.await() }
            val a = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            f.resolver.invalidate(VIDEO)
            val b = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            assertEquals(2, f.transport.calls.size)
            old.complete(wire(wireFormat(suffix = "-drc")).json())
            assertTrue(assertIs<ResolveResult.Ok>(a.await()).audio.isDrc)
            assertNull(f.cache.get(VIDEO, AudioPref()))
            val c = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            assertEquals(2, f.transport.calls.size)
            fresh.complete(recordedResolve())
            assertEquals(b.await(), c.await())
            assertFalse(f.cache.get(VIDEO, AudioPref())!!.isDrc)
            assertTrue(f.health.reports.isEmpty())
        }

    @Test fun identityResetClearsCacheAndHintsEvenWifiToWifiAndOldReplyCannotRestoreThem() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val reset = DefaultNetworkReset(f.resolver::defaultNetworkChanged)
            reset.changed("wifi-one")
            assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertEquals(IpFamily.V4, f.hints.family)
            reset.changed("wifi-one")
            assertEquals(IpFamily.V4, f.hints.family)
            reset.changed("wifi-two")
            assertNull(f.hints.family)
            assertNull(f.cache.get(VIDEO, AudioPref()))
            val release = CompletableDeferred<String>()
            f.transport.answer = { release.await() }
            val old = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            val ctx =
                Json
                    .decodeFromString<YtxVideoRequest>(
                        f.transport.calls
                            .last()
                            .json,
                    ).ctx
            assertNull(ctx.ipFamily)
            reset.changed(null)
            release.complete(recordedResolve())
            assertIs<ResolveResult.Ok>(old.await())
            assertNull(f.hints.family)
            assertNull(f.cache.get(VIDEO, AudioPref()))
            f.transport.answer = { recordedResolve() }
            assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertEquals(3, f.transport.calls.size)
        }

    @Test fun successfulResolveSetsFamilyForNextContextAndAllInvalidationReportsNothing() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            f.hints.set(IpFamily.V6)
            assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertEquals(IpFamily.V4, f.hints.family)
            assertEquals(1, f.transport.calls.size)
            assertEquals(1, f.health.reservations.size)
            f.resolver.invalidateAll()
            assertEquals(IpFamily.V4, f.hints.family)
            assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertEquals(
                "V4",
                Json
                    .decodeFromString<YtxVideoRequest>(
                        f.transport.calls
                            .last()
                            .json,
                    ).ctx.ipFamily,
            )
            f.resolver.invalidate(VIDEO)
            assertTrue(f.health.reports.isEmpty())
        }

    @Test fun nonCooperativeCancelledReplyCannotCacheOrReportSuccess() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            val late = CompletableDeferred<String>()
            f.transport.answer = { withContext(NonCancellable) { late.await() } }
            val old = async { f.resolver.resolveAudio(VIDEO, AudioPref()) }
            runCurrent()
            old.cancelAndJoin()
            runCurrent()
            f.transport.answer = { recordedResolve() }
            val fresh = assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            late.complete(wire(wireFormat(suffix = "-drc")).json())
            runCurrent()
            assertEquals(fresh.audio, f.cache.get(VIDEO, AudioPref()))
            assertEquals(
                listOf(ExtractionOutcome.Success, ExtractionOutcome.Inconclusive),
                f.health.completions.map { it.second },
            )
        }

    @Test fun malformedResultMapsToOneParseFailureAndNextResolveRecovers() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            for (bad in listOf(
                "{private signed-url",
                wire().copy(formats = emptyList()).json(),
                wire(wireFormat().copy(protocol = "sabr")).json(),
            )) {
                f.transport.answer = { bad }
                assertEquals(
                    TransientKind.EXTRACTION,
                    assertIs<ResolveResult.Transient>(f.resolver.resolveAudio(VIDEO, AudioPref())).kind,
                )
                assertNull(f.cache.get(VIDEO, AudioPref()))
            }
            assertEquals(List(3) { ExtractionOutcome.ParseFailure(VIDEO) }, f.health.completions.map { it.second })
            f.transport.answer = { recordedResolve() }
            assertIs<ResolveResult.Ok>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertEquals(
                ExtractionOutcome.Success,
                f.health.completions
                    .last()
                    .second,
            )
        }

    @Test fun perVideoRateNetworkTimeoutAndHostDeathDoNotCountAsParseFailures() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            for (code in listOf(
                "PRIVATE",
                "AGE_RESTRICTED",
                "REGION_BLOCKED",
                "LIVE",
                "UPCOMING",
                "MEMBERS_ONLY",
                "KIDS_ONLY",
                "UNAVAILABLE",
                "RATE_LIMITED",
                "NETWORK",
                "TIMEOUT",
                "ENGINE_UNAVAILABLE",
            )) {
                f.transport.answer = { throw YtxCallException(code) }
                val result = f.resolver.resolveAudio(VIDEO, AudioPref())
                if (code in
                    listOf("RATE_LIMITED", "NETWORK", "TIMEOUT", "ENGINE_UNAVAILABLE")
                ) {
                    assertEquals(code, assertIs<ResolveResult.Transient>(result).kind.name)
                } else {
                    assertEquals(code, assertIs<ResolveResult.Unavailable>(result).reason.name)
                }
            }
            assertEquals(12, f.health.completions.size)
            assertTrue(f.health.completions.all { it.second == ExtractionOutcome.Inconclusive })
            assertEquals(1, f.health.reports.size)
            assertEquals("rate" to f.clock.now(), f.health.reports.single())
        }

    @Test fun unavailableClusterCountsDistinctVideosAndDoesNotPersistThirdReason() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { throw YtxCallException("UNAVAILABLE") }
            assertIs<ResolveResult.Unavailable>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertIs<ResolveResult.Unavailable>(f.resolver.resolveAudio(VIDEO, AudioPref()))
            assertIs<ResolveResult.Unavailable>(f.resolver.resolveAudio(OTHER_VIDEO, AudioPref()))
            assertEquals(
                TransientKind.EXTRACTION,
                assertIs<ResolveResult.Transient>(f.resolver.resolveAudio(THIRD_VIDEO, AudioPref())).kind,
            )
            assertEquals(
                ExtractionOutcome.ParseFailure(THIRD_VIDEO),
                f.health.completions
                    .last()
                    .second,
            )
        }

    @Test fun mismatchedVideoCannotMasqueradeAsStructuredUnavailable() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            f.transport.answer = { wire(video = OTHER_VIDEO).copy(liveStatus = "is_live").json() }
            assertEquals(
                TransientKind.EXTRACTION,
                assertIs<ResolveResult.Transient>(f.resolver.resolveAudio(VIDEO, AudioPref())).kind,
            )
            assertEquals(
                ExtractionOutcome.ParseFailure(VIDEO),
                f.health.completions
                    .single()
                    .second,
            )
        }

    @Test fun structuredLivePostLiveUpcomingAndEmptyPrivateAreNotParseFailures() =
        runTest {
            val f = ResolverFixture(backgroundScope, TestClock.from(testScheduler))
            for ((dto, reason) in listOf(
                wire().copy(liveStatus = "is_live") to Availability.LIVE,
                wire().copy(liveStatus = "post_live") to Availability.LIVE,
                wire().copy(liveStatus = "is_upcoming") to Availability.UPCOMING,
                wire().copy(formats = emptyList(), availability = "private") to Availability.PRIVATE,
            )) {
                f.transport.answer = { dto.json() }
                assertEquals(ResolveResult.Unavailable(reason), f.resolver.resolveAudio(VIDEO, AudioPref()))
            }
            assertTrue(f.health.completions.all { it.second == ExtractionOutcome.Inconclusive })
        }

    @Test fun mappingPastDeadlineCannotCacheOrCompleteHealthAsSuccess() =
        runTest {
            var wireReturned = false
            var elapsed = 0L
            val clock =
                object : Clock {
                    override fun now(): Long {
                        if (wireReturned) elapsed = 25000
                        return TestClock.DEFAULT_NOW
                    }

                    override fun elapsedRealtime(): Long = elapsed
                }
            val f = ResolverFixture(backgroundScope, clock)
            f.transport.answer = {
                wireReturned = true
                recordedResolve()
            }
            val result = f.resolver.resolveAudio(VIDEO, AudioPref())
            assertEquals(TransientKind.TIMEOUT, assertIs<ResolveResult.Transient>(result).kind)
            assertNull(f.cache.get(VIDEO, AudioPref()))
            assertEquals(listOf(ExtractionOutcome.Inconclusive), f.health.completions.map { it.second })
        }
}
