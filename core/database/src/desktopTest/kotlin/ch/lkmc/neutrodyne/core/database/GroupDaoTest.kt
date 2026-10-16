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

    @Test fun mosaicsReturnFourNewestActiveMembersPerGroup() =
        runTest {
            val group = dao.insert(groupEntity(orderKey = "a"))
            val podcasts =
                (1..5).map { i ->
                    db.podcastDao().insertPodcast(
                        podcastEntity(title = "P$i").copy(latestEpisodeAt = i * 1000L),
                    )
                }
            // Insert members out of recency order; the mosaic must still rank by latestEpisodeAt.
            dao.insertMember(memberEntity(group, podcasts[0], "a"))
            dao.insertMember(memberEntity(group, podcasts[4], "b"))
            dao.insertMember(memberEntity(group, podcasts[2], "c"))
            dao.insertMember(memberEntity(group, podcasts[3], "d"))
            dao.insertMember(memberEntity(group, podcasts[1], "e"))

            val rows = dao.observeMosaics().first()
            assertEquals(
                listOf(podcasts[4], podcasts[3], podcasts[2], podcasts[1]),
                rows.map { it.podcastId },
            )
            assertEquals(rows.map { it.podcastId }.size, 4)
            assertEquals(rows.map { it.groupId }.distinct(), listOf(group))
        }

    @Test fun mosaicsScopeMembersToTheirOwnGroupAndJoinArtwork() =
        runTest {
            val group = dao.insert(groupEntity(name = "One", orderKey = "a"))
            val other = dao.insert(groupEntity(name = "Two", orderKey = "b"))
            val pinned =
                db.podcastDao().insertPodcast(
                    podcastEntity(title = "Custom")
                        .copy(
                            artworkKey = "u-cover",
                            artworkUrl = "https://x/c.jpg",
                            customTitle = "Renamed",
                            latestEpisodeAt = 5_000L,
                        ),
                )
            val plain =
                db.podcastDao().insertPodcast(
                    podcastEntity(title = "Plain").copy(latestEpisodeAt = 9_000L),
                )
            // Never published: NULL latestEpisodeAt sorts last under DESC.
            val never = db.podcastDao().insertPodcast(podcastEntity(title = "Never"))
            dao.insertMember(memberEntity(group, pinned, "a"))
            dao.insertMember(memberEntity(group, plain, "b"))
            dao.insertMember(memberEntity(group, never, "c"))
            dao.insertMember(memberEntity(other, plain, "a"))
            db.artworkDao().upsert(ArtworkEntity(key = "u-cover", version = 7, avgArgb = 0xFF112233.toInt()))

            val rows = dao.observeMosaics().first()
            val mine = rows.filter { it.groupId == group }.map { it.podcastId }
            // NULL latestEpisodeAt sorts last; only this group's members appear.
            assertEquals(listOf(plain, pinned, never), mine)
            val cover = rows.single { it.podcastId == pinned }
            assertEquals("Renamed", cover.title)
            assertEquals("u-cover", cover.artworkKey)
            assertEquals("https://x/c.jpg", cover.artworkUrl)
            assertEquals(7, cover.artworkVersion)
            assertEquals(0xFF112233.toInt(), cover.artworkAvgArgb)
            val plainRow = rows.single { it.groupId == group && it.podcastId == plain }
            assertEquals(0, plainRow.artworkVersion)
            assertNull(plainRow.artworkAvgArgb)
        }

    @Test fun mosaicsReemitOnMemberAndArtworkChanges() =
        runTest {
            val group = dao.insert(groupEntity(orderKey = "a"))
            val podcast = db.podcastDao().insertPodcast(podcastEntity().copy(artworkKey = "u-a"))
            dao.observeMosaics().test {
                assertEquals(emptyList(), awaitItem())
                dao.insertMember(memberEntity(group, podcast, "a"))
                assertEquals(listOf(podcast), awaitItem().map { it.podcastId })
                db.artworkDao().upsert(ArtworkEntity(key = "u-a", version = 2))
                assertEquals(2, awaitItem().single().artworkVersion)
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
