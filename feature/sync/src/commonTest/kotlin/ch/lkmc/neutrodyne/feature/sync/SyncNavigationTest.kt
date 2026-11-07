// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SyncHeldChangesKey
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import dev.zacsweers.metro.Provider
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncNavigationTest {
    /** The held-review destination is the overlay dialog the banner's Review pushes (08). */
    @Test
    fun theHeldChangesKeyResolvesAsADialog() {
        val provider =
            entryProvider<NavKey> {
                SyncNavigation.entries(Provider { FakeSyncController() }).invoke(this)
            }
        assertEquals(NdSceneMetadata.dialog(), provider(SyncHeldChangesKey(7)).metadata)
    }
}
