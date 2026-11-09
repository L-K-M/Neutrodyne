// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.benchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PB3: cover-grid fling jank on `benchmarkRelease`. Emulator timings are diagnostic only. */
@RunWith(AndroidJUnit4::class)
class CoverGridBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test
    fun coverGridFling() =
        benchmark.measureFrames(Destination.LIBRARY, "library_grid") {
            with(JourneyActions) { flingCoverGrid() }
        }
}
