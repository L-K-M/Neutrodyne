// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.fetch.FeedHttpHeaders
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class FeedHttpHeadersTest {
    @Test
    fun `max-age matches the directive name, not a prefix`() {
        assertThat(FeedHttpHeaders.maxAgeSec("max-agency=120")).isNull()
        assertThat(FeedHttpHeaders.maxAgeSec("s-maxage=5")).isNull()
        assertThat(FeedHttpHeaders.maxAgeSec("max-age=120")).isEqualTo(120L)
        assertThat(FeedHttpHeaders.maxAgeSec("no-cache, MAX-AGE=30")).isEqualTo(30L)
        assertThat(FeedHttpHeaders.maxAgeSec("max-age")).isNull()
        assertThat(FeedHttpHeaders.maxAgeSec("max-age=abc")).isNull()
    }

    @Test
    fun `retry-after clamps negative and overflowing deltas`() {
        assertThat(FeedHttpHeaders.retryAfterMs("-5", 0L)).isEqualTo(0L)
        assertThat(FeedHttpHeaders.retryAfterMs("0", 0L)).isEqualTo(0L)
        assertThat(FeedHttpHeaders.retryAfterMs("90", 0L)).isEqualTo(90_000L)
        assertThat(FeedHttpHeaders.retryAfterMs("9999999999999999", 0L)).isEqualTo(Long.MAX_VALUE)
        assertThat(FeedHttpHeaders.retryAfterMs("9223372036854775807", 0L)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `basic challenge is found bare and inside a packed list`() {
        assertThat(FeedHttpHeaders.basicChallenge(listOf("Basic"))).isEqualTo(true to null)
        assertThat(FeedHttpHeaders.basicChallenge(listOf("Bearer realm=\"api\"")))
            .isEqualTo(false to null)
        assertThat(
            FeedHttpHeaders.basicChallenge(listOf("Digest realm=\"d\", Basic realm=\"packed\"")),
        ).isEqualTo(true to "packed")

        // RFC 9110 separates auth-params with commas: a space-separated list is malformed, so
        // the challenge still counts but its realm is not guessed.
        assertThat(FeedHttpHeaders.basicChallenge(listOf("Basic foo=bar realm=\"x\"")))
            .isEqualTo(true to null)
        assertThat(FeedHttpHeaders.basicChallenge(listOf("Basic dGVzdA=="))).isEqualTo(true to null)
    }

    @Test(timeout = FUZZ_TIMEOUT_MS)
    fun `challenge parser never throws or stalls on hostile input`() {
        // Seeded so a failure reproduces; the alphabet is dense in challenge-grammar bytes.
        val random = Random(FUZZ_SEED)
        repeat(FUZZ_CASES) {
            val header =
                String(
                    CharArray(random.nextInt(FUZZ_MAX_LENGTH)) { FUZZ_ALPHABET[random.nextInt(FUZZ_ALPHABET.length)] },
                )
            FeedHttpHeaders.basicChallenge(listOf(header))
        }
    }

    @Test
    fun `a basic realm survives thousands of auth params`() {
        // The recursive regex exhausted the JVM stack on these sizes (review probe).
        for (params in listOf(1_024, 4_096)) {
            val header =
                (0 until params).joinToString(",", prefix = "Basic ") { "p$it=x" } +
                    ",realm=\"probe\""
            assertThat(FeedHttpHeaders.basicChallenge(listOf(header))).isEqualTo(true to "probe")
        }
    }

    @Test
    fun `basic realm keeps commas and escaped quotes inside quotes`() {
        assertThat(FeedHttpHeaders.basicChallenge(listOf("""Basic realm="a,b\"c"""")))
            .isEqualTo(true to "a,b\"c")
    }

    private companion object {
        const val FUZZ_TIMEOUT_MS = 30_000L
        const val FUZZ_SEED = 42
        const val FUZZ_CASES = 20_000
        const val FUZZ_MAX_LENGTH = 160
        const val FUZZ_ALPHABET = ",= \t\"\\BasicDgtrlmRealmx0129=;"
    }
}
