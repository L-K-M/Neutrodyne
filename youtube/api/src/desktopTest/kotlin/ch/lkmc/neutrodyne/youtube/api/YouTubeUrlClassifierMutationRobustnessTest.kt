// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeded mutation robustness over [YouTubeUrlClassifier] (09 Untrusted-input robustness): a
 * corpus of 200 seeded random strings plus the canonical grammar forms is corrupted into URI,
 * upload-playlist, playlist, handle, percent-escape, astral, invalid-Unicode, control,
 * truncation and nesting mutants. Every mutant must classify into a declared [YtRef] shape or
 * null — never `Query`, never throw — finish inside the per-mutant timeout and stay within the
 * 128 MB heap this task runs with. PR runs use 20 mutation rounds per corpus entry (seed =
 * [CORPUS_SEED] + corpus index, printed in every failure); nightly runs raise
 * `-PmutationIterations` to 1,000.
 */
class YouTubeUrlClassifierMutationRobustnessTest {
    /** Daemon pool: a mutant that never finishes is abandoned, never joined again. */
    private val workers = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    private val classifier = YouTubeUrlClassifier()

    @Test
    fun everyStringSurvivesMutations() {
        val iterations = System.getProperty("neutrodyne.mutationIterations")?.toIntOrNull() ?: 20
        try {
            require(iterations > 0) { "mutationIterations must be positive" }
            corpus().forEachIndexed { index, form -> mutate(form, index, iterations) }
        } finally {
            workers.shutdownNow()
        }
    }

    private fun corpus(): List<String> = FORMS + seededStrings()

    private fun mutate(
        form: String,
        index: Int,
        iterations: Int,
    ) {
        val seed = CORPUS_SEED + index.toLong()
        val random = Random(seed)
        repeat(iterations) { round ->
            mutantsOf(form, random).forEachIndexed { mutantIndex, mutant ->
                try {
                    classifyWithTimeout(mutant)
                } catch (e: Throwable) {
                    throw AssertionError(
                        "mutation of corpus[$index] (seed $seed, round $round, mutant $mutantIndex)" +
                            " failed for input ${mutant.printable()}",
                        e,
                    )
                }
            }
        }
    }

    /** One mutant on a worker thread: all three entry points, checked shape, timed. */
    private fun classifyWithTimeout(mutant: String) {
        val future =
            workers.submit<Unit> {
                val ref = classifier.classify(mutant)
                assertDeclared(ref, mutant)
                // The contract of 04: classifyOrQuery is classify with a Query fallback, so a
                // non-null classify result must be returned verbatim, a null must become Query.
                assertEquals(ref ?: YtRef.Query(mutant.trim()), classifier.classifyOrQuery(mutant))
                classifier.isYouTubeHost(mutant)
            }
        try {
            future.get(MUTANT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw AssertionError("classify did not finish within $MUTANT_TIMEOUT_MS ms")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * The declared outcomes of `classify`/`classifyOrQuery`: `null` or a [YtRef] whose payload is
     * internally consistent. `Query` never answers `classify` (04: only `classifyOrQuery`
     * produces it), so a `Query` here is a structural violation.
     */
    private fun assertDeclared(
        ref: YtRef?,
        mutant: String,
    ) {
        when (ref) {
            null -> {
                return
            }

            is YtRef.Channel -> {
                assertTrue(YouTubeIds.CHANNEL.matches(ref.id), "channel id ${ref.id} of ${mutant.printable()}")
                assertTrue(ref.variantsHint == null || ref.variantsHint >= 0)
            }

            is YtRef.Video -> {
                assertTrue(YouTubeIds.VIDEO.matches(ref.id), "video id ${ref.id} of ${mutant.printable()}")
            }

            is YtRef.Handle -> {
                assertTrue(ref.handle.startsWith('@'), "handle ${ref.handle} of ${mutant.printable()}")
                assertTrue(ref.handle.length <= 101)
                assertTrue(ref.handle.none { it.isWhitespace() })
            }

            is YtRef.LegacyPath -> {
                assertTrue(ref.path.isNotEmpty() && ref.path.length <= 102, "path ${ref.path} of ${mutant.printable()}")
            }

            is YtRef.Playlist -> {
                assertTrue(ref.id.isNotEmpty(), "playlist of ${mutant.printable()}")
            }

            is YtRef.Query -> {
                throw AssertionError("Query must never answer classify: ${mutant.printable()}")
            }
        }
    }

    /**
     * The corpus's seeded half: exactly 200 random strings over a hostile pool — URL separators,
     * percent signs, controls, lone surrogate halves and astral pairs — so 'URI-ish', percent,
     * Unicode and truncation mutations already start from attacker-shaped input. The stream is a
     * function of [STRINGS_SEED] only, the same on every machine.
     */
    private fun seededStrings(): List<String> {
        val random = Random(STRINGS_SEED)
        return List(SEEDED_STRING_COUNT) {
            buildString {
                repeat(random.nextInt(MAX_SEEDED_LENGTH + 1)) {
                    append(POOL[random.nextInt(POOL.length)])
                }
            }
        }
    }

    /**
     * The mutation classes of 09 for a string grammar: truncation, flips over the hostile pool,
     * slice duplication, percent-escape splicing (valid, truncated and hostile payloads),
     * invalid-Unicode/control insertion and nesting into wrappers and YouTube URL contexts.
     */
    private fun mutantsOf(
        original: String,
        random: Random,
    ): List<String> {
        val mutants = mutableListOf<String>()

        // Truncate at 10 evenly spread offsets.
        for (i in 1..10) {
            mutants += original.substring(0, original.length * i / 10)
        }

        // Flip 1–16 random chars.
        val flipped = original.toCharArray()
        repeat(1 + random.nextInt(16)) {
            if (flipped.isNotEmpty()) flipped[random.nextInt(flipped.size)] = POOL[random.nextInt(POOL.length)]
        }
        mutants += String(flipped)

        // Duplicate a random slice.
        if (original.length > 4) {
            val width = 1 + random.nextInt(minOf(64, original.length))
            val start = random.nextInt(original.length - width + 1)
            val at = random.nextInt(original.length + 1)
            mutants += original.substring(0, at) + original.substring(start, start + width) + original.substring(at)
        }

        // Splice percent escapes: hex-valid, truncated and non-UTF-8 payloads.
        for (escape in PERCENT_ESCAPES) {
            val at = random.nextInt(original.length + 1)
            mutants += original.substring(0, at) + escape + original.substring(at)
        }

        // Insert controls and invalid Unicode: NUL, DEL, a lone surrogate half, an astral char.
        for (insert in HOSTILE_INSERTS) {
            val at = random.nextInt(original.length + 1)
            mutants += original.substring(0, at) + insert + original.substring(at)
        }

        // Nest: wrappers stack, YouTube URL contexts embed, hostile neighbours frame.
        for (nest in NESTS) {
            mutants += nest.replace(NEST_SLOT, original)
        }

        return mutants.distinct()
    }

    /** The corpus's canonical half — one form per grammar class of 04's input grammar. */
    private companion object {
        const val CORPUS_SEED = 0x59C4L
        const val STRINGS_SEED = 0x59C4_200L
        const val SEEDED_STRING_COUNT = 200
        const val MAX_SEEDED_LENGTH = 96
        const val MUTANT_TIMEOUT_MS = 2_000L
        const val NEST_SLOT = "\u0000"

        val FORMS =
            listOf(
                // URI forms across the allow-listed hosts.
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m3s",
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
                "https://www.youtube.com/shorts/dQw4w9WgXcQ",
                "https://www.youtube.com/live/dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ?si=Ab1Cd2",
                "youtube.com/watch?v=dQw4w9WgXcQ",
                // Channel and feed URIs.
                "https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/feeds/videos.xml?playlist_id=UUBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/feeds/videos.xml?user=MarquesBrownlee",
                "UCBJycsmduvYEL83R_U4JriQ",
                // Upload playlist and plain playlist forms.
                "https://www.youtube.com/playlist?list=UUBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/playlist?list=UULFBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/playlist?list=UUSHBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/playlist?list=UULVBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/playlist?list=UUMOBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/playlist?list=UUPSBJycsmduvYEL83R_U4JriQ",
                "https://www.youtube.com/playlist?list=PL6566A39B68523E18",
                // Handles, legacy and bare custom forms.
                "https://www.youtube.com/@mkbhd",
                "@mkbhd",
                "https://www.youtube.com/c/mkbhd",
                "https://www.youtube.com/user/MarquesBrownlee",
                "https://www.youtube.com/mkbhd",
                // Percent escapes, astral and invalid-Unicode raw forms.
                "https://www.youtube.com/@%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82",
                "https://www.youtube.com/@%40nasa",
                "@\uD840\uDC0Babc",
                "https://www.youtube.com/@\uD840\uDC0Ba%62",
                "https://www.youtube.com/c/\uD840\uDC0Ba%62",
                "https://www.youtube.com/@ab\uD800",
                "https://www.youtube.com/@\u0002cd",
                // Nesting: stacked wrappers.
                "feed:view-source:https://www.youtube.com/@mkbhd",
                "view-source:https://youtu.be/dQw4w9WgXcQ",
            )

        /**
         * The hostile char pool: the URL alphabet and separators, whitespace, the percent sign,
         * NUL and other controls, a lone surrogate half (invalid Unicode) and an astral pair.
         */
        val POOL =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
                "%:/?#&=._-@+~,; \t\r\n\u0000\u0007\u001B\u0000" +
                "\uD800\uDC00\uD840\uDC0B\uFFFD"

        val PERCENT_ESCAPES = listOf("%2F", "%40", "%25", "%00", "%FF", "%E0%80", "%2", "%%", "%zz")

        val HOSTILE_INSERTS = listOf("\u0000", "\u007F", "\u001B", "\uD800", "\uDFFF", "\uD840\uDC0B", "\u0301")

        val NESTS =
            listOf(
                "feed:$NEST_SLOT",
                "view-source:$NEST_SLOT",
                "feed:view-source:$NEST_SLOT",
                "https://www.youtube.com/$NEST_SLOT",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=$NEST_SLOT",
                "https://www.youtube.com/playlist?list=$NEST_SLOT",
                "https://www.youtube.com/feeds/videos.xml?channel_id=$NEST_SLOT",
                "https://evil.example/?next=$NEST_SLOT",
                "$NEST_SLOT://www.youtube.com/watch?v=dQw4w9WgXcQ",
            )
    }
}

/** A mutant string with control and non-ASCII characters escaped, bounded for the failure message. */
private fun String.printable(): String =
    buildString {
        for (c in this@printable.take(160)) {
            append(if (c.code in 0x20..0x7e) c.toString() else "\\u%04x".format(c.code))
        }
        if (this@printable.length > 160) append("…(${this@printable.length} chars)")
    }
