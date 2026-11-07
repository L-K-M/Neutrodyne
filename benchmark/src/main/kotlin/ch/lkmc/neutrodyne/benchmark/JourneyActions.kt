// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

internal const val PACKAGE = "ch.lkmc.neutrodyne"
private const val WAIT_MS = 30_000L
private const val GRID_FLINGS = 3
private const val PAGER_SWIPES = 10
private const val PLAYER_CYCLES = 5
private const val COLD_START_ITERATIONS = 15
private const val FRAME_ITERATIONS = 5

/** The destination a frame journey opens before its measured action. */
enum class Destination(
    val navTag: String,
) {
    FEEDS("nav_feeds"),
    LIBRARY("nav_library"),
}

/**
 * The UI-driving part of the performance journeys, shared by the Macrobenchmark classes and
 * [BaselineProfileGenerator] so a journey is defined once.
 */
object JourneyActions {
    /** Cold start to the first Feeds page; the setup block seeds and presses Home. */
    fun MacrobenchmarkScope.coldStart() {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.res("feeds_pager")), WAIT_MS)) { "Feeds did not load" }
    }

    /** Three flings over the Library cover grid. */
    fun MacrobenchmarkScope.flingCoverGrid() {
        val grid = checkNotNull(device.findObject(By.res("library_grid")))
        grid.setGestureMargin(device.displayWidth / 5)
        repeat(GRID_FLINGS) {
            grid.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    /** Flings through the All feed with day headers. */
    fun MacrobenchmarkScope.flingAllFeed() {
        val feed = checkNotNull(device.findObject(By.res("feed_list")))
        feed.setGestureMargin(device.displayWidth / 5)
        repeat(GRID_FLINGS) {
            feed.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    /** Ten pager swipes across groups. */
    fun MacrobenchmarkScope.swipeGroupPager() {
        val pager = checkNotNull(device.findObject(By.res("feeds_pager")))
        pager.setGestureMargin(device.displayWidth / 5)
        repeat(PAGER_SWIPES) {
            pager.swipe(if (it % 2 == 0) Direction.LEFT else Direction.RIGHT, .8f)
            device.waitForIdle()
        }
    }

    /** Five expand/collapse cycles with the seeded current episode. */
    fun MacrobenchmarkScope.expandCollapsePlayer() {
        repeat(PLAYER_CYCLES) {
            checkNotNull(device.wait(Until.findObject(By.res("mini_player")), WAIT_MS)).click()
            check(device.wait(Until.hasObject(By.res("full_player")), WAIT_MS)) { "Player did not expand" }
            device.pressBack()
            check(device.wait(Until.hasObject(By.res("mini_player")), WAIT_MS)) { "Player did not collapse" }
        }
    }

    /** Library tile to podcast screen content. */
    fun MacrobenchmarkScope.openPodcast() {
        val grid = checkNotNull(device.findObject(By.res("library_grid")))
        val tile = checkNotNull(grid.children.firstOrNull()) { "Library grid has no tiles" }
        tile.click()
        check(device.wait(Until.hasObject(By.res("podcast_list")), WAIT_MS)) { "Podcast did not open" }
    }

    /** Fills the database through the benchmark-only seed receiver. */
    fun MacrobenchmarkScope.seed() {
        val result =
            device.executeShellCommand(
                "am broadcast -a ch.lkmc.neutrodyne.BENCHMARK_SEED " +
                    "-n $PACKAGE/.benchmark.BenchmarkSeedReceiver",
            )
        check(result.contains("result=0")) { "Benchmark seed failed: $result" }
        check(result.contains("benchmark-seeded-v1")) { "Seed marker is missing: $result" }
    }

    /** Seeds, cold-starts and opens [destination], waiting for [contentTag]. */
    fun MacrobenchmarkScope.prepareJourney(
        destination: Destination,
        contentTag: String,
    ) {
        seed()
        killProcess()
        startActivityAndWait()
        checkNotNull(device.wait(Until.findObject(By.res(destination.navTag)), WAIT_MS)).click()
        check(device.wait(Until.hasObject(By.res(contentTag)), WAIT_MS)) { "Missing $contentTag" }
    }

    /** The gated compilation mode: the committed baseline profile, as ProfileInstaller applies it. */
    fun compilationMode(): CompilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)
}

/** Measures a frame journey after [JourneyActions.prepareJourney]. */
fun MacrobenchmarkRule.measureFrames(
    destination: Destination,
    contentTag: String,
    iterations: Int = FRAME_ITERATIONS,
    measure: MacrobenchmarkScope.() -> Unit,
) = measureRepeated(
    packageName = PACKAGE,
    metrics = listOf(FrameTimingMetric()),
    iterations = iterations,
    compilationMode = JourneyActions.compilationMode(),
    setupBlock = {
        with(JourneyActions) { prepareJourney(destination, contentTag) }
    },
    measureBlock = measure,
)

/** Measures a cold start after seeding and pressing Home. */
fun MacrobenchmarkRule.measureColdStart(
    iterations: Int = COLD_START_ITERATIONS,
    measure: MacrobenchmarkScope.() -> Unit,
) = measureRepeated(
    packageName = PACKAGE,
    metrics = listOf(StartupTimingMetric()),
    iterations = iterations,
    startupMode = StartupMode.COLD,
    compilationMode = JourneyActions.compilationMode(),
    setupBlock = {
        with(JourneyActions) { seed() }
        pressHome()
    },
    measureBlock = measure,
)
