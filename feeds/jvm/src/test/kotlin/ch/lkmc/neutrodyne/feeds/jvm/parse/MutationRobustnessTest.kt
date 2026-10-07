// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Seeded mutation robustness over every committed fixture of `FeedParser` (09 Untrusted-input
 * robustness): every mutant must return a `ParseResult` without throwing, finish inside the
 * per-mutant timeout and stay within the small heap this task runs with. PR runs use 20 mutations
 * per fixture (seed = fixture name hash); nightly runs raise `-PmutationIterations` to 1,000.
 */
class MutationRobustnessTest {
    /** Daemon pool: a mutant that never finishes is abandoned, never joined again. */
    private val workers = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    @Test
    fun everyFixtureSurvivesMutations() {
        val iterations = System.getProperty("neutrodyne.mutationIterations")?.toIntOrNull() ?: 20
        for (fixture in Goldens.fixturesDir
            .listFiles { f -> f.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }) {
            mutate(fixture, iterations)
        }
    }

    private fun mutate(
        fixture: File,
        iterations: Int,
    ) {
        val original = fixture.readBytes()
        val random = Random(fixture.name.hashCode().toLong())

        repeat(iterations) {
            for (mutant in mutantsOf(original, random)) {
                try {
                    parseWithTimeout(mutant)
                } catch (e: Throwable) {
                    throw AssertionError("mutation of ${fixture.name} failed", e)
                }
            }
        }
    }

    /** One mutant on a worker thread with its own parser; a non-terminating parse is abandoned. */
    private fun parseWithTimeout(mutant: ByteArray): ParseResult {
        val future =
            workers.submit<ParseResult> {
                XmlPullFeedParser
                    .discovered()
                    .parse({ Buffer().write(mutant) }, null, "https://example.com/feed.xml")
            }
        try {
            return future.get(MUTANT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw AssertionError("parse did not finish within $MUTANT_TIMEOUT_MS ms")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** The mutation set of 09: truncation, byte flips, slice duplication, DOCTYPE injection, encoding swaps. */
    private fun mutantsOf(
        original: ByteArray,
        random: Random,
    ): List<ByteArray> {
        val mutants = mutableListOf<ByteArray>()

        // Truncate at 10 evenly spread offsets.
        for (i in 1..10) {
            mutants += original.copyOf(original.size * i / 10)
        }

        // Flip 1–16 random bytes.
        val flips = 1 + random.nextInt(16)
        val flipped = original.copyOf()
        repeat(flips) {
            if (flipped.isNotEmpty()) {
                flipped[random.nextInt(flipped.size)] = random.nextInt(256).toByte()
            }
        }
        mutants += flipped

        // Duplicate a random 1 KiB slice.
        if (original.size > 1_024) {
            val start = random.nextInt(original.size - 1_024)
            val slice = original.copyOfRange(start, start + 1_024)
            val at = random.nextInt(original.size)
            mutants += original.copyOfRange(0, at) + slice + original.copyOfRange(at, original.size)
        }

        // Inject an internal-entity DOCTYPE after the XML declaration.
        val doctype = "<!DOCTYPE rss [<!ENTITY e \"0123456789\">]>".toByteArray()
        val injectionAt = minOf(original.size, 40)
        mutants += original.copyOfRange(0, injectionAt) + doctype + original.copyOfRange(injectionAt, original.size)

        // Replace the declared encoding.
        val text = original.decodeToString(0, minOf(original.size, 200))
        val swapped =
            text
                .replace(Regex("""encoding="[^"]*""""), "encoding=\"UTF-16\"")
                .replace(Regex("""encoding='[^']*'"""), "encoding='UTF-16'")
        if (swapped != text) {
            mutants += swapped.encodeToByteArray() + original.copyOfRange(minOf(original.size, 200), original.size)
        } else {
            mutants += original
        }

        // Insert NUL and a lone surrogate.
        val asText = original.decodeToString()
        val withNul = asText.replaceFirst("<", "<", ignoreCase = false)
        val withSurrogate = asText.replaceFirst("<", "<\uD800", ignoreCase = false)
        if (withNul != asText) mutants += withNul.encodeToByteArray()
        if (withSurrogate != asText) mutants += withSurrogate.encodeToByteArray()

        return mutants.distinctBy { it.contentHashCode() }
    }

    private companion object {
        const val MUTANT_TIMEOUT_MS = 5_000L
    }
}
