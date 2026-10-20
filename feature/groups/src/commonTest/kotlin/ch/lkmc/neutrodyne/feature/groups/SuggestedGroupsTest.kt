// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SuggestedGroups
import ch.lkmc.neutrodyne.core.model.CategoryCount
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.settings.DiscoverSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SuggestedGroupsTest {
    @Test fun selectedSuggestionsCreateAtomicMembershipsWithPaletteDefaults() =
        runTest {
            val podcasts =
                FakePodcastRepository().apply {
                    categoryCounts.value =
                        listOf(CategoryCount("News", listOf(1, 2)), CategoryCount("Technology", listOf(2, 3)))
                }
            val groups = FakeGroupRepository()
            val controller = SuggestedGroups(podcasts, groups, FakeSettingsRepository(), backgroundScope)
            runCurrent()
            controller.toggle("News")
            controller.toggle("Technology")
            controller.create()
            runCurrent()
            assertEquals(
                2,
                controller.state.value.result
                    ?.created,
            )
            assertEquals(
                0,
                controller.state.value.result
                    ?.failed,
            )
            assertEquals(listOf(setOf(1L, 2L), setOf(2L, 3L)), groups.groups.value.map { groups.members.value[it.id] })
            assertEquals(
                2,
                groups.groups.value
                    .map { it.colorArgb }
                    .toSet()
                    .size,
            )
            assertEquals(
                groups.groups.value
                    .first()
                    .uuid,
                controller.state.value.result
                    ?.firstGroupUuid,
            )
        }

    @Test fun partialNameRaceCountsOnlyCommittedGroupsAndLeavesFailureSelected() =
        runTest {
            val podcasts =
                FakePodcastRepository().apply {
                    categoryCounts.value =
                        listOf(CategoryCount("News", listOf(1, 2)), CategoryCount("Technology", listOf(2, 3)))
                }
            val backing = FakeGroupRepository()
            val groups =
                object : GroupRepository by backing {
                    override suspend fun create(
                        draft: GroupDraft,
                        memberIds: Set<Long>,
                    ): Outcome<Long, GroupError> =
                        if (draft.name == "News") {
                            Outcome.Failure(GroupError.NameTaken(99))
                        } else {
                            backing.create(draft, memberIds)
                        }
                }
            val controller = SuggestedGroups(podcasts, groups, FakeSettingsRepository(), backgroundScope)
            runCurrent()
            controller.toggle("News")
            controller.toggle("Technology")
            controller.create()
            controller.create()
            runCurrent()
            assertEquals(
                1,
                controller.state.value.result
                    ?.created,
            )
            assertEquals(
                1,
                controller.state.value.result
                    ?.failed,
            )
            assertEquals(setOf("News"), controller.state.value.selected)
            assertEquals(1, backing.groups.value.size)
            controller.consumeResult()
            assertEquals(null, controller.state.value.result)
        }

    @Test fun dismissalPersistsOnlyDeviceFlagAcrossRelaunchAndFailureRemainsVisible() =
        runTest {
            val podcasts =
                FakePodcastRepository().apply {
                    categoryCounts.value = listOf(CategoryCount("News", listOf(1, 2)))
                }
            val settings = FakeSettingsRepository()
            val groups = FakeGroupRepository()
            val controller = SuggestedGroups(podcasts, groups, settings, backgroundScope)
            runCurrent()
            settings.failNextSet = SettingsError.WriteFailed
            controller.dismiss()
            runCurrent()
            assertFalse(settings.get(DiscoverSettingKeys.SUGGESTED_GROUPS_DISMISSED))
            assertEquals(1, controller.state.value.suggestions.size)
            controller.dismiss()
            runCurrent()
            val reentered = SuggestedGroups(podcasts, groups, settings, backgroundScope)
            runCurrent()
            assertTrue(
                reentered.state.value.suggestions
                    .isEmpty(),
            )
            assertTrue(groups.groups.value.isEmpty())
            val snapshot = settings.observePortableSnapshot().first()
            assertFalse(snapshot.containsKey(DiscoverSettingKeys.SUGGESTED_GROUPS_DISMISSED.name))
        }

    @Test fun updatedCountsRemoveStaleSelectionsAndUnknownChipsDoNothing() =
        runTest {
            val podcasts =
                FakePodcastRepository().apply {
                    categoryCounts.value = listOf(CategoryCount("News", listOf(1, 2)))
                }
            val controller = SuggestedGroups(podcasts, FakeGroupRepository(), FakeSettingsRepository(), backgroundScope)
            runCurrent()
            controller.toggle("unknown")
            assertTrue(
                controller.state.value.selected
                    .isEmpty(),
            )
            controller.toggle("News")
            podcasts.categoryCounts.value = emptyList()
            runCurrent()
            assertTrue(
                controller.state.value.selected
                    .isEmpty(),
            )
            controller.create()
            assertEquals(null, controller.state.value.result)
        }
}
