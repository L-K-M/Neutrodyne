// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The `OrderKey` fractional index behind a port (10 Ordered lists): `:core:data` cannot see
 * `:sync:protocol`, so the shells bind [OrderKeys.after] to `OrderKey.after`. Used by the
 * group creation and subscribe transactions for group and membership keys (`orderKey = OrderKey.after(last key)`).
 */
fun interface OrderKeys {
    /** `OrderKey.after(last)` — the next group or member key, or the first key for null. */
    fun after(last: String?): String
}
