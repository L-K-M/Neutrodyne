// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DestructuringDeclarationWithTooManyEntries") // Four named group IDs make order assertions readable.
class GroupsManageViewModelTest : MainDispatcherTest() {
    private val groups = FakeGroupRepository()
    private val calls = mutableListOf<List<Long>>()
    private val recording =
        object : GroupRepository by groups {
            override suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError> {
                calls += groupIdsInOrder.toList()
                return groups.reorder(groupIdsInOrder)
            }
        }

    private suspend fun seed(): List<Long> =
        listOf("One", "Two", "Three", "Four").map {
            assertIs<Outcome.Success<Long>>(groups.create(GroupDraft(it))).value
        }

    private fun GroupsManageViewModel.ids() = uiState.value.groups.map { it.id }

    @Test fun dragFramesOnlyPreviewAndDropCallsRepositoryOnce() =
        runTest {
            val (a, b, c, d) = seed()
            val vm = GroupsManageViewModel(recording)
            advanceUntilIdle()
            val keysBefore = groups.groups.value.associate { it.id to it.orderKey }
            vm.startDrag(a)
            vm.dragOver(b)
            vm.dragOver(c)
            vm.dragOver(d)
            assertEquals(listOf(b, c, d, a), vm.ids())
            assertTrue(calls.isEmpty())
            vm.drop()
            vm.drop()
            advanceUntilIdle()
            assertEquals(listOf(listOf(b, c, d, a)), calls)
            assertEquals(
                setOf(a),
                groups.groups.value
                    .filter { it.orderKey != keysBefore[it.id] }
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(listOf(b, c, d, a), vm.ids())
            assertNull(vm.uiState.value.draggingId)
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun cancelAndDropInTheOriginalPlaceWriteNothing() =
        runTest {
            val (a, b) = seed()
            val vm = GroupsManageViewModel(recording)
            advanceUntilIdle()
            val original = vm.ids()
            vm.startDrag(a)
            vm.dragOver(b)
            vm.cancelDrag()
            assertEquals(original, vm.ids())
            vm.startDrag(a)
            vm.drop()
            advanceUntilIdle()
            assertTrue(calls.isEmpty())
        }

    @Test fun repositoryMetadataReconcilesWithoutReplacingTheDragPreview() =
        runTest {
            val (a, b, c, d) = seed()
            val vm = GroupsManageViewModel(recording)
            advanceUntilIdle()
            vm.startDrag(d)
            vm.dragOver(a)
            groups.update(b, GroupEdit.Rename("Renamed"))
            advanceUntilIdle()
            assertEquals(listOf(d, a, b, c), vm.ids())
            assertEquals(
                "Renamed",
                vm.uiState.value.groups
                    .single { it.id == b }
                    .name,
            )
            vm.drop()
            advanceUntilIdle()
            assertEquals(listOf(d, a, b, c), vm.ids())
        }

    @Test fun unrelatedConcurrentReorderSurvivesTheDrop() =
        runTest {
            val (a, b, c, d) = seed()
            val vm = GroupsManageViewModel(recording)
            advanceUntilIdle()
            vm.startDrag(d)
            vm.dragOver(a)
            groups.reorder(listOf(c, a, b, d))
            advanceUntilIdle()
            vm.drop()
            assertEquals(listOf(c, d, a, b), vm.ids())
            advanceUntilIdle()
            assertEquals(listOf(listOf(c, d, a, b)), calls)
            assertEquals(listOf(c, d, a, b), vm.ids())
        }

    @Test fun changedMembershipOfTheListCancelsDragAndReportsIt() =
        runTest {
            val (a, b) = seed()
            val vm = GroupsManageViewModel(recording)
            advanceUntilIdle()
            vm.startDrag(a)
            vm.dragOver(b)
            groups.create(GroupDraft("Five"))
            advanceUntilIdle()
            vm.drop()
            advanceUntilIdle()
            assertTrue(calls.isEmpty())
            assertNull(vm.uiState.value.draggingId)
            assertEquals(5, vm.ids().size)
            assertEquals(1, vm.uiState.value.messages.size)
        }

    @Test fun failedDropReloadsAndShowsAnAcknowledgedMessage() =
        runTest {
            val (a, b) = seed()
            val failing =
                object : GroupRepository by recording {
                    override suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError> {
                        groups.create(GroupDraft("Added at commit"))
                        return Outcome.Failure(GroupError.NotFound)
                    }
                }
            val vm = GroupsManageViewModel(failing)
            advanceUntilIdle()
            vm.startDrag(a)
            vm.dragOver(b)
            vm.drop()
            advanceUntilIdle()
            assertEquals(groups.groups.value.map { it.id }, vm.ids())
            assertEquals(1, vm.uiState.value.messages.size)
            vm.onMessageShown(
                vm.uiState.value.messages
                    .single()
                    .id,
            )
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test fun pendingCommitKeepsPreviewAndBlocksAnotherMove() =
        runTest {
            val (a, b, c, d) = seed()
            val gate = CompletableDeferred<Unit>()
            val delayed =
                object : GroupRepository by recording {
                    override suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError> {
                        gate.await()
                        return recording.reorder(groupIdsInOrder)
                    }
                }
            val vm = GroupsManageViewModel(delayed)
            advanceUntilIdle()
            vm.startDrag(d)
            vm.dragOver(a)
            vm.drop()
            runCurrent()
            groups.update(b, GroupEdit.ShowAsTab(false))
            runCurrent()
            assertEquals(listOf(d, a, b, c), vm.ids())
            vm.move(a, GroupMove.DOWN)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, calls.size)
            assertEquals(listOf(d, a, b, c), vm.ids())
        }

    @Test fun moveActionsRespectBoundariesAndToggleUsesTargetedEdit() =
        runTest {
            val (a, b, c, d) = seed()
            val edits = mutableListOf<Pair<Long, GroupEdit>>()
            val repo =
                object : GroupRepository by recording {
                    override suspend fun update(
                        groupId: Long,
                        edit: GroupEdit,
                    ): Outcome<Unit, GroupError> {
                        edits += groupId to edit
                        return groups.update(groupId, edit)
                    }
                }
            val vm = GroupsManageViewModel(repo)
            advanceUntilIdle()
            vm.move(a, GroupMove.UP)
            vm.move(d, GroupMove.DOWN)
            vm.move(a, GroupMove.TOP)
            assertTrue(calls.isEmpty())
            vm.move(c, GroupMove.TOP)
            advanceUntilIdle()
            assertEquals(listOf(listOf(c, a, b, d)), calls)
            vm.showAsTab(b, false)
            advanceUntilIdle()
            assertEquals<List<Pair<Long, GroupEdit>>>(listOf(b to GroupEdit.ShowAsTab(false)), edits)
            assertFalse(
                vm.uiState.value.groups
                    .single { it.id == b }
                    .showAsTab,
            )
        }

    @Test fun navigationEventsAreConsumedOnceAndDeletedGroupsCannotOpen() =
        runTest {
            val (a) = seed()
            val vm = GroupsManageViewModel(recording)
            advanceUntilIdle()
            vm.openEditor(a)
            assertEquals(GroupEditKey(a), vm.consumeNavigation())
            assertNull(vm.consumeNavigation())
            vm.openEditor()
            assertEquals(GroupEditKey(null), vm.consumeNavigation())
            vm.openEditor(999)
            assertNull(vm.consumeNavigation())
        }

    @Test fun deleteCanBeUndoneWithoutLeavingTheList() =
        runTest {
            val (a) = seed()
            val vm = GroupsManageViewModel(recording)
            advanceUntilIdle()
            vm.delete(a)
            advanceUntilIdle()
            assertFalse(a in vm.ids())
            val deleted = vm.consumeDeleted()!!
            assertNull(vm.consumeDeleted())
            assertIs<Outcome.Success<Long>>(vm.undoDelete(deleted.token))
            advanceUntilIdle()
            assertTrue(a in vm.ids())
            assertNull(vm.consumeNavigation())
        }

    @Test fun failedReadAndThrownWriteAreRecoverable() =
        runTest {
            val (a) = seed()
            var fail = true
            val repo =
                object : GroupRepository by recording {
                    override fun observeGroups() =
                        flow {
                            if (fail) error("read failed")
                            emitAll(groups.observeGroups())
                        }

                    override suspend fun update(
                        groupId: Long,
                        edit: GroupEdit,
                    ): Outcome<Unit, GroupError> = error("write failed")
                }
            val vm = GroupsManageViewModel(repo)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loadFailed)
            fail = false
            vm.retry()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)
            assertFalse(vm.uiState.value.loadFailed)
            vm.showAsTab(a, false)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.busy)
            assertEquals(1, vm.uiState.value.messages.size)
        }
}
