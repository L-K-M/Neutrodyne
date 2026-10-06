// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The one registry of every [NavKey]. Non-JVM targets cannot serialize keys by reflection, so every
 * back stack is created with [savedStateConfiguration] and every key is registered here explicitly.
 */
public object NavKeySerializers {
    public val module: SerializersModule =
        SerializersModule {
            polymorphic(NavKey::class) {
                subclass(FeedsKey::class)
                subclass(LibraryKey::class)
                subclass(UpNextKey::class)
                subclass(DownloadsKey::class)
                subclass(DiscoverKey::class)
                subclass(PodcastKey::class)
                subclass(PodcastPreviewKey::class)
                subclass(PodcastSettingsKey::class)
                subclass(EpisodeKey::class)
                subclass(GroupEditKey::class)
                subclass(GroupsManageKey::class)
                subclass(GroupSettingsKey::class)
                subclass(AddToGroupsKey::class)
                subclass(AllGroupsKey::class)
                subclass(DirectoryKey::class)
                subclass(AddPodcastKey::class)
                subclass(ImportKey::class)
                subclass(BackupKey::class)
                subclass(ExportKey::class)
                subclass(SettingsHomeKey::class)
                subclass(SettingsKey::class)
                subclass(LicencesKey::class)
                subclass(InstallHelpKey::class)
                subclass(VerificationNoticeKey::class)
                subclass(KeyboardShortcutsKey::class)
                subclass(SyncSettingsKey::class)
                subclass(SyncSetupKey::class)
                subclass(SyncApproveKey::class)
                subclass(SyncDevicesKey::class)
                subclass(SyncHeldChangesKey::class)
                subclass(SyncDiagnosticsKey::class)
                subclass(DiagnosticsKey::class)
                subclass(SpeedKey::class)
                subclass(SleepTimerKey::class)
            }
        }

    /** The configuration every per-tab back stack passes to `rememberNavBackStack`. */
    public val savedStateConfiguration: SavedStateConfiguration =
        SavedStateConfiguration { serializersModule = module }
}
