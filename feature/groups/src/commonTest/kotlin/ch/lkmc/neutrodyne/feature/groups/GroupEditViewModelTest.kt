// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.lifecycle.SavedStateHandle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupEditViewModelTest : MainDispatcherTest() {
    private val clock = TestClock()
    private val groups = FakeGroupRepository(clock)
    private val podcasts = FakePodcastRepository()

    private fun editor(
        id: Long? = null,
        handle: SavedStateHandle = SavedStateHandle(),
        repo: GroupRepository = groups,
    ) = GroupEditViewModel(id, handle, repo, podcasts)

    private suspend fun create(
        name: String,
        members: Set<Long> = emptySet(),
    ): Long = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft(name), members)).value

    @Test fun missingGroupClosesAndFailedLoadCanRetry() =
        runTest {
            val gone = editor(999)
            advanceUntilIdle()
            assertEquals(GroupEditDone.Gone, gone.consumeDone())
            var fail = true
            val vm =
                editor(
                    repo =
                        object : GroupRepository by groups {
                            override fun observeGroups() =
                                kotlinx.coroutines.flow.flow {
                                    if (fail) error("read failed")
                                    emit(emptyList<ch.lkmc.neutrodyne.core.model.Group>())
                                }
                        },
                )
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loadFailed)
            assertFalse(vm.uiState.value.loaded)
            fail = false
            vm.load()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)
            assertFalse(vm.uiState.value.loadFailed)
        }

    @Test fun memberReadFailureCanRetryWithoutLosingSelections() =
        runTest {
            var fail = true
            val source =
                object : PodcastRepository by podcasts {
                    override fun observeLibraryTiles(groupId: Long?) =
                        flow {
                            if (fail) error("read failed")
                            emit(listOf(testLibraryTile(7)))
                        }
                }
            val vm = GroupEditViewModel(null, SavedStateHandle(), groups, source)
            advanceUntilIdle()
            vm.toggleMember(7)
            assertTrue(vm.uiState.value.membersFailed)
            fail = false
            vm.retry()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.membersFailed)
            assertEquals(
                listOf(7L),
                vm.uiState.value.tiles
                    .map { it.podcastId },
            )
            assertEquals(setOf(7L), vm.uiState.value.draft.memberIds)
        }

    @Test fun validationDebouncesAndCountsEmojiAndNormalizedNames() =
        runTest {
            val vm = editor()
            runCurrent()
            assertNull(vm.uiState.value.nameError)
            vm.edit(
                vm.uiState.value.draft
                    .copy(name = "x"),
            )
            vm.edit(
                vm.uiState.value.draft
                    .copy(name = ""),
            )
            runCurrent()
            advanceTimeBy(299)
            assertNull(vm.uiState.value.nameError)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(GroupError.NameEmpty, vm.uiState.value.nameError)
            vm.edit(
                vm.uiState.value.draft
                    .copy(name = "a".repeat(41)),
            )
            advanceUntilIdle()
            assertEquals(GroupError.NameTooLong(40), vm.uiState.value.nameError)
            vm.edit(
                vm.uiState.value.draft
                    .copy(name = "🎧".repeat(40)),
            )
            advanceUntilIdle()
            assertNull(vm.uiState.value.nameError)
            create("Café")
            vm.edit(
                vm.uiState.value.draft
                    .copy(name = "Cafe\u0301"),
            )
            advanceUntilIdle()
            assertIs<GroupError.NameTaken>(vm.uiState.value.nameError)
        }

    @Test fun createCarriesEveryDraftFieldAndMembers() =
        runTest {
            create("Existing")
            val vm = editor()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.dirty)
            assertEquals(GroupPalette.COLORS[1], vm.uiState.value.draft.colorArgb)
            vm.edit(
                vm.uiState.value.draft.copy(
                    name = "🎧 Commute",
                    iconKey = "memory",
                    playOrder = FeedOrder.OLDEST_FIRST,
                    showAsTab = false,
                ),
            )
            vm.toggleMember(3)
            vm.toggleMember(4)
            vm.toggleMember(3)
            vm.save()
            advanceUntilIdle()
            val group = groups.observeGroups().first().last()
            assertEquals("🎧 Commute", group.name)
            assertEquals("memory", group.iconKey)
            assertEquals(FeedOrder.OLDEST_FIRST, group.playOrder)
            assertFalse(group.showAsTab)
            assertEquals(setOf(4L), groups.observeMemberIds(group.id).first())
            assertEquals(GroupEditDone.Saved, vm.consumeDone())
            assertNull(vm.consumeDone())
        }

    @Test fun editPreservesIdentityAndFeedOrderAndSetsMembersLast() =
        runTest {
            val id = create("Original", setOf(1))
            val calls = mutableListOf<String>()
            val repo =
                object : GroupRepository by groups {
                    override suspend fun update(
                        groupId: Long,
                        edit: GroupEdit,
                    ): Outcome<Unit, GroupError> {
                        calls += edit::class.simpleName.orEmpty()
                        return groups.update(groupId, edit)
                    }

                    override suspend fun setMembers(
                        groupId: Long,
                        podcastIds: Set<Long>,
                    ): Outcome<Unit, GroupError> {
                        calls += "members"
                        return groups.setMembers(groupId, podcastIds)
                    }
                }
            val original = groups.observeGroup(id).first()!!
            val vm = editor(id, repo = repo)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.dirty)
            assertNull(vm.uiState.value.nameError)
            vm.edit(
                vm.uiState.value.draft
                    .copy(name = "Renamed", colorArgb = GroupPalette.COLORS[7]),
            )
            vm.toggleMember(2)
            groups.update(id, GroupEdit.Orders(FeedOrder.OLDEST_FIRST, FeedOrder.NEWEST_FIRST))
            vm.save()
            vm.save()
            advanceUntilIdle()
            assertEquals(listOf("Rename", "Look", "Orders", "ShowAsTab", "members"), calls)
            val saved = groups.observeGroup(id).first()!!
            assertEquals(original.uuid, saved.uuid)
            assertEquals(original.orderKey, saved.orderKey)
            assertEquals(FeedOrder.OLDEST_FIRST, saved.feedOrder)
            assertEquals(setOf(1L, 2L), groups.observeMemberIds(id).first())
            assertEquals(GroupEditDone.Saved, vm.consumeDone())
        }

    @Test fun nameTakenRaceIsInlineAndStopsOtherWrites() =
        runTest {
            val id = create("Original", setOf(1))
            val original = groups.observeGroup(id).first()
            val vm =
                editor(
                    id,
                    repo =
                        object : GroupRepository by groups {
                            override suspend fun update(
                                groupId: Long,
                                edit: GroupEdit,
                            ): Outcome<Unit, GroupError> = Outcome.Failure(GroupError.NameTaken(99))
                        },
                )
            advanceUntilIdle()
            vm.edit(
                vm.uiState.value.draft
                    .copy(name = "Clash", colorArgb = GroupPalette.COLORS[6]),
            )
            vm.toggleMember(2)
            vm.save()
            advanceUntilIdle()
            assertEquals(GroupError.NameTaken(99), vm.uiState.value.nameError)
            assertEquals(original, groups.observeGroup(id).first())
            assertEquals(setOf(1L), groups.observeMemberIds(id).first())
            assertNull(vm.consumeDone())
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun createRaceAndInvalidSaveNeverReportSuccess() =
        runTest {
            val vm =
                editor(
                    repo =
                        object : GroupRepository by groups {
                            override suspend fun create(
                                draft: GroupDraft,
                                memberIds: Set<Long>,
                            ): Outcome<Long, GroupError> = Outcome.Failure(GroupError.NameTaken(7))
                        },
                )
            advanceUntilIdle()
            vm.save()
            advanceUntilIdle()
            assertEquals(GroupError.NameEmpty, vm.uiState.value.nameError)
            vm.edit(
                vm.uiState.value.draft
                    .copy(name = "Name"),
            )
            vm.save()
            advanceUntilIdle()
            assertEquals(GroupError.NameTaken(7), vm.uiState.value.nameError)
            assertNull(vm.consumeDone())
            assertTrue(groups.observeGroups().first().isEmpty())
        }

    @Test fun discardDetectionAndSavedStateRestoreAllFieldsAndBaseline() =
        runTest {
            val handle = SavedStateHandle()
            val vm = editor(handle = handle)
            advanceUntilIdle()
            val original = vm.uiState.value.draft
            vm.search("show")
            assertFalse(vm.uiState.value.dirty)
            val draft =
                original.copy(
                    name = "Unsaved",
                    colorArgb = 0xFF123456.toInt(),
                    iconKey = "future_icon",
                    playOrder = FeedOrder.OLDEST_FIRST,
                    showAsTab = false,
                    memberIds = setOf(3, 8),
                )
            vm.edit(draft)
            assertTrue(vm.uiState.value.dirty)
            // A fresh ViewModel with only the saved primitives models process recreation, not reuse.
            val restored = editor(handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
            advanceUntilIdle()
            assertEquals(draft, restored.uiState.value.draft)
            assertTrue(restored.uiState.value.dirty)
            restored.edit(original)
            assertFalse(restored.uiState.value.dirty)
        }

    @Test fun deleteUndoRestoresOriginalAndExpiryIsReported() =
        runTest {
            val id = create("Original", setOf(1, 2))
            val original = groups.observeGroup(id).first()
            val vm = editor(id)
            advanceUntilIdle()
            vm.delete()
            advanceUntilIdle()
            assertNull(groups.observeGroup(id).first())
            val done = assertIs<GroupEditDone.Deleted>(vm.consumeDone())
            assertEquals(Outcome.Success(id), vm.undoDelete(done.token))
            assertEquals(original, groups.observeGroup(id).first())
            assertEquals(setOf(1L, 2L), groups.observeMemberIds(id).first())
            val again = editor(id)
            advanceUntilIdle()
            again.delete()
            advanceUntilIdle()
            val token = assertIs<GroupEditDone.Deleted>(again.consumeDone()).token
            clock.nowMs += GroupRepository.UNDO_WINDOW_MS
            assertEquals(Outcome.Failure(GroupError.UndoExpired), again.undoDelete(token))
        }

    @Test fun membershipFailureKeepsDraftForRetryAndUnexpectedFailureIsAcknowledged() =
        runTest {
            val id = create("Original")
            var fails = true
            val vm =
                editor(
                    id,
                    repo =
                        object : GroupRepository by groups {
                            override suspend fun setMembers(
                                groupId: Long,
                                podcastIds: Set<Long>,
                            ): Outcome<Unit, GroupError> {
                                if (fails) error("disk failure")
                                return groups.setMembers(groupId, podcastIds)
                            }
                        },
                )
            advanceUntilIdle()
            vm.toggleMember(7)
            vm.save()
            advanceUntilIdle()
            assertNull(vm.consumeDone())
            assertTrue(vm.uiState.value.dirty)
            assertFalse(vm.uiState.value.busy)
            val message =
                vm.uiState.value.messages
                    .single()
            vm.onMessageShown(message.id)
            runCurrent()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            fails = false
            vm.save()
            advanceUntilIdle()
            assertEquals(GroupEditDone.Saved, vm.consumeDone())
            assertEquals(setOf(7L), groups.observeMemberIds(id).first())
        }
}
