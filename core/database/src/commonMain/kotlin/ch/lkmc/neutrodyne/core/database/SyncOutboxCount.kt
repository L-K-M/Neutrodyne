// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

/** A projection of collection names and row counts, never outbox payloads or IDs. */
data class SyncOutboxCount(
    val coll: String,
    val count: Int,
)
