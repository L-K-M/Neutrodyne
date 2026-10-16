// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import kotlinx.coroutines.flow.Flow

/** M2 scoped fields only; writes preserve every other stored override. */
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
}
