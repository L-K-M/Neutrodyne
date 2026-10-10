// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The `OrderKey` fractional index behind a port (10 Ordered lists): `:core:data` cannot see
 * `:sync:protocol`, so the shells bind [OrderKeys.after] to `OrderKey.after`. Used by the
 * subscribe transaction for group membership keys (`orderKey = OrderKey.after(last key)`).
 */
fun interface OrderKeys {
    /** `OrderKey.after(last)` — the next member key of a group, or the first key for null. */
    fun after(last: String?): String
}
