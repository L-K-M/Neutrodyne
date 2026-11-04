// SPDX-License-Identifier: Unlicense
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import ch.lkmc.neutrodyne.youtube.api.ExtractionReservation
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys as Keys

class YouTubeExtractionHealthTest {
    @Test fun fifthParseReportImmediatelyDeniesAndInvalidatesBeforeAdmission() =
        runTest {
            val f = fixture()
            repeat(4) { f.parse("v$it") }
            assertEquals(ExtractionGate.Allow, f.gate())
            f.parse("v4")
            assertEquals(1, f.invalidations)
            assertIs<ExtractionGate.Deny>(f.duringInvalidation)
            assertEquals(
                ExtractionGate.Deny(f.clock.now() + 6 * HOUR, TransientKind.BREAKER_OPEN),
                f.gate(),
            )
            assertEquals(BreakerState.OPEN, f.health.state.value.breaker)
            assertEquals(0L, f.settings.get(Keys.BREAKER_OPEN_UNTIL)) // IO has not run yet.
            f.health.awaitLoaded()
            assertEquals(f.clock.now() + 6 * HOUR, f.settings.get(Keys.BREAKER_OPEN_UNTIL))
        }

    @Test fun tenMinuteDedupAndOneHourWindowHaveExactBoundaries() =
        runTest {
            val f = fixture()
            repeat(8) { f.parse("same") }
            f.now += 10 * MINUTE - 1
            f.parse("same")
            assertEquals(ExtractionGate.Allow, f.gate())
            f.now++
            f.parse("same")
            f.parse("a")
            f.parse("b")
            assertEquals(ExtractionGate.Allow, f.gate())
            f.parse("c")
            assertIs<ExtractionGate.Deny>(f.gate())
            val expired = fixture()
            repeat(4) { expired.parse("$it") }
            expired.now += HOUR
            expired.parse("new")
            assertEquals(ExtractionGate.Allow, expired.gate())
        }

    @Test fun fresh403RequiresThreeDistinctThenCountsOncePerVideo() =
        runTest {
            val f = fixture()
            repeat(8) { f.fresh("one") }
            f.fresh("two")
            repeat(4) { f.parse("p$it") }
            assertEquals(ExtractionGate.Allow, f.gate())
            f.fresh("three")
            assertIs<ExtractionGate.Deny>(f.gate())
            val sparse = fixture()
            sparse.fresh("one")
            sparse.fresh("two")
            sparse.now += HOUR
            sparse.fresh("three")
            repeat(4) { sparse.parse("p$it") }
            assertEquals(ExtractionGate.Allow, sparse.gate())
        }

    @Test fun gateReadsNeverReserveAndOnlyOneTrialCompletesOnce() =
        runTest {
            val f = fixture()
            f.open()
            f.now += 6 * HOUR
            repeat(10) { assertEquals(ExtractionGate.AllowTrial, f.gate()) }
            assertEquals(BreakerState.HALF_OPEN, f.health.state.value.breaker)
            val first = async { f.health.reserveExtraction(f.clock.now()) }
            val second = async { f.health.reserveExtraction(f.clock.now()) }
            val results = listOf(first.await(), second.await())
            val token = results.filterIsInstance<ExtractionAdmission.Admitted>().single().reservation
            assertEquals(1, results.filterIsInstance<ExtractionAdmission.Denied>().size)
            assertEquals(BreakerState.HALF_OPEN, f.health.state.value.breaker)
            f.health.completeExtraction(token, ExtractionOutcome.Success)
            f.health.completeExtraction(token, ExtractionOutcome.ParseFailure("late"))
            assertEquals(ExtractionGate.Allow, f.gate())
            assertEquals(BreakerState.CLOSED, f.health.state.value.breaker)
        }

    @Test fun cancelledTrialReleasesWithoutClosingAndNextFailureReopensTwelveHours() =
        runTest {
            val f = fixture()
            f.open()
            f.health.retryNow()
            val token = f.reserve()
            val job =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        f.health.completeExtraction(token, ExtractionOutcome.Inconclusive)
                    }
                }
            runCurrent()
            job.cancelAndJoin()
            assertEquals(ExtractionGate.AllowTrial, f.gate())
            f.health.completeExtraction(f.reserve(), ExtractionOutcome.ParseFailure("retry"))
            assertEquals(f.clock.now() + 12 * HOUR, f.health.state.value.breakerOpenUntil)
            assertEquals(2, f.invalidations)
            f.now += 24 * HOUR
            f.health.completeExtraction(f.reserve(), ExtractionOutcome.ParseFailure(null))
            assertEquals(f.clock.now() + 6 * HOUR, f.health.state.value.breakerOpenUntil)
        }

    @Test fun oldOperationsCannotCloseOpenBreakerOrSurviveVersionReset() =
        runTest {
            val f = fixture()
            val oldSuccess = f.reserve()
            val oldFailure = f.reserve()
            f.open()
            f.health.reportRateLimited(f.clock.now())
            f.health.completeExtraction(oldSuccess, ExtractionOutcome.Success)
            assertEquals(BreakerState.OPEN, f.health.state.value.breaker)
            assertNull(f.health.state.value.rateLimitedUntil)
            f.health.reportEngineVersion("new")
            f.health.completeExtraction(oldFailure, ExtractionOutcome.ParseFailure("old"))
            repeat(4) { f.parse("new$it") }
            assertEquals(ExtractionGate.Allow, f.gate())
            f.parse("new4")
            assertEquals(f.clock.now() + 6 * HOUR, f.health.state.value.breakerOpenUntil)
            assertTrue(f.reserveDenied())
        }

    @Test fun engineRateDoublesIndependentlyAndSuccessRestoresInitialDelay() =
        runTest {
            val f = fixture()
            f.health.reportFeedOutage(f.clock.now())
            f.health.reportFeedRateLimited(f.clock.now(), 8 * HOUR)
            f.health.awaitLoaded()
            for (minutes in listOf(30, 60, 120, 240, 360, 360)) {
                f.health.reportRateLimited(f.clock.now())
                assertEquals(f.clock.now() + minutes * MINUTE, f.health.state.value.rateLimitedUntil)
                assertEquals(
                    TransientKind.RATE_LIMITED,
                    assertIs<ExtractionGate.Deny>(f.gate()).kind,
                )
            }
            assertEquals(f.clock.now() + 8 * HOUR, f.health.state.value.feedRateLimitedUntil)
            f.health.reportFeedRecovered()
            f.health.awaitLoaded()
            assertEquals(f.clock.now() + 6 * HOUR, f.health.state.value.rateLimitedUntil)
            f.now += 6 * HOUR
            f.health.completeExtraction(f.reserve(), ExtractionOutcome.Success)
            assertNull(f.health.state.value.rateLimitedUntil)
            f.health.reportRateLimited(f.clock.now())
            assertEquals(f.clock.now() + 30 * MINUTE, f.health.state.value.rateLimitedUntil)
            f.health.reportEngineVersion("next-bundle")
            assertEquals(f.clock.now() + 30 * MINUTE, f.health.state.value.rateLimitedUntil)
        }

    @Test fun restartRestoresDeadlinesButNotReservationsAndAppChangeResets() =
        runTest {
            val f = fixture()
            f.open()
            f.health.reportRateLimited(f.clock.now())
            f.health.awaitLoaded()
            val restarted = DefaultYouTubeHealth(f.settings, f.clock, backgroundScope, appVersionCode = 42) {}
            restarted.awaitLoaded()
            assertEquals(f.health.state.value, restarted.state.value)
            f.now += 6 * HOUR
            assertIs<ExtractionAdmission.Admitted>(restarted.reserveExtraction(f.clock.now()))
            val third = DefaultYouTubeHealth(f.settings, f.clock, backgroundScope, appVersionCode = 42) {}
            third.awaitLoaded()
            assertIs<ExtractionAdmission.Admitted>(third.reserveExtraction(f.clock.now()))
            val upgraded = DefaultYouTubeHealth(f.settings, f.clock, backgroundScope, appVersionCode = 43) {}
            upgraded.awaitLoaded()
            assertEquals(ExtractionGate.Allow, upgraded.extractionGate(f.clock.now()))
            assertEquals(f.health.state.value.rateLimitedUntil, upgraded.state.value.rateLimitedUntil)
            assertEquals(43, f.settings.get(Keys.BREAKER_VERSION_CODE))
            assertEquals(0L, f.settings.get(Keys.BREAKER_OPEN_UNTIL))
            val downgraded = DefaultYouTubeHealth(f.settings, f.clock, backgroundScope, appVersionCode = 42) {}
            downgraded.awaitLoaded()
            assertEquals(ExtractionGate.Allow, downgraded.extractionGate(f.clock.now()))
            assertNull(downgraded.state.value.breakerOpenUntil)
        }

    @Test fun resetDuringSuspendedPersistenceIsLastAndSurvivesRestart() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val base = FakeSettingsRepository()
            var block = false
            val repository =
                object : SettingsRepository by base {
                    override suspend fun <T : Any> set(
                        key: SettingKey<T>,
                        value: T,
                    ): Outcome<Unit, SettingsError> {
                        if (block && key == Keys.BREAKER_OPEN_UNTIL) {
                            block = false
                            entered.complete(Unit)
                            release.await()
                        }
                        return base.set(key, value)
                    }
                }
            val f = fixture(repository)
            block = true
            f.open()
            entered.await()
            f.health.reportEngineVersion("fixed")
            assertEquals(ExtractionGate.Allow, f.gate())
            release.complete(Unit)
            f.health.awaitLoaded()
            val restarted = DefaultYouTubeHealth(repository, f.clock, backgroundScope, appVersionCode = 42) {}
            restarted.awaitLoaded()
            restarted.reportEngineVersion("fixed")
            assertEquals(ExtractionGate.Allow, restarted.extractionGate(f.clock.now()))
            assertEquals(0L, base.get(Keys.BREAKER_OPEN_UNTIL))
            assertEquals("fixed", base.get(Keys.BREAKER_ENGINE_VERSION))
        }

    @Test fun persistenceFailureDoesNotUndoGateOrKillWriterAndLaterSnapshotRecovers() =
        runTest {
            val f = fixture()
            f.settings.failNextSet = SettingsError.WriteFailed
            f.open()
            f.health.awaitLoaded()
            assertEquals(BreakerState.OPEN, f.health.state.value.breaker)
            f.health.retryNow()
            f.health.awaitLoaded()
            f.health.completeExtraction(f.reserve(), ExtractionOutcome.Success)
            f.health.awaitLoaded()
            assertEquals(0L, f.settings.get(Keys.BREAKER_OPEN_UNTIL))
            assertEquals(42, f.settings.get(Keys.BREAKER_VERSION_CODE))
        }

    @Test fun versionResetDuringLoadWinsAndInconclusiveNeverTrips() =
        runTest {
            val base = FakeSettingsRepository()
            base.set(Keys.BREAKER_VERSION_CODE, 42)
            base.set(Keys.BREAKER_ENGINE_VERSION, "old")
            base.set(Keys.BREAKER_OPEN_UNTIL, 99 * HOUR)
            val barrier = CompletableDeferred<Unit>()
            val repository =
                object : SettingsRepository by base {
                    override suspend fun <T : Any> get(key: SettingKey<T>): T {
                        barrier.await()
                        return base.get(key)
                    }
                }
            val f = Fixture(this, repository)
            f.health.reportEngineVersion("new")
            barrier.complete(Unit)
            f.health.awaitLoaded()
            repeat(20) { f.health.completeExtraction(f.reserve(), ExtractionOutcome.Inconclusive) }
            assertEquals(ExtractionGate.Allow, f.gate())
            assertEquals("new", base.get(Keys.BREAKER_ENGINE_VERSION))
        }

    @Test fun successfulOperationsDoNotEraseTheRollingFailureWindow() =
        runTest {
            val f = fixture()
            repeat(4) {
                f.parse("failure$it")
                f.health.completeExtraction(f.reserve(), ExtractionOutcome.Success)
            }
            f.parse("failure4")
            assertIs<ExtractionGate.Deny>(f.gate())
        }

    private suspend fun TestScope.fixture(repository: SettingsRepository? = null): Fixture =
        Fixture(this, repository).also {
            it.health.awaitLoaded()
            it.health.reportEngineVersion("bundled")
            it.health.awaitLoaded()
        }

    private class Fixture(
        scope: TestScope,
        repository: SettingsRepository?,
    ) {
        val clock = TestClock(nowMs = HOUR)
        var now: Long
            get() = clock.nowMs
            set(value) {
                clock.nowMs = value
            }
        val settings = FakeSettingsRepository()
        var invalidations = 0
        var duringInvalidation: ExtractionGate? = null
        val health: DefaultYouTubeHealth =
            DefaultYouTubeHealth(
                repository ?: settings,
                clock,
                scope.backgroundScope,
                appVersionCode = 42,
                invalidateUrls = ::invalidate,
            ) {}

        private fun invalidate() {
            invalidations++
            duringInvalidation = health.extractionGate(clock.now())
        }

        fun parse(video: String) = health.reportExtraction(ExtractionOutcome.ParseFailure(video))

        fun fresh(video: String) = health.reportExtraction(ExtractionOutcome.ForbiddenFreshUrl(video))

        fun open() {
            repeat(5) { parse("v$it") }
        }

        fun reserve(): ExtractionReservation =
            assertIs<ExtractionAdmission.Admitted>(health.reserveExtraction(clock.now())).reservation

        fun gate() = health.extractionGate(clock.now())

        fun reserveDenied() = health.reserveExtraction(clock.now()) is ExtractionAdmission.Denied
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
