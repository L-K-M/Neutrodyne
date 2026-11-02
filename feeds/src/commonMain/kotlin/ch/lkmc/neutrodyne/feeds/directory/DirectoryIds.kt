// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.directory

/** Decimal provider IDs fit an unsigned 64-bit value; reject oversized untrusted lookup paths. */
object DirectoryIds {
    fun isValid(value: String): Boolean =
        value.length in 1..MAX_DIGITS &&
            value.all { it in '0'..'9' } && value.toULongOrNull() != null

    private const val MAX_DIGITS = 20
}
