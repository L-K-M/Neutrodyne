// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlin.uuid.Uuid

/** Device selection identity (08); group UUIDs remain stable across local database IDs. */
sealed interface FeedSourceRef {
    data object All : FeedSourceRef

    data object Ungrouped : FeedSourceRef

    data class Group(
        val uuid: String,
    ) : FeedSourceRef {
        init {
            require(Uuid.parseOrNull(uuid)?.toString() == uuid) { "Expected a canonical group UUID" }
        }
    }

    fun encode(): String = encode(this)

    companion object {
        fun encode(ref: FeedSourceRef): String =
            when (ref) {
                All -> "all"
                Ungrouped -> "ungrouped"
                is Group -> "group:${ref.uuid}"
            }

        /** Malformed storage falls back to All; resolving an unknown UUID belongs to the UI. */
        fun decode(value: String): FeedSourceRef =
            when {
                value == "ungrouped" -> {
                    Ungrouped
                }

                value.startsWith("group:") -> {
                    val uuid = value.removePrefix("group:")
                    if (Uuid.parseOrNull(uuid)?.toString() == uuid) Group(uuid) else All
                }

                else -> {
                    All
                }
            }
    }
}
