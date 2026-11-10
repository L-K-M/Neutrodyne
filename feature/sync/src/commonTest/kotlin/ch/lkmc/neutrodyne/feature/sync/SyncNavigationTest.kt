// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SyncApproveKey
import ch.lkmc.neutrodyne.core.navigation.SyncDevicesKey
import ch.lkmc.neutrodyne.core.navigation.SyncHeldChangesKey
import ch.lkmc.neutrodyne.core.navigation.SyncSettingsKey
import ch.lkmc.neutrodyne.core.navigation.SyncSetupKey
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** The overlay metadata of `:feature:sync`'s nav entries (08 Sync screens, 01 overlay scenes). */
class SyncNavigationTest {
    @Test
    fun theHeldChangesPromptIsRegisteredAsADialog() {
        val provider = entryProvider<NavKey> { entries().invoke(this) }
        assertEquals(NdSceneMetadata.dialog(), provider(SyncHeldChangesKey(3)).metadata)
    }

    @Test
    fun theApproveSheetIsRegisteredAsABottomSheet() {
        val provider = entryProvider<NavKey> { entries().invoke(this) }
        assertEquals(NdSceneMetadata.bottomSheet(), provider(SyncApproveKey()).metadata)
    }

    @Test
    fun theScreensStayDetailPanes() {
        val provider = entryProvider<NavKey> { entries().invoke(this) }
        assertEquals(NdSceneMetadata.paneDetail(), provider(SyncSettingsKey).metadata)
        assertEquals(NdSceneMetadata.paneDetail(), provider(SyncSetupKey("https://sync.example.org")).metadata)
        assertEquals(NdSceneMetadata.paneDetail(), provider(SyncDevicesKey).metadata)
    }

    private fun entries(): EntryProviderInstaller =
        SyncNavigation.entries(
            syncController = { FakeSyncController() },
            backups = { FakeBackupRepository() },
            address = { FakeSyncServerAddress() },
            buildInfo = BUILD_INFO,
        )

    private companion object {
        val BUILD_INFO =
            BuildInfo(
                versionName = "1.2.3",
                versionCode = 42,
                debug = true,
                platform = BuildInfo.Platform.DESKTOP,
                repoUrl = "https://github.com/lkmc/neutrodyne",
                updateManifestUrl = "https://example.invalid/updates.json",
                engineManifestUrl = "https://example.invalid/engine.json",
                youTubeEngineBundled = false,
                desktop =
                    BuildInfo.Desktop(
                        os = DesktopOs.MACOS,
                        arch = DesktopArch.ARM64,
                        installKind = InstallKind.DMG,
                        runtime = "bundled",
                    ),
                shippedLocales = persistentListOf("en", "de"),
                podcastIndexKey = "",
                podcastIndexSecret = "",
            )
    }
}
