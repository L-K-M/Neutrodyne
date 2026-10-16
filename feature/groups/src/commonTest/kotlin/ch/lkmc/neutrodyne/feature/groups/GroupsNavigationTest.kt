// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.GroupSettingsKey
import ch.lkmc.neutrodyne.core.navigation.GroupsManageKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupsNavigationTest {
    @Test fun manageIsRegisteredAsAListPane() {
        val provider = entryProvider<NavKey> { GroupsNavigation.entries().invoke(this) }
        assertEquals(NdSceneMetadata.paneList(), provider(GroupsManageKey).metadata)
    }

    @Test fun settingsIsRegisteredAsADetailPane() {
        val provider = entryProvider<NavKey> { GroupsNavigation.entries().invoke(this) }
        assertEquals(NdSceneMetadata.paneDetail(), provider(GroupSettingsKey(7)).metadata)
    }

    @Test fun createAndEditAreRegisteredAsDetailPanes() {
        val provider = entryProvider<NavKey> { GroupsNavigation.entries().invoke(this) }
        for (key in listOf(GroupEditKey(null), GroupEditKey(7))) {
            assertEquals(NdSceneMetadata.paneDetail(), provider(key).metadata)
        }
    }
}
