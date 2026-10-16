// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The `OrderKey` fractional index behind a port (10 Ordered lists): `:core:data` cannot see
 * `:sync:protocol`, so the shells bind these members to `OrderKey`. Used by the group lifecycle and
 * subscribe transactions for group and membership keys (`orderKey = OrderKey.after(last key)`) and
 * by [GroupRepository.reorder]'s minimal-write moves (`between` at the ends is `after`/`before`).
 */
interface OrderKeys {
    /** `OrderKey.after(last)` — the next group or member key, or the first key for null. */
    fun after(last: String?): String

    /**
     * `OrderKey.between(previous, next)` — a key strictly between the bounds, either of which may be
     * null at a list end. Callers must supply ordered distinct bounds: equal keys cannot be split
     * and signal a rewrite instead ([planOrderKeyWrites]).
     */
    fun between(
        previous: String?,
        next: String?,
    ): String

    /** `OrderKey.rewrite(count)` — [count] evenly spaced fresh keys in ascending order. */
    fun rewrite(count: Int): List<String>

    companion object {
        /** A key a device would write beyond this length makes it rewrite the whole list (10). */
        const val MAX_LENGTH = 64
    }
}
