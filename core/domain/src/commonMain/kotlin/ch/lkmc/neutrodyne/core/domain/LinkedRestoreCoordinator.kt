// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The sync-side cooperations of a restore on a linked device (05 Restore while linked, MS2).
 * `:core:data`'s restore pipeline owns the library writes; the sync layer owns everything
 * that touches the server, the token or the sync tables, so the runner reaches it through
 * this port instead of depending on `:sync:impl`. Implemented there; a default
 * [Unsupported] answer keeps unlinked builds and tests honest.
 */
interface LinkedRestoreCoordinator {
    /**
     * Replace with reach THIS_DEVICE: 10's unlink runs first — one best-effort push round,
     * logout, token deleted, sync tables cleared, `sync.server_url` kept (05). The local
     * Replace then runs on an unlinked device and pushes nothing.
     */
    suspend fun unlinkForRestore(): Outcome<Unit, BackupError>

    /**
     * Replace with reach ALL_DEVICES: 05's step 8 runs 10's "Use this device's library
     * everywhere" steps 2–5 — capture the replaced library with fresh clocks, push until
     * the outbox drains, prune the server by the staged snapshot cursor, write `linkedAt`.
     * Idempotent: a crash re-runs the restore and this step.
     */
    suspend fun completeAllDevicesRestore(): Outcome<Unit, BackupError>

    /**
     * The ALL_DEVICES second confirmation's effect (05): pull and stage the server library,
     * then count its podcasts and groups the backup lacks — what the other devices will
     * remove when they next sync. Runs before any local write.
     */
    suspend fun previewAllDevicesReach(
        backupPodcastKeys: Set<String>,
        backupGroupNames: Set<String>,
    ): Outcome<AllDevicesReachEffect, BackupError>
}

/** The counts the ALL_DEVICES confirmation names (05 Restore while linked). */
data class AllDevicesReachEffect(
    val podcastsToRemove: Int,
    val groupsToRemove: Int,
)

/** The fail-closed default: linked restores cannot run without the sync layer. */
object NoLinkedRestoreCoordinator : LinkedRestoreCoordinator {
    override suspend fun unlinkForRestore(): Outcome<Unit, BackupError> = Outcome.Failure(BackupError.Unsupported)

    override suspend fun completeAllDevicesRestore(): Outcome<Unit, BackupError> =
        Outcome.Failure(BackupError.Unsupported)

    override suspend fun previewAllDevicesReach(
        backupPodcastKeys: Set<String>,
        backupGroupNames: Set<String>,
    ): Outcome<AllDevicesReachEffect, BackupError> = Outcome.Failure(BackupError.Unsupported)
}

/**
 * Binds the fail-closed default until `:sync:impl`'s coordinator lands with the first-link
 * merger port (the next PR of this stack); that binding replaces this one.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object LinkedRestoreCoordinatorBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun linkedRestoreCoordinator(): LinkedRestoreCoordinator = NoLinkedRestoreCoordinator
}
