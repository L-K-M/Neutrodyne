// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Common domain callers see the same channel lifecycle from the reusable repository fakes. */
class GroupChannelCallSitesTest {
    @Test
    fun groupAndSettingsWritesInvokeTheDomainPort() =
        runTest {
            val channels = FakeGroupChannelSync()
            val fakeGroups = FakeGroupRepository(channelSync = channels)
            val groups: GroupRepository = fakeGroups
            val fakeSettings = FakeScopeSettingsRepository(channels)
            val settings: ScopeSettingsRepository = fakeSettings
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            val uuid =
                fakeGroups.groups.value
                    .single()
                    .uuid
            fakeSettings.seedGroup(id, uuid, "Tech")
            assertEquals(listOf("sync()"), channels.calls)

            assertIs<Outcome.Success<Unit>>(groups.update(id, GroupEdit.Rename("Science")))
            assertIs<Outcome.Success<Unit>>(settings.updateGroup(id) { it.copy(notifyNewEpisodes = true) })
            assertEquals(List(3) { "sync()" }, channels.calls)

            val observedUndo = mutableListOf<Set<String>>()
            channels.onSync = { observedUndo += groups.pendingUndoUuids() }
            val token = assertIs<Outcome.Success<DeletedGroupToken>>(groups.delete(id)).value
            assertIs<Outcome.Success<Long>>(groups.undoDelete(token))
            assertEquals(List(5) { "sync()" }, channels.calls)
            assertEquals(listOf(setOf(uuid), emptySet()), observedUndo)
        }
}
