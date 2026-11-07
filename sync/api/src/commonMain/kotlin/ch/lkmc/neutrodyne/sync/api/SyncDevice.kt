// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/** One device of the account, as Settings › Sync › Devices shows it (10 Authentication). */
data class SyncDevice(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String?,
    val lastSeenAtMs: Long?,
    val current: Boolean,
    val stale: Boolean,
)
