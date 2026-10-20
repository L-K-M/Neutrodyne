// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.PlaybackScopeWrite
import ch.lkmc.neutrodyne.core.model.PlaybackSettingChange
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingScope
import kotlinx.coroutines.flow.Flow

/** Scoped M2/M4 fields; writes preserve every other stored override. */
interface ScopeSettingsRepository {
    /** Null once the scope is gone. Membership, group, override and global edits re-emit. */
    fun observePodcast(podcastId: Long): Flow<ScopedSettingsView?>

    fun observeGroup(groupId: Long): Flow<ScopedSettingsView?>

    /** Change runs under the writer lock. Missing scopes are successful no-ops. */
    suspend fun updatePodcast(
        podcastId: Long,
        change: (SettingOverrides) -> SettingOverrides,
    ): Outcome<Unit, SettingsError>

    suspend fun updateGroup(
        groupId: Long,
        change: (SettingOverrides) -> SettingOverrides,
    ): Outcome<Unit, SettingsError>

    /**
     * Player write with membership checked under the writer lock. Clears only the edited field
     * at more-specific scopes on this podcast/context chain. SQL failures roll back all scoped
     * edits; a failed global store write also rolls back those edits. The stores have no shared
     * commit: if SQLite's final commit fails after the global write, the global may be stored,
     * but scoped values survive and the operation reports failure. Cancellation is commit-safe.
     * Future sync capture must join this writer transaction, never await a separate writer job.
     */
    suspend fun writePlayback(
        podcastId: Long,
        contextGroupId: Long?,
        scope: SettingScope,
        change: PlaybackSettingChange,
    ): Outcome<PlaybackScopeWrite, SettingsError>
}
