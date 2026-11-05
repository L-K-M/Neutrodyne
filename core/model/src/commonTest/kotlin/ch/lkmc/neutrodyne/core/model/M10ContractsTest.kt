// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibraryDensity
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M10ContractsTest {
    @Test fun appearanceDefaultsFollowPortableBackupAndDevicePolicy() {
        val portable =
            listOf(
                AppearanceSettingKeys.ARTWORK_TINT,
                AppearanceSettingKeys.PURE_BLACK,
                AppearanceSettingKeys.LIBRARY_DENSITY,
            )
        val panel = AppearanceSettingKeys.PLAYER_PANEL_HIDDEN
        assertTrue(AllSettingKeys.list.containsAll(portable + panel))
        for (key in portable) {
            assertEquals(SettingsFile.PORTABLE, key.file)
            assertFalse(key.synced)
        }
        assertEquals(SettingsFile.DEVICE, panel.file)
        assertFalse(panel.synced)
        assertFalse(panel.default)
        assertTrue(AppearanceSettingKeys.ARTWORK_TINT.default)
        assertFalse(AppearanceSettingKeys.PURE_BLACK.default)
        val density = AppearanceSettingKeys.LIBRARY_DENSITY
        assertEquals(LibraryDensity.MEDIUM, density.default)
        assertEquals(listOf(72, 100, 152), density.values.map { it.minCellWidthDp })
        assertEquals(LibraryDensity.MEDIUM, density.fromStored("unknown"))
    }

    @Test fun mosaicAcceptsEmptyAndPreservesOrderedVersionedMembers() {
        val empty = MosaicSnapshot(1, "uuid", "Group", null, null, persistentListOf())
        val members = (1L..4L).map { GroupMosaicMember(1, it, "Title $it", ArtworkRef("key-$it", null, 3), null) }
        val snapshot = empty.copy(members = members.toPersistentList())
        assertEquals(listOf(1L, 2L, 3L, 4L), snapshot.members.map { it.podcastId })
        assertEquals(0, empty.members.size)
        assertFailsWith<IllegalArgumentException> {
            snapshot.copy(
                members = (members + members.first()).toPersistentList(),
            )
        }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(groupId = 2) }
        assertFailsWith<IllegalArgumentException> { empty.copy(members = persistentListOf(members[0], members[0])) }
    }

    @Test fun postureRetainsWindowDpWithoutConflatingSeparatingAndHalfOpened() {
        val bounds = WindowRectDp(0f, 300f, 840f, 308f)
        val hinge = WindowHinge(bounds, HingeOrientation.HORIZONTAL, HingeState.HALF_OPENED, false)
        assertEquals(
            300f,
            WindowPosture(persistentListOf(hinge))
                .hinges
                .single()
                .bounds.top,
        )
        assertTrue(WindowPosture().hinges.isEmpty())
        assertFailsWith<IllegalArgumentException> { WindowRectDp(0f, Float.NaN, 1f, 2f) }
        assertFailsWith<IllegalArgumentException> { WindowRectDp(2f, 0f, 1f, 2f) }
    }
}
