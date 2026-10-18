// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.jvm.parse.MutationRobustnessTest
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import okio.Buffer
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Seeded mutation robustness over the OPML corpus, same protocol as `MutationRobustnessTest` (09
 * Untrusted-input robustness): every mutant must return an [OpmlReadResult] without throwing,
 * finish inside the per-mutant timeout and stay within the 128 MB heap this task runs with. Shares
 * the feed test's mutation set so the OPML reader sees the same corruption classes.
 */
class OpmlMutationRobustnessTest {
    /** Daemon pool: a mutant that never finishes is abandoned, never joined again. */
    private val workers = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    private val mutator = MutationRobustnessTest()

    @Test
    fun everyFixtureSurvivesMutations() {
        val iterations = System.getProperty("neutrodyne.mutationIterations")?.toIntOrNull() ?: 20
        for (fixture in Goldens.fixtures(AREA, EXTENSION)) {
            mutate(fixture, iterations)
        }
    }

    private fun mutate(
        fixture: File,
        iterations: Int,
    ) {
        val original = fixture.readBytes()
        val random = Random(fixture.name.hashCode().toLong())
        repeat(iterations) { iteration ->
            for (mutant in mutator.mutantsOf(original, random)) {
                try {
                    readWithTimeout(mutant)
                } catch (e: Throwable) {
                    throw AssertionError("mutation of ${fixture.name} failed at iteration $iteration", e)
                }
            }
        }
    }

    private fun readWithTimeout(mutant: ByteArray): OpmlReadResult {
        val future =
            workers.submit<OpmlReadResult> {
                XmlPullOpmlReader.discovered().read({ Buffer().write(mutant) })
            }
        try {
            return future.get(MUTANT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw AssertionError("read did not finish within $MUTANT_TIMEOUT_MS ms")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private companion object {
        const val AREA = "opml"
        const val EXTENSION = ".opml"
        const val MUTANT_TIMEOUT_MS = 5_000L
    }
}
