// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

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
    fun twoInsertsAtTheSameSpotAlmostNeverCollide() {
        // Jitter exists so concurrent inserts on different devices produce different keys.
        val keys = (1..32).map { OrderKey.between("a0", "a1") }.toSet()
        assertEquals(32, keys.size, "jittered inserts at the same spot collided")
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
}
