// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The notification-channel lifecycle port of 05 Notification channels: Android keeps one
 * `new_episodes_{uuid}` channel per group whose own `notifyNewEpisodes` is `true`; the desktop
 * has no channels and binds [NoGroupChannels]. Moved here from `:core:data` (2026-10-05) so
 * `:sync:impl` never depends on `:core:data`. Android binds `AndroidGroupChannelSync` in
 * `:app`; the desktop binds [NoGroupChannels].
 */
interface GroupChannelSync {
    /**
     * Reconcile channels with the group table (05's start-up and post-write sweep): create a
     * channel for every group whose own `notifyNewEpisodes` is `true` and delete every
     * `new_episodes_*` channel whose uuid belongs to neither an enabled group nor a live undo token —
     * implementations read [GroupRepository.pendingUndoUuids] for the spared set.
     */
    suspend fun sync()

    /**
     * A group's `uuid` changed (a Replace restore or a sync merge): carry the channel over —
     * create `new_episodes_{newUuid}` with the old channel's importance, sound, lights,
     * vibration and badge, then delete `new_episodes_{oldUuid}` (05 Platform constraints).
     */
    suspend fun onUuidChanged(
        oldUuid: String,
        newUuid: String,
    )

    /** A group's undo window ended without an undo: delete `new_episodes_{uuid}`. */
    suspend fun onDeleted(uuid: String)
}

/**
 * The desktop's channel-free implementation.
 */
object NoGroupChannels : GroupChannelSync {
    override suspend fun sync() = Unit

    override suspend fun onUuidChanged(
        oldUuid: String,
        newUuid: String,
    ) = Unit

    override suspend fun onDeleted(uuid: String) = Unit
}
