// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SyncDiagnosticsKey
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import dev.zacsweers.metro.Provider
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** `SyncDiagnosticsKey` must resolve to a real detail entry of `:feature:sync` (08 Screen inventory). */
class SyncDiagnosticsNavigationTest {
    @Test
    fun theDiagnosticsKeyInstallsADetailEntry() {
        val installer =
            SyncNavigation.entries(
                syncController = Provider { FakeSyncController() },
                backups = Provider { FakeBackupRepository() },
                address = Provider { FakeSyncServerAddress() },
                buildInfo = TEST_BUILD,
            )
        val provider = entryProvider<NavKey> { installer() }

        val entry = provider(SyncDiagnosticsKey)
        assertNotNull(entry.contentKey)
        assertEquals(NdSceneMetadata.PANE_DETAIL, entry.metadata[NdSceneMetadata.KEY_PANE])
    }

    private companion object {
        val TEST_BUILD =
            BuildInfo(
                versionName = "0.1.0",
                versionCode = 10095,
                debug = false,
                platform = BuildInfo.Platform.DESKTOP,
                repoUrl = "https://repo.example/neutrodyne",
                updateManifestUrl = "",
                engineManifestUrl = "",
                youTubeEngineBundled = false,
                shippedLocales = persistentListOf("en", "de"),
                podcastIndexKey = "",
                podcastIndexSecret = "",
            )
    }
}
