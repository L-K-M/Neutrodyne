// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.protocol

import kotlin.random.Random

/**
 * Base-62 fractional index for ordered lists (`orderKey` columns of `podcast_group`,
 * `podcast_group_member` and `queue_entry`; 02 Conventions, [10 Ordered lists](10-sync.md)).
 * Port of rocicorp/fractional-indexing (CC0-1.0) with 10's per-device jitter: [between] appends two
 * random base-62 characters to the computed midpoint, so two devices inserting at the same spot
 * almost never produce equal keys.
 *
 * Keys sort correctly under SQLite's `BINARY` collation (digits `0-9A-Za-z` in ASCII order). A key
 * never ends in the zero digit and its integer head is a `BASE_52` length/magnitude marker.
 */
object OrderKey {
    private const val BASE_62_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    // Integer-part head alphabet when no explicit alphabet is given: A-Z heads mark negative
    // lengths, a-z positive lengths, so the default keys keep the classic "a0", "Zz" form.
    private const val BASE_52_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /**
     * A key strictly between [a] and [b] (either may be null for the list ends). The midpoint is
     * jittered; when the jittered key would not sort strictly between (the midpoint was a prefix
     * of [b]), the midpoint becomes the new lower bound instead (10 Ordered lists).
     */
    fun between(
        a: String?,
        b: String?,
    ): String = between(a, b, Random)

    // Test seam: the jitter source is injectable so tests can walk the whole suffix space
    // deterministically instead of sampling it.
    internal fun between(
        a: String?,
        b: String?,
        random: Random,
    ): String {
        val midpoint = generateKeyBetween(a, b)
        val candidate = midpoint + jitter(random)
        if (b == null || candidate < b) return candidate
        return between(midpoint, b, random)
    }

    /** A key before [first] (or the initial key when [first] is null). */
    fun before(first: String?): String = between(null, first)

    /** A key after [last] (or the initial key when [last] is null). */
    fun after(last: String?): String = between(last, null)

    /**
     * [n] evenly spaced fresh keys in ascending order (10 Ordered lists: a device that would write
     * a key longer than 64 characters rewrites the whole list with `rewrite(n)` in one
     * transaction).
     */
    fun rewrite(n: Int): List<String> = generateNKeysBetween(null, null, n)

    // Two random digits; the last is never the zero digit, so the jittered key stays a valid
    // fractional index (the library rejects a fractional part ending in 0).
    private fun jitter(random: Random): String {
        var last = BASE_62_DIGITS[0]
        while (last == BASE_62_DIGITS[0]) {
            last = BASE_62_DIGITS[random.nextInt(BASE_62_DIGITS.length)]
        }
        return BASE_62_DIGITS[random.nextInt(BASE_62_DIGITS.length)].toString() + last
    }

    // --- Port of rocicorp/fractional-indexing src/index.js (CC0-1.0) -------------------------
    // Only the default alphabets are supported: `digits` = BASE_62, `intDigits` = BASE_52.

    private fun generateKeyBetween(
        a: String?,
        b: String?,
    ): String {
        var a = a
        var b = b
        if (a != null) validateOrderKey(a)
        if (b != null) validateOrderKey(b)
        if (a != null && b != null && a > b) {
            val t = a
            a = b
            b = t
        }

        if (a == null) {
            if (b == null) {
                // The shortest positive head: the first character of intDigits' second half.
                return BASE_52_DIGITS[BASE_52_DIGITS.length / 2] + BASE_62_DIGITS[0].toString()
            }
            val ib = getIntegerPart(b)
            val fb = b.substring(ib.length)
            if (isSmallestInteger(ib)) return ib + midpoint("", fb)
            if (ib < b) return ib
            return decrementInteger(ib) ?: error("cannot decrement any more")
        }

        if (b == null) {
            val ia = getIntegerPart(a)
            val fa = a.substring(ia.length)
            return incrementInteger(ia) ?: ia + midpoint(fa, null)
        }

        val ia = getIntegerPart(a)
        val fa = a.substring(ia.length)
        val ib = getIntegerPart(b)
        val fb = b.substring(ib.length)
        if (ia == ib) return ia + midpoint(fa, fb)
        val i = incrementInteger(ia) ?: error("cannot increment any more")
        if (i < b) return i
        return ia + midpoint(fa, null)
    }

    private fun generateNKeysBetween(
        a: String?,
        b: String?,
        n: Int,
    ): List<String> {
        require(n >= 0) { "n must be >= 0: $n" }
        if (n == 0) return emptyList()
        if (n == 1) return listOf(generateKeyBetween(a, b))
        if (b == null) {
            var c = generateKeyBetween(a, null)
            val result = mutableListOf(c)
            repeat(n - 1) {
                c = generateKeyBetween(c, null)
                result += c
            }
            return result
        }
        if (a == null) {
            var c = generateKeyBetween(null, b)
            val result = mutableListOf(c)
            repeat(n - 1) {
                c = generateKeyBetween(a, c)
                result += c
            }
            return result.reversed()
        }
        val mid = n / 2
        val c = generateKeyBetween(a, b)
        return generateNKeysBetween(a, c, mid) + c + generateNKeysBetween(c, b, n - mid - 1)
    }

    // `a` may be empty; `b` is null or non-empty. `a < b` lexicographically when `b` is non-null.
    private fun midpoint(
        a: String,
        b: String?,
    ): String {
        val zero = BASE_62_DIGITS[0]
        if (b != null && a >= b) throw IllegalArgumentException("$a >= $b")
        if (a.endsWith(zero) || b?.endsWith(zero) == true) throw IllegalArgumentException("trailing zero")
        if (b != null) {
            // Remove the longest common prefix, padding `a` with zeros; `b` cannot end before `a`
            // while they share the prefix. `drop` clamps like the reference's `slice`: the padding
            // case leaves an empty remainder instead of throwing.
            var n = 0
            while ((a.getOrNull(n) ?: zero) == b.getOrNull(n)) {
                n++
            }
            if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.drop(n))
        }
        val digitA = if (a.isEmpty()) 0 else digitIndex(a[0])
        val digitB = if (b != null) digitIndex(b[0]) else BASE_62_DIGITS.length
        if (digitB - digitA > 1) {
            val midDigit = (digitA + digitB + 1) / 2
            return BASE_62_DIGITS[midDigit].toString()
        }
        if (b != null && b.length > 1) return b.substring(0, 1)
        return BASE_62_DIGITS[digitA] + midpoint(a.drop(1), null)
    }

    // Head characters mark integer-part lengths: the first half of intDigits are negative-length
    // heads, the second half positive; the two straddling the midpoint mark the shortest parts.
    private fun getIntegerLength(head: Char): Int {
        val i = BASE_52_DIGITS.indexOf(head)
        require(i >= 0) { "invalid order key head: $head" }
        val half = BASE_52_DIGITS.length / 2
        return if (i < half) half - i + 1 else i - half + 2
    }

    private fun getIntegerPart(key: String): String {
        require(key.isNotEmpty()) { "invalid order key: $key" }
        val len = getIntegerLength(key[0])
        require(len <= key.length) { "invalid order key: $key" }
        return key.substring(0, len)
    }

    private fun validateInteger(int: String) {
        require(int.isNotEmpty() && int.length == getIntegerLength(int[0])) {
            "invalid integer part of order key: $int"
        }
    }

    private fun validateOrderKey(key: String) {
        require(!isSmallestInteger(key)) { "invalid order key: $key" }
        val i = getIntegerPart(key)
        val f = key.substring(i.length)
        require(!f.endsWith(BASE_62_DIGITS[0])) { "invalid order key: $key" }
    }

    // The smallest integer is the most-negative head followed by all-zero digits.
    private fun isSmallestInteger(key: String): Boolean =
        key == BASE_52_DIGITS[0].toString() + BASE_62_DIGITS[0].toString().repeat(BASE_52_DIGITS.length / 2)

    // Walk the digit run right-to-left, zeroing maxed digits until one can be bumped; a carry out
    // of the whole run moves the head one step towards the largest marker (null = largest).
    private fun incrementInteger(x: String): String? {
        validateInteger(x)
        val head = x[0]
        val zero = BASE_62_DIGITS[0]
        var trailing = ""
        for (i in x.length - 1 downTo 1) {
            val d = digitIndex(x[i]) + 1
            if (d == BASE_62_DIGITS.length) {
                trailing = zero + trailing
            } else {
                return head + x.substring(1, i) + BASE_62_DIGITS[d] + trailing
            }
        }
        val headIndex = BASE_52_DIGITS.indexOf(head)
        if (headIndex == BASE_52_DIGITS.length - 1) return null
        val h = BASE_52_DIGITS[headIndex + 1]
        val lengthDelta = getIntegerLength(h) - getIntegerLength(head)
        return h +
            when {
                lengthDelta > 0 -> trailing + zero
                lengthDelta < 0 -> trailing.substring(1)
                else -> trailing
            }
    }

    // Mirror of incrementInteger: borrows out of the digit run, else moves the head down (null =
    // smallest).
    private fun decrementInteger(x: String): String? {
        validateInteger(x)
        val head = x[0]
        val last = BASE_62_DIGITS[BASE_62_DIGITS.length - 1]
        var trailing = ""
        for (i in x.length - 1 downTo 1) {
            val d = digitIndex(x[i]) - 1
            if (d == -1) {
                trailing = last + trailing
            } else {
                return head + x.substring(1, i) + BASE_62_DIGITS[d] + trailing
            }
        }
        val headIndex = BASE_52_DIGITS.indexOf(head)
        if (headIndex == 0) return null
        val h = BASE_52_DIGITS[headIndex - 1]
        val lengthDelta = getIntegerLength(h) - getIntegerLength(head)
        return h +
            when {
                lengthDelta > 0 -> trailing + last
                lengthDelta < 0 -> trailing.substring(1)
                else -> trailing
            }
    }

    // The reference's 256-entry lookup yields 0 for characters outside the alphabet.
    private fun digitIndex(c: Char): Int = BASE_62_DIGITS.indexOf(c).coerceAtLeast(0)
}
