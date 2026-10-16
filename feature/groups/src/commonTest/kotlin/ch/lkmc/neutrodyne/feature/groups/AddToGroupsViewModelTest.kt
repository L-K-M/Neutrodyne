// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.ui.state.ToggleableState
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.membership_changed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AddToGroupsViewModelTest : MainDispatcherTest() {
    private val groups = FakeGroupRepository()
    private val podcasts = FakePodcastRepository()
    private val writes = mutableListOf<Triple<Set<Long>, Set<Long>, Set<Long>>>()
    private var failNextWrite = false
    private var collections = 0
    private val repository =
        object : GroupRepository by groups {
            override fun observeMemberships() = groups.observeMemberships().also { collections++ }

            override suspend fun applyMembership(
                podcastIds: Set<Long>,
                add: Set<Long>,
                remove: Set<Long>,
            ): Outcome<Unit, GroupError> {
                writes += Triple(podcastIds, add, remove)
                if (failNextWrite) {
                    failNextWrite = false
                    return Outcome.Failure(GroupError.NotFound)
                }
                return groups.applyMembership(podcastIds, add, remove)
            }
        }

    private suspend fun seed(): List<Long> {
        podcasts.tiles.value = listOf(testLibraryTile(1), testLibraryTile(2), testLibraryTile(3))
        val ids = listOf("All", "Some", "None").map { (groups.create(GroupDraft(it)) as Outcome.Success).value }
        groups.members.value = mapOf(ids[0] to setOf(1, 2, 3), ids[1] to setOf(1))
        return ids
    }

    private fun viewModel() = AddToGroupsViewModel(listOf(1, 2, 3), repository, podcasts)

    @Test fun initialStatesUseOneMembershipCollectionInUserOrder() =
        runTest {
            val ids = seed()
            groups.reorder(ids.reversed())
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(1, collections)
            assertEquals(
                ids.reversed(),
                vm.uiState.value.chips
                    .map { it.group.id },
            )
            assertEquals(
                listOf(
                    ToggleableState.Off,
                    ToggleableState.Indeterminate,
                    ToggleableState.On,
                ),
                vm.uiState.value.chips.map {
                    it.selection
                },
            )
        }

    @Test fun mixedCyclesToCheckedThenUncheckedWithoutReenteringMixed() =
        runTest {
            val id = seed()[1]
            val vm = viewModel()
            advanceUntilIdle()
            repeat(4) { index ->
                vm.toggle(id)
                assertEquals(
                    if (index % 2 ==
                        0
                    ) {
                        ToggleableState.On
                    } else {
                        ToggleableState.Off
                    },
                    vm.uiState.value.chips
                        .single { it.group.id == id }
                        .selection,
                )
            }
        }

    @Test fun doneSendsOnlyChangedChipsAndPreservesUntouchedMemberships() =
        runTest {
            val ids = seed()
            val vm = viewModel()
            advanceUntilIdle()
            vm.toggle(ids[0])
            vm.toggle(ids[2])
            vm.done()
            advanceUntilIdle()
            assertEquals(listOf(Triple(setOf(1L, 2L, 3L), setOf(ids[2]), setOf(ids[0]))), writes)
            assertEquals(setOf(1L), groups.members.value[ids[1]])
            assertEquals(3, vm.consumeDone())
            assertNull(vm.consumeDone())
        }

    @Test fun togglingBackToInitialStateDoesNotWriteTheChip() =
        runTest {
            val id = seed()[0]
            val vm = viewModel()
            advanceUntilIdle()
            vm.toggle(id)
            vm.toggle(id)
            vm.done()
            advanceUntilIdle()
            assertEquals(Triple(setOf(1L, 2L, 3L), emptySet(), emptySet()), writes.single())
        }

    @Test fun newGroupIsCreatedCheckedAndAddedOnDone() =
        runTest {
            seed()
            val vm = viewModel()
            advanceUntilIdle()
            vm.editName(" New group ")
            vm.createGroup()
            advanceUntilIdle()
            val chip =
                vm.uiState.value.chips
                    .single { it.group.name == "New group" }
            assertEquals(ToggleableState.On, chip.selection)
            assertNull(vm.uiState.value.name)
            vm.done()
            advanceUntilIdle()
            assertEquals(setOf(chip.group.id), writes.single().second)
        }

    @Test fun invalidAndDuplicateNewNamesStayInline() =
        runTest {
            seed()
            val vm = viewModel()
            advanceUntilIdle()
            for (name in listOf(" ", "x".repeat(41), "ALL")) {
                vm.editName(name)
                vm.createGroup()
                advanceUntilIdle()
                assertNotNull(vm.uiState.value.nameError)
            }
            assertEquals(3, groups.groups.value.size)
        }

    @Test fun notFoundReloadsAndClearsStaleChanges() =
        runTest {
            val id = seed()[0]
            val vm = viewModel()
            advanceUntilIdle()
            vm.toggle(id)
            groups.groups.value = groups.groups.value.filterNot { it.id == id }
            failNextWrite = true
            vm.done()
            advanceUntilIdle()
            assertEquals(2, collections)
            assertEquals(
                UiText.Res(Res.string.membership_changed),
                vm.messages.value
                    .single()
                    .text,
            )
            assertNull(vm.uiState.value.doneCount)
            assertEquals(2, vm.uiState.value.chips.size)
            vm.done()
            advanceUntilIdle()
            assertEquals(emptySet(), writes.last().third)
        }

    @Test fun unsubscribedPodcastsAreDroppedAtTheWriteBoundary() =
        runTest {
            seed()
            val vm = viewModel()
            advanceUntilIdle()
            // Do not advance the observer before Done: the write must re-read subscriptions itself.
            podcasts.tiles.value = listOf(testLibraryTile(2))
            vm.done()
            advanceUntilIdle()
            assertEquals(setOf(2L), writes.single().first)
            assertEquals(1, vm.uiState.value.doneCount)
        }
}
