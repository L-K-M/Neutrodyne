// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/** One-shot notices the sync engine posts to the shell's snackbar flow (08 Sync notices). */
sealed interface SyncNotice {
    /** A full resync re-asserted [added] local changes with their original clocks. */
    data class Resynced(
        val added: Int,
    ) : SyncNotice

    /** A received credential was installed for a feed this device could not refresh. */
    data class FeedPasswordNeeded(
        val podcastIds: List<Long>,
    ) : SyncNotice

    /** The server reported a password set for the account on [date] (ISO-8601). */
    data class PasswordSet(
        val date: String,
    ) : SyncNotice

    /** A remote device marked the loaded episode played; playback here is untouched (MS3). */
    data class MarkedPlayedElsewhere(
        val episodeId: Long,
        val title: String,
        val deviceName: String,
    ) : SyncNotice
}
