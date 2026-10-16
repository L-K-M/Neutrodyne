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

    @Test fun fakeMembershipOpsMirrorTheRepositoryContract() =
        runTest {
            val repo = FakeGroupRepository()
            val tech = assertIs<Outcome.Success<Long>>(repo.create(GroupDraft("Tech"), setOf(1L, 2L))).value
            val news = assertIs<Outcome.Success<Long>>(repo.create(GroupDraft("News"))).value
            assertEquals(setOf(1L, 2L), repo.observeMemberIds(tech).first())
            assertEquals(2, repo.observeGroup(tech).first()?.memberCount)
            assertEquals(setOf(tech), repo.observeMemberships().first().getValue(1L))

            assertEquals(Outcome.Success(Unit), repo.setMembers(tech, setOf(2L, 3L)))
            assertEquals(setOf(2L, 3L), repo.observeMemberIds(tech).first())
            assertEquals(2, repo.observeGroup(tech).first()?.memberCount)
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.setMembers(999, emptySet()))

            assertEquals(
                Outcome.Success(Unit),
                repo.applyMembership(setOf(2L), add = setOf(news), remove = setOf(tech)),
            )
            assertEquals(setOf(news), repo.observeMemberships().first().getValue(2L))
            assertEquals(
                Outcome.Failure(GroupError.NotFound),
                repo.applyMembership(setOf(2L), add = setOf(999), remove = emptySet()),
            )
            assertEquals(setOf(news), repo.observeMemberships().first().getValue(2L))
        }

    @Test fun fakeReorderReordersAndRejectsNonPermutations() =
        runTest {
            val repo = FakeGroupRepository()
            val ids = (1..3).map { assertIs<Outcome.Success<Long>>(repo.create(GroupDraft("G$it"))).value }
            assertEquals(ids, repo.observeGroups().first().map { it.id })
            assertEquals(Outcome.Success(Unit), repo.reorder(listOf(ids[2], ids[0], ids[1])))
            assertEquals(listOf(ids[2], ids[0], ids[1]), repo.observeGroups().first().map { it.id })
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.reorder(listOf(ids[0], ids[1], 99L)))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.reorder(ids.dropLast(1)))
            assertEquals(listOf(ids[2], ids[0], ids[1]), repo.observeGroups().first().map { it.id })
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
