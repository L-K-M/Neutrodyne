// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.benchmark

import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

private const val PACKAGE = "ch.lkmc.neutrodyne"
private const val WAIT_MS = 30_000L

/** Timings on emulators are diagnostic only. PB3 is evaluated on the reference phone. */
@RunWith(AndroidJUnit4::class)
class Journeys {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartToFeeds() =
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            compilationMode = compilation(),
            setupBlock = {
                seed()
                pressHome()
            },
        ) {
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.res("feeds_pager")), WAIT_MS)) { "Feeds did not load" }
        }

    @Test fun coverGridFling() =
        frames("library_grid", "nav_library") {
            val grid = checkNotNull(device.findObject(By.res("library_grid")))
            grid.setGestureMargin(device.displayWidth / 5)
            repeat(3) {
                grid.fling(Direction.DOWN)
                device.waitForIdle()
            }
        }

    @Test fun allFeedFling() =
        frames("feed_list", "nav_feeds") {
            val feed = checkNotNull(device.findObject(By.res("feed_list")))
            feed.setGestureMargin(device.displayWidth / 5)
            repeat(3) {
                feed.fling(Direction.DOWN)
                device.waitForIdle()
            }
        }

    @Test fun groupPagerSwipe() =
        frames("feeds_pager", "nav_feeds") {
            val pager = checkNotNull(device.findObject(By.res("feeds_pager")))
            pager.setGestureMargin(device.displayWidth / 5)
            repeat(10) {
                pager.swipe(if (it % 2 == 0) Direction.LEFT else Direction.RIGHT, .8f)
                device.waitForIdle()
            }
        }

    @Test fun playerExpandCollapse() =
        frames("mini_player", "nav_feeds") {
            repeat(5) {
                checkNotNull(device.wait(Until.findObject(By.res("mini_player")), WAIT_MS)).click()
                check(device.wait(Until.hasObject(By.res("full_player")), WAIT_MS)) { "Player did not expand" }
                device.pressBack()
                check(device.wait(Until.hasObject(By.res("mini_player")), WAIT_MS)) { "Player did not collapse" }
            }
        }

    private fun frames(
        tag: String,
        navTag: String,
        measure: MacrobenchmarkScope.() -> Unit,
    ) = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        compilationMode = compilation(),
        setupBlock = {
            seed()
            killProcess()
            startActivityAndWait()
            try {
                checkNotNull(device.wait(Until.findObject(By.res(navTag)), WAIT_MS)).click()
                check(device.wait(Until.hasObject(By.res(tag)), WAIT_MS)) { "Missing $tag" }
            } catch (error: Exception) {
                // Macrobenchmark kills the target after a setup failure. Capture
                // the visible state first, in the always-uploaded per-test logcat.
                runCatching {
                    val hierarchy =
                        ByteArrayOutputStream().use { output ->
                            device.dumpWindowHierarchy(output)
                            output.toString(Charsets.UTF_8.name()).take(200_000)
                        }
                    hierarchy.chunked(3000).forEachIndexed { index, chunk ->
                        Log.e("M10Journey", "$tag hierarchy[$index]: $chunk")
                    }
                }.onFailure { Log.e("M10Journey", "Could not capture $tag hierarchy", it) }
                throw error
            }
        },
        measureBlock = measure,
    )

    private fun MacrobenchmarkScope.seed() {
        val result =
            device.executeShellCommand(
                "am broadcast -a ch.lkmc.neutrodyne.BENCHMARK_SEED " +
                    "-n $PACKAGE/.benchmark.BenchmarkSeedReceiver",
            )
        check(result.contains("result=0")) { "Benchmark seed failed: $result" }
        check(result.contains("benchmark-seeded-v1")) { "Seed marker is missing: $result" }
    }

    private fun compilation() =
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 3)
}
