// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.navigation.BackupKey
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ImportExportNavigationTest {
    @Test fun libraryAndGroupExportsAreRegisteredAsDialogs() {
        val provider = entryProvider<NavKey> { ImportExportNavigation.entries().invoke(this) }
        assertEquals(NdSceneMetadata.dialog(), provider(ExportKey(null)).metadata)
        assertEquals(NdSceneMetadata.dialog(), provider(ExportKey(5L)).metadata)
    }

    @Test fun theImportSessionResolvesAsADetailPane() {
        val provider = entryProvider<NavKey> { ImportExportNavigation.entries().invoke(this) }
        assertEquals(NdSceneMetadata.paneDetail(), provider(ImportKey("7")).metadata)
    }

    @Test fun theBackupKeyResolvesAsADetailPane() {
        val pending = entryProvider<NavKey> { ImportExportPendingNavigation.entries().invoke(this) }
        assertEquals(NdSceneMetadata.paneDetail(), pending(BackupKey).metadata)
        // ImportKey must not stay registered twice — D1's real entry lives beside ExportKey.
        assertFails { pending(ImportKey("7")) }
    }
}
