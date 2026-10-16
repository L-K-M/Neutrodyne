// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GroupDaoTest {
    private val db = TestDb.inMemory()
    private val dao = db.groupDao()

    @AfterTest fun close() = db.close()

    @Test fun groupReadsUseOrderKeyThenIdAndIncludeEmptyGroups() =
        runTest {
            val later = dao.insert(groupEntity(name = "Later", orderKey = "b"))
            val first = dao.insert(groupEntity(name = "First", orderKey = "a"))
            val tied = dao.insert(groupEntity(name = "Tied", orderKey = "a"))
            assertEquals(listOf(first, tied, later), dao.observeGroups().first().map { it.group.id })
            assertEquals("b", dao.lastGroupOrderKey())
            assertEquals(0, dao.observeGroup(first).first()?.memberCount)
            assertNull(dao.observeGroup(-1).first())
            assertEquals(first, dao.groupByNameKey("first")?.id)
            assertNull(dao.groupByNameKey("missing"))
        }

    @Test fun memberChangesInvalidateGroupCountsAndMembershipReads() =
        runTest {
            val group = dao.insert(groupEntity(orderKey = "a"))
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            dao.observeGroup(group).test {
                assertEquals(0, awaitItem()?.memberCount)
                dao.insertMember(memberEntity(group, podcast, "a"))
                assertEquals(1, awaitItem()?.memberCount)
                assertEquals(listOf(podcast), dao.observeMemberIds(group).first())
                assertEquals(listOf(group), dao.observeGroupsOf(podcast).first().map { it.group.id })
            }
        }

    @Test fun membershipsAreScopedAndGroupsOfPodcastUseGroupOrder() =
        runTest {
            val later = dao.insert(groupEntity(name = "Later", orderKey = "b"))
            val first = dao.insert(groupEntity(name = "First", orderKey = "a"))
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val other = db.podcastDao().insertPodcast(podcastEntity())
            dao.insertMember(memberEntity(later, podcast, "a"))
            dao.insertMember(memberEntity(first, other, "a"))
            dao.insertMember(memberEntity(first, podcast, "b"))
            assertEquals(listOf(first, later), dao.observeGroupsOf(podcast).first().map { it.group.id })
            assertEquals(listOf(other, podcast), dao.observeMemberIds(first).first())
            assertEquals(emptyList(), dao.observeMemberIds(-1).first())
            assertEquals(emptyList(), dao.observeGroupsOf(-1).first())
        }

    @Test fun targetedUpdatesPreserveIdentityAndOtherPreferences() =
        runTest {
            val original = groupEntity(orderKey = "a").copy(ruleJson = "reserved", lastViewedAt = 7)
            val id = dao.insert(original)
            dao.rename(id, "Renamed", "renamed", 10)
            dao.setLook(id, 123, "future_icon", 11)
            dao.setOrders(id, FeedOrder.OLDEST_FIRST, FeedOrder.OLDEST_FIRST, 12)
            dao.setView(id, 3, MediaFilter.AUDIO, 30, 13)
            dao.setShowAsTab(id, false, 14)
            val row = assertNotNull(dao.groupById(id))
            assertEquals(
                original.copy(
                    id = id,
                    name = "Renamed",
                    nameKey = "renamed",
                    colorArgb = 123,
                    iconKey = "future_icon",
                    feedOrder = FeedOrder.OLDEST_FIRST,
                    playOrder = FeedOrder.OLDEST_FIRST,
                    filterFlags = 3,
                    mediaFilter = MediaFilter.AUDIO,
                    hideOlderThanDays = 30,
                    showAsTab = false,
                    updatedAt = 14,
                ),
                row,
            )
            assertEquals(listOf(123), dao.groupColors())
        }

    @Test fun nameKeyUniqueIndexRejectsInsertAndRenameWithoutOverwriting() =
        runTest {
            val first = dao.insert(groupEntity(name = "Tech", orderKey = "a"))
            val other = dao.insert(groupEntity(name = "Other", orderKey = "b"))
            assertThrows { dao.insert(groupEntity(name = "tech", orderKey = "c")) }
            assertThrows { dao.rename(other, "TECH", "tech", 10) }
            assertEquals("Other", dao.groupById(other)?.name)
            assertEquals(first, dao.groupByNameKey("tech")?.id)
            assertEquals(2, dao.observeGroups().first().size)
        }
}
