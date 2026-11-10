// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.database

/** Lightweight replay pagination; raw clock payloads are loaded one record at a time. */
data class SyncClockKey(
    val coll: String,
    val rid: String,
)
