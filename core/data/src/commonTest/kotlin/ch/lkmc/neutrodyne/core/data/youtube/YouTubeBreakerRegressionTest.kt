// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.ExtractionOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

class YouTubeBreakerRegressionTest {
    @Test fun reportThenGateCannotOvertakeBreakerThreshold() =
        runTest {
            val clock = TestClock()
            val health = DefaultYouTubeHealth(FakeSettingsRepository(), clock, backgroundScope) {}
            health.awaitLoaded()
            repeat(5) { health.reportExtraction(ExtractionOutcome.ParseFailure("video$it")) }
            assertIs<ExtractionGate.Deny>(health.extractionGate(clock.now()))
        }
}
