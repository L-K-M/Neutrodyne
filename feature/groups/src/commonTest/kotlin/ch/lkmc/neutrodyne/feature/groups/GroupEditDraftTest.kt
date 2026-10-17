// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import ch.lkmc.neutrodyne.core.model.FeedOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Saved drafts can outlive an app update; a malformed entry must start the editor fresh. */
class GroupEditDraftTest {
    @Test fun savedDraftRoundTrips() {
        val draft = GroupEditDraft("Tech", 0xFF336699.toInt(), "star", FeedOrder.OLDEST_FIRST, false, setOf(3L, 7L))
        assertEquals(draft, GroupEditDraft.restore(draft.save()))
    }

    @Test fun malformedEntriesRestoreAsNull() {
        val valid = GroupEditDraft("Tech", null, null, FeedOrder.NEWEST_FIRST, true, setOf(3L)).save()
        assertNull(GroupEditDraft.restore(valid.take(2)))
        assertNull(GroupEditDraft.restore(valid.toMutableList().also { it[3] = "SIDEWAYS" }))
        assertNull(GroupEditDraft.restore(valid + "not-an-id"))
    }
}
