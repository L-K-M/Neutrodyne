// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Negative control for [ConformanceVectors]: the runner must fail when an expected
 * value is wrong, so a green vector suite cannot pass vacuously. The two runner
 * defects fixed in MS0 (rekey payload shape, per-hint provenance skew) were found
 * without a failing-first Kotlin run; these cases pin the runner's own comparison.
 */
class ConformanceRunnerTest {
    private val clock = "01a10c942d800000-0000000000000001"

    private fun vector(
        expectStatus: String = "applied",
        expectValue: Int = 100,
        expectMaxClock: String = clock,
        expectError: String? = null,
        serverTimeMs: Long? = null,
    ): String {
        val error = expectError?.let { "\"error\": \"$it\"," } ?: ""
        val serverTime = serverTimeMs?.let { "\"serverTimeMs\": $it," } ?: ""
        return """
            {
              "name": "negative-control",
              "collection": "podcast",
              "id": "11111111-1111-4111-8111-111111111111",
              $serverTime
              "changes": [
                {"fields": {"subscribedAt": {"v": 100, "c": "$clock"}}}
              ],
              "expect": {
                $error
                "status": ["$expectStatus"],
                "fields": {"subscribedAt": {"v": $expectValue, "c": "$clock"}},
                "maxClock": "$expectMaxClock"
              }
            }
            """.trimIndent()
    }

    @Test
    fun correctExpectationsPass() {
        val result = ConformanceVectors.run(vector())
        assertEquals(VectorDisposition.PASSED, result.disposition)
        assertEquals("negative-control", result.name)
    }

    @Test
    fun wrongExpectedStatusIsDetected() {
        assertFailsWith<IllegalStateException> { ConformanceVectors.run(vector(expectStatus = "stale")) }
    }

    @Test
    fun wrongExpectedFieldValueIsDetected() {
        assertFailsWith<IllegalStateException> { ConformanceVectors.run(vector(expectValue = 200)) }
    }

    @Test
    fun wrongExpectedMaxClockIsDetected() {
        assertFailsWith<IllegalStateException> {
            ConformanceVectors.run(vector(expectMaxClock = "01a10c942d800001-0000000000000001"))
        }
    }

    @Test
    fun expectedRejectionThatDoesNotHappenIsDetected() {
        assertFailsWith<IllegalStateException> { ConformanceVectors.run(vector(expectError = "invalid")) }
    }

    @Test
    fun wrongExpectedErrorCodeIsDetected() {
        // The change clock (2026) is far ahead of the 2020 server time: clock_skew.
        val skewed = vector(expectError = "clock_skew", serverTimeMs = ProtocolLimits.MIN_CLOCK_MS)
        assertEquals(VectorDisposition.PASSED, ConformanceVectors.run(skewed).disposition)
        assertFailsWith<ProtocolException> {
            ConformanceVectors.run(vector(expectError = "invalid", serverTimeMs = ProtocolLimits.MIN_CLOCK_MS))
        }
    }
}
