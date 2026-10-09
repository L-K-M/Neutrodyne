// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `OrderKey` per [10 Ordered lists](10-sync.md): midpoints, per-device jitter, the no-trailing-`0`
 * rule and rewrite output. The `BINARY` collation half of MS0 acceptance 4 is asserted by the
 * SQLite-side tests in `:core:database`; these are the pure-Kotlin properties.
 */
class OrderKeyTest {
    @Test
    fun betweenProducesAStrictlyOrderedKey() {
        val a = OrderKey.between(null, null)
        val b = OrderKey.between(null, a)
        val c = OrderKey.between(a, null)
        assertTrue(b < a && a < c, "expected $b < $a < $c")

        val mid = OrderKey.between(a, c)
        assertTrue(a < mid && mid < c, "expected $a < $mid < $c")
    }

    @Test
    fun beforeAndAfterWorkAtTheEnds() {
        val first = OrderKey.before(null)
        val afterFirst = OrderKey.after(first)
        val beforeFirst = OrderKey.before(first)
        assertTrue(beforeFirst < first && first < afterFirst)
    }

    @Test
    fun jitteredKeysDoNotEndInTheZeroDigit() {
        // The reference library rejects a fractional part ending in '0'; the appended jitter keeps
        // the last character non-zero (10 Ordered lists).
        repeat(200) {
            val key = OrderKey.between(null, null)
            assertTrue(key.last() != '0', "key ends in '0': $key")
            val mid = OrderKey.between("a0", "a2")
            assertTrue(mid.last() != '0' && mid in "a0".."a2", "bad key $mid")
        }
    }

    @Test
    fun jitterCoversTheWholeSuffixSpace() {
        // Jitter exists so concurrent inserts on different devices produce different keys. The
        // suffix space is small enough to walk exhaustively — 62 first digits × 61 non-zero last
        // digits = 3782 keys — instead of sampling it and flaking on the birthday bound.
        val digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val seen = HashSet<String>(3782)
        for (first in digits.indices) {
            for (last in 1 until digits.length) {
                // jitter() draws the non-zero last digit first, then the leading digit.
                val fed = intArrayOf(last, first)
                var index = 0
                val random =
                    object : Random() {
                        override fun nextBits(bitCount: Int): Int = error("jitter draws through nextInt(bound)")

                        override fun nextInt(until: Int): Int = fed[index++]
                    }
                // between("a0", "a1") computes the fixed midpoint "a0V"; the jitter appends.
                val key = OrderKey.between("a0", "a1", random)
                assertEquals("a0V${digits[first]}${digits[last]}", key)
                assertTrue(key in "a0".."a1")
                assertTrue(seen.add(key), "duplicate jittered key $key")
            }
        }
        assertEquals(3782, seen.size)

        // A zero last digit is rejected and re-drawn (a key may not end in '0').
        val fed = intArrayOf(0, 5, 10)
        var index = 0
        val random =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = error("jitter draws through nextInt(bound)")

                override fun nextInt(until: Int): Int = fed[index++]
            }
        assertEquals("a0V${digits[10]}${digits[5]}", OrderKey.between("a0", "a1", random))
        assertEquals(3, index, "the rejected '0' draw must consume an extra value")
    }

    @Test
    fun sameSpotInsertsKeepOrdering() {
        var prev = OrderKey.between(null, null)
        val next = OrderKey.after(prev)
        repeat(60) {
            val k = OrderKey.between(prev, next)
            assertTrue(prev < k && k < next, "$k not between $prev and $next")
            prev = k
        }
    }

    @Test
    fun rewriteReturnsDistinctOrderedKeys() {
        val keys = OrderKey.rewrite(10)
        assertEquals(10, keys.size)
        assertEquals(keys.sorted(), keys, "rewrite keys not ascending")
        assertEquals(keys.toSet().size, keys.size, "rewrite keys not distinct")
        assertEquals(emptyList(), OrderKey.rewrite(0))
        assertFailsWith<IllegalArgumentException> { OrderKey.rewrite(-1) }
    }

    @Test
    fun invalidBoundsAreRejected() {
        assertFailsWith<IllegalArgumentException> { OrderKey.between("!!", "a0") }
        assertFailsWith<IllegalArgumentException> { OrderKey.between("a0", "a0x0") }
    }

    @Test
    fun invalidIntegerDigitsAreRejectedAtEveryListBoundary() {
        for (key in listOf("a~", "a-", "aé", "b0~")) {
            assertFailsWith<IllegalArgumentException>(key) { OrderKey.before(key) }
            assertFailsWith<IllegalArgumentException>(key) { OrderKey.after(key) }
            assertFailsWith<IllegalArgumentException>(key) { OrderKey.between(key, "b11") }
        }
    }

    @Test
    fun invalidFractionDigitsAreRejectedAtEveryListBoundary() {
        for (key in listOf("a0~", "a0-", "a0é", "a0\u001F")) {
            assertFailsWith<IllegalArgumentException>(key) { OrderKey.before(key) }
            assertFailsWith<IllegalArgumentException>(key) { OrderKey.after(key) }
            assertFailsWith<IllegalArgumentException>(key) { OrderKey.between(key, "b11") }
        }
    }

    @Test
    fun prefixEdgeBoundsProduceKeysBetween() {
        // Regression for the unclamped substring in midpoint(): when the lower fraction is a
        // proper prefix of the upper one (padding `a` with '0' digits), the port used to throw
        // StringIndexOutOfBoundsException where the reference's slice() clamps.
        val edgePairs =
            listOf(
                "a0" to "a00V",
                "a0" to "a01",
                "a05" to "a050V",
                "a0x" to "a0x1",
            )
        for ((a, b) in edgePairs) {
            val key = OrderKey.between(a, b)
            assertTrue(a < key && key < b, "key $key is not strictly between $a and $b")
        }
    }

    @Test
    fun generatedNeighbourPairsProduceKeysBetween() {
        // Property-style check with a fixed seed: a seeded pool of valid keys (literal prefix-edge
        // forms plus rewrite() output plus random midpoint insertions) is probed at every adjacent
        // pair; every between() must yield a strictly ordered key.
        val random = Random(0xC0FFEE)
        val pool =
            sortedSetOf(
                "Y10",
                "Zz",
                "a0",
                "a00V",
                "a01",
                "a05",
                "a050V",
                "a0V",
                "a0Vz",
                "a0x",
                "a0x1",
                "a1",
                "b0x",
            )
        pool += OrderKey.rewrite(64)
        repeat(300) {
            val keys = pool.toList()
            val i = random.nextInt(keys.size - 1)
            pool += OrderKey.between(keys[i], keys[i + 1])
        }
        val keys = pool.toList()
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            val key = OrderKey.between(a, b)
            assertTrue(a < key && key < b, "generated key $key is not strictly between $a and $b")
        }
    }
}
