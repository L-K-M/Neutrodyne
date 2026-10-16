// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupIcons
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.MediaFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FakeGroupRepositoryTest {
    @Test fun fakeUsesSharedUnicodeAndDuplicateRules() =
        runTest {
            val repo = FakeGroupRepository()
            val id = assertIs<Outcome.Success<Long>>(repo.create(GroupDraft(" Café "))).value
            assertEquals(Outcome.Failure(GroupError.NameTaken(id)), repo.create(GroupDraft("CAFE\u0301")))
            assertEquals(Outcome.Failure(GroupError.NameEmpty), repo.create(GroupDraft("\t")))
            assertEquals(Outcome.Failure(GroupError.NameTooLong(40)), repo.create(GroupDraft("🎧".repeat(41))))
            assertIs<Outcome.Success<Long>>(repo.create(GroupDraft("🎧".repeat(40))))
            assertEquals(Outcome.Success("Café"), repo.checkName("Cafe\u0301", id))
            val other = assertIs<Outcome.Success<Long>>(repo.create(GroupDraft("Other"))).value
            assertEquals(Outcome.Failure(GroupError.NameTaken(id)), repo.update(other, GroupEdit.Rename("CAFÉ")))
        }

    @Test fun fakePreservesIdentityAndUnknownIconsAndObservesMembershipFixtures() =
        runTest {
            val repo = FakeGroupRepository()
            val id =
                assertIs<Outcome.Success<Long>>(
                    repo.create(GroupDraft("Tech", iconKey = "future_icon", showAsTab = false)),
                ).value
            val original = repo.observeGroup(id).first()!!
            assertFalse(original.showAsTab)
            repo.update(id, GroupEdit.Rename("TECH"))
            assertEquals(original.copy(name = "TECH"), repo.observeGroup(id).first())
            repo.members.value = mapOf(id to setOf(8L))
            assertEquals(setOf(8L), repo.observeMemberIds(id).first())
            assertEquals(
                1,
                repo
                    .observeGroupsOf(8)
                    .first()
                    .single()
                    .memberCount,
            )
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.update(-1, GroupEdit.ShowAsTab(true)))
            assertEquals(Outcome.Failure(GroupError.InvalidIconKey), repo.update(id, GroupEdit.Look(null, "BAD")))
        }

    @Test fun fakeRejectsUnsupportedAgeLimits() =
        runTest {
            val repo = FakeGroupRepository()
            val id = assertIs<Outcome.Success<Long>>(repo.create(GroupDraft("Name"))).value
            val before = repo.observeGroup(id).first()
            for (days in listOf(-1, 0, 2, Int.MAX_VALUE)) {
                val result = repo.update(id, GroupEdit.View(0, MediaFilter.ALL, days))
                assertEquals(Outcome.Failure(GroupError.InvalidHideOlderThanDays), result)
                assertEquals(before, repo.observeGroup(id).first())
            }
            for (days in listOf(null, 1, 3, 7, 14, 30, 90, 365)) {
                assertEquals(Outcome.Success(Unit), repo.update(id, GroupEdit.View(0, MediaFilter.ALL, days)))
            }
        }

    @Test fun paletteAndIconCataloguesFollowTheDocumentedValues() {
        assertEquals(12, GroupPalette.COLORS.size)
        assertEquals(12, GroupPalette.KEYS.size)
        assertEquals(31, GroupIcons.KEYS.distinct().size)
        for (key in GroupIcons.KEYS) assertEquals(true, GroupIcons.isValid(key))
        assertEquals(false, GroupIcons.isValid("x".repeat(41)))
        assertEquals(true, GroupIcons.isValid("x".repeat(40)))
        assertEquals(GroupPalette.COLORS[0], GroupPalette.nextColor(listOf(GroupPalette.COLORS[1])))
        assertEquals(GroupPalette.COLORS[1], GroupPalette.nextColor(GroupPalette.COLORS + null))
    }
}
