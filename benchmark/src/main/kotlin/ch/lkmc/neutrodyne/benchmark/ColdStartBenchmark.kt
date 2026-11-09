// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.benchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PB1: cold start to Feeds on `benchmarkRelease`. Emulator timings are diagnostic only. */
@RunWith(AndroidJUnit4::class)
class ColdStartBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test
    fun coldStartToFeeds() = benchmark.measureColdStart { with(JourneyActions) { coldStart() } }
}
