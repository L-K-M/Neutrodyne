// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ExtractionAdmission
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class YouTubeExtractionRaceTest {
    @Test fun twoWorkerThreadsRaceForExactlyOneTrialAndReleaseIt() =
        runTest {
            val clock = TestClock()
            val health = DefaultYouTubeHealth(FakeSettingsRepository(), clock, backgroundScope, appVersionCode = 42) {}
            health.awaitLoaded()
            repeat(5) { health.reportExtraction(ExtractionOutcome.ParseFailure("v$it")) }
            health.retryNow()
            repeat(100) {
                val start = CompletableDeferred<Unit>()
                val contenders =
                    List(2) {
                        async(Dispatchers.Default) {
                            start.await()
                            health.reserveExtraction(clock.now())
                        }
                    }
                start.complete(Unit)
                val outcomes = contenders.awaitAll()
                val token = outcomes.filterIsInstance<ExtractionAdmission.Admitted>().single().reservation
                assertEquals(1, outcomes.filterIsInstance<ExtractionAdmission.Denied>().size)
                health.completeExtraction(token, ExtractionOutcome.Inconclusive)
                assertEquals(ExtractionGate.AllowTrial, health.extractionGate(clock.now()))
            }
        }

    @Test fun concurrentReportsCannotLoseThresholdOrInvalidateTwice() =
        runTest {
            val clock = TestClock()
            val invalidations =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val health =
                DefaultYouTubeHealth(
                    FakeSettingsRepository(),
                    clock,
                    backgroundScope,
                    appVersionCode = 42,
                    invalidateUrls = { invalidations.incrementAndGet() },
                ) {}
            health.awaitLoaded()
            val start = CompletableDeferred<Unit>()
            val reporters =
                List(20) { id ->
                    async(Dispatchers.Default) {
                        start.await()
                        health.reportExtraction(ExtractionOutcome.ParseFailure("v$id"))
                        health.extractionGate(clock.now())
                    }
                }
            start.complete(Unit)
            reporters.awaitAll()
            assertIs<ExtractionGate.Deny>(health.extractionGate(clock.now()))
            assertEquals(1, invalidations.get())
            health.awaitLoaded()
        }
}
