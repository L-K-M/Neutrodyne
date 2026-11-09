// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.benchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The remaining frame journeys (PB4, PB5, PB9) on `benchmarkRelease`. */
@RunWith(AndroidJUnit4::class)
class Journeys {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test
    fun allFeedFling() =
        benchmark.measureFrames(
            Destination.FEEDS,
            "feed_list",
            prepare = {
                with(JourneyActions) { selectAllFeed() }
            },
        ) {
            with(JourneyActions) { flingAllFeed() }
        }

    @Test
    fun groupPagerSwipe() =
        benchmark.measureFrames(Destination.FEEDS, "feeds_pager") {
            with(JourneyActions) { swipeGroupPager() }
        }

    @Test
    fun playerExpandCollapse() =
        benchmark.measureFrames(Destination.FEEDS, "mini_player") {
            with(JourneyActions) { expandCollapsePlayer() }
        }

    @Test
    fun podcastOpen() =
        benchmark.measureFrames(Destination.LIBRARY, "library_grid") {
            with(JourneyActions) { openPodcast() }
        }
}
