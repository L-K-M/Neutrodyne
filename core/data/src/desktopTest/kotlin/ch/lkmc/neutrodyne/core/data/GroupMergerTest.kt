// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 05's `GroupMerger` over the bundled driver: memberships unite with the loser's `orderKey`s where
 * no overlap exists, the survivor's member and settings rows win, a GROUP play context moves to the
 * survivor and `GroupChannelSync.sync()` runs after the commit. Its callers (restore, `SyncApplier`)
 * arrive with M3/M10 — until then this suite is the only exerciser.
 */
class GroupMergerTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val channelSync = FakeGroupChannelSync()
    private val merger = GroupMerger(db, clock, channelSync)

    @AfterTest fun close() = db.close()

    @Test
    fun mergeUnitesMembersKeepsSurvivorRowsAndMovesContext() =
        runTest {
            val p1 = db.podcastDao().insertPodcast(podcastEntity())
            val p2 = db.podcastDao().insertPodcast(podcastEntity())
            val p3 = db.podcastDao().insertPodcast(podcastEntity())
            val survivor = db.groupDao().insert(groupEntity(name = "Tech", orderKey = "a"))
            val loser = db.groupDao().insert(groupEntity(name = "News", orderKey = "b"))
            db.groupDao().insertMember(memberEntity(survivor, p1, "m1"))
            db.groupDao().insertMember(memberEntity(survivor, p2, "m2"))
            db.groupDao().insertMember(memberEntity(loser, p1, "n1"))
            db.groupDao().insertMember(memberEntity(loser, p3, "n3"))
            val survivorSettings = PodcastGroupSettingsEntity(survivor, ScopeOverrides(notifyNewEpisodes = true))
            db.scopeSettingsDao().upsertGroup(survivorSettings)
            db.scopeSettingsDao().upsertGroup(PodcastGroupSettingsEntity(loser, ScopeOverrides(skipSilence = true)))
            db.playSessionDao().ensureNow(clock.now())
            db.useWriterConnection {
                it.execSql("UPDATE play_session SET contextType = 'GROUP', contextId = $loser WHERE id = 0")
            }
            clock.nowMs++

            merger.merge(loser, survivor)

            assertNull(db.groupDao().groupById(loser))
            val members = db.groupDao().membersOf(survivor).associateBy { it.podcastId }
            assertEquals(setOf(p1, p2, p3), members.keys)
            assertEquals("m1", members.getValue(p1).orderKey, "the survivor's own row wins the overlap")
            assertEquals("m2", members.getValue(p2).orderKey)
            assertEquals("n3", members.getValue(p3).orderKey, "the loser's orderKey rides along")

            // The survivor's settings row is kept; the loser's died with its row.
            assertEquals(survivorSettings, db.scopeSettingsDao().forGroup(survivor))
            assertNull(db.scopeSettingsDao().forGroup(loser))

            val session = assertNotNull(db.playSessionDao().get())
            assertEquals(survivor, session.contextId)

            assertEquals(clock.now(), db.groupDao().groupById(survivor)?.updatedAt)
            assertEquals(listOf("sync()"), channelSync.calls)
        }

    @Test
    fun mergeRejectsSameMissingAndSwappedGroupsWithoutWriting() =
        runTest {
            val survivor = db.groupDao().insert(groupEntity(name = "Tech", orderKey = "a"))
            val before = db.groupDao().groupById(survivor)

            assertFailsWith<IllegalArgumentException> { merger.merge(survivor, survivor) }
            assertFailsWith<IllegalArgumentException> { merger.merge(-1, survivor) }
            assertFailsWith<IllegalArgumentException> { merger.merge(survivor, -1) }

            assertEquals(before, db.groupDao().groupById(survivor))
            assertEquals(emptyList(), channelSync.calls)
        }
}
