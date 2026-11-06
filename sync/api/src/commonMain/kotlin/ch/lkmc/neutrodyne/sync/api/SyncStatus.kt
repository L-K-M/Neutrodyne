// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/**
 * The link state the Settings › Sync body follows (10 Error handling and backoff's state diagram,
 * 08 Settings › Sync). Pure data: `:sync:impl` derives it from `sync_state` and the secret store.
 */
sealed interface SyncStatus {
    /** No server configured; sync is inert (R7.1). */
    data object NotConfigured : SyncStatus

    /** A restored install or copied data directory kept `sync.server_url`; nothing is pushed. */
    data class Reconnect(
        val serverUrl: String,
        val username: String?,
    ) : SyncStatus

    /** A token is stored but the first-link step never finished; Settings › Sync resumes it. */
    data class LinkUnfinished(
        val serverUrl: String,
    ) : SyncStatus

    /**
     * An all-synced-devices Replace restore is pending (05 Restore while linked): the restore
     * session, not the first-link choices, is the resume point, and Settings › Sync says so
     * instead of offering the choices (08).
     */
    data class FinishingRestore(
        val serverUrl: String,
    ) : SyncStatus

    /**
     * Linked; [problem] replaces the first status line while set. [activity] is what the status
     * card shows next to "Up to date" (08 Settings › Sync).
     */
    data class Linked(
        val serverUrl: String,
        val accountId: String,
        val deviceId: String,
        val username: String?,
        val nodeId: String,
        val lastSyncAtMs: Long?,
        val activity: SyncActivity,
        val problem: SyncProblem?,
    ) : SyncStatus

    /** The token was revoked; "Link again" starts a new link. */
    data class Revoked(
        val serverUrl: String,
    ) : SyncStatus
}

/** What the sync machinery is doing right now (10 Live updates, 08 Settings › Sync). */
enum class SyncActivity {
    /** Linked and quiet. */
    IDLE,

    /** A sync round is in flight. */
    SYNCING,

    /** The server-sent events stream is connected. */
    LIVE,
}
