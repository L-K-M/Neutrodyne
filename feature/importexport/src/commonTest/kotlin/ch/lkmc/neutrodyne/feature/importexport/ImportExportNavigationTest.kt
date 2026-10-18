// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportExportNavigationTest {
    @Test fun libraryAndGroupExportsAreRegisteredAsDialogs() {
        val provider = entryProvider<NavKey> { ImportExportNavigation.entries().invoke(this) }
        assertEquals(NdSceneMetadata.dialog(), provider(ExportKey(null)).metadata)
        assertEquals(NdSceneMetadata.dialog(), provider(ExportKey(5L)).metadata)
    }
}
