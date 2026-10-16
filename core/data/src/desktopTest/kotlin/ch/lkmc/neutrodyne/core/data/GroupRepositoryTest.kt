// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.repo.GroupRepositoryImpl
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Basic group lifecycle over Room's bundled SQLite driver, including real unique-index races. */
class GroupRepositoryTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val orderKeys = TestOrderKeys
    private val repo =
        GroupRepositoryImpl(
            db,
            clock,
            orderKeys,
            FakeGroupChannelSync(),
            FakeRefreshController(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

    @AfterTest fun close() = db.close()

    @Test fun createNormalizesTheNameAndAssignsDefaultsInOrder() =
        runTest {
            repo.observeGroups().test {
                assertEquals(emptyList(), awaitItem())
                val first = created(GroupDraft("  Cafe\u0301  "))
                val group = awaitItem().single()
                assertEquals(first, group.id)
                assertEquals("Café", group.name)
                assertEquals("0.5", group.orderKey)
                assertEquals(GroupPalette.COLORS.first(), group.colorArgb)
                assertEquals(FeedOrder.NEWEST_FIRST, group.playOrder)
                assertTrue(group.showAsTab)
                assertEquals(0, group.memberCount)
                assertEquals(clock.now(), group.createdAt)
                assertTrue(group.uuid.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
                val next = created(GroupDraft("Next", playOrder = FeedOrder.OLDEST_FIRST, showAsTab = false))
                val rows = awaitItem()
                assertEquals(listOf(first, next), rows.map { it.id })
                assertEquals("0.75", rows.last().orderKey)
                assertEquals(GroupPalette.COLORS[1], rows.last().colorArgb)
                assertEquals(FeedOrder.OLDEST_FIRST, rows.last().playOrder)
                assertFalse(rows.last().showAsTab)
            }
        }

    @Test fun paletteUsesFirstUnusedThenGroupCountModuloTwelve() =
        runTest {
            created(GroupDraft("Explicit", colorArgb = GroupPalette.COLORS[1]))
            created(GroupDraft("First free"))
            assertEquals(
                GroupPalette.COLORS[0],
                repo
                    .observeGroups()
                    .first()
                    .last()
                    .colorArgb,
            )
            for (index in 2 until GroupPalette.COLORS.size) created(GroupDraft("Group $index"))
            created(GroupDraft("Wrapped"))
            assertEquals(
                GroupPalette.COLORS[0],
                repo
                    .observeGroups()
                    .first()
                    .last()
                    .colorArgb,
            )
        }

    @Test fun caseAndNfcDuplicatesIncludingRenameReturnTheWinningId() =
        runTest {
            val tech = created(GroupDraft("Tech"))
            val cafe = created(GroupDraft("Café"))
            val other = created(GroupDraft("Other"))
            assertEquals(Outcome.Failure(GroupError.NameTaken(tech)), repo.create(GroupDraft("tech")))
            assertEquals(Outcome.Failure(GroupError.NameTaken(cafe)), repo.create(GroupDraft("Cafe\u0301")))
            assertEquals(
                Outcome.Failure(GroupError.NameTaken(tech)),
                repo.update(other, GroupEdit.Rename(" TECH ")),
            )
            assertEquals(
                Outcome.Failure(GroupError.NameTaken(cafe)),
                repo.update(other, GroupEdit.Rename("CAFE\u0301")),
            )
            assertEquals("Other", repo.observeGroup(other).first()?.name)
            assertEquals(Outcome.Success(Unit), repo.update(tech, GroupEdit.Rename("TECH")))
            assertEquals(3, repo.observeGroups().first().size)
        }

    @Test fun checkNameValidatesAndCanExcludeTheEditedGroup() =
        runTest {
            val id = created(GroupDraft("Tech"))
            assertEquals(Outcome.Failure(GroupError.NameTaken(id)), repo.checkName("tech"))
            assertEquals(Outcome.Success("TECH"), repo.checkName(" TECH ", id))
            assertEquals(Outcome.Success("New name"), repo.checkName("New  name"))
            assertEquals(Outcome.Failure(GroupError.NameEmpty), repo.checkName("\t"))
            assertEquals(Outcome.Failure(GroupError.NameTooLong(40)), repo.checkName("🎧".repeat(41)))
        }

    @Test fun validationFailuresWriteNothingAndMissingUpdatesReturnNotFound() =
        runTest {
            assertEquals(Outcome.Failure(GroupError.NameEmpty), repo.create(GroupDraft(" ")))
            assertEquals(Outcome.Failure(GroupError.NameTooLong(40)), repo.create(GroupDraft("🎧".repeat(41))))
            assertEquals(
                Outcome.Failure(GroupError.InvalidIconKey),
                repo.create(GroupDraft("Name", iconKey = "Bad-Key")),
            )
            assertEquals(emptyList(), repo.observeGroups().first())
            val id = created(GroupDraft("Good"))
            val before = db.groupDao().groupById(id)
            assertEquals(Outcome.Failure(GroupError.NameEmpty), repo.update(id, GroupEdit.Rename("")))
            assertEquals(
                Outcome.Failure(GroupError.NameTooLong(40)),
                repo.update(id, GroupEdit.Rename("a".repeat(41))),
            )
            assertEquals(Outcome.Failure(GroupError.InvalidIconKey), repo.update(id, GroupEdit.Look(null, "")))
            assertEquals(before, db.groupDao().groupById(id))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.update(-1, GroupEdit.ShowAsTab(false)))
            assertNull(repo.observeGroup(-1).first())
        }

    @Test fun unsupportedAgeLimitsAreRejectedWithoutChangingTheGroup() =
        runTest {
            val id = created(GroupDraft("Name"))
            val before = db.groupDao().groupById(id)
            for (days in listOf(-1, 0, 2, Int.MAX_VALUE)) {
                val result = repo.update(id, GroupEdit.View(0, MediaFilter.ALL, days))
                assertEquals(Outcome.Failure(GroupError.InvalidHideOlderThanDays), result)
                assertEquals(before, db.groupDao().groupById(id))
            }
            for (days in listOf(null, 1, 3, 7, 14, 30, 90, 365)) {
                assertEquals(Outcome.Success(Unit), repo.update(id, GroupEdit.View(0, MediaFilter.ALL, days)))
                assertEquals(days, repo.observeGroup(id).first()?.hideOlderThanDays)
            }
        }

    @Test fun concurrentCreatesReturnOneSuccessAndNameTakenForEveryLoser() =
        runTest {
            val start = CompletableDeferred<Unit>()
            val results =
                (0 until 8).map { index ->
                    async(Dispatchers.Default) {
                        val independentRepo =
                            GroupRepositoryImpl(
                                db,
                                clock,
                                orderKeys,
                                FakeGroupChannelSync(),
                                FakeRefreshController(),
                                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                            )
                        start.await()
                        independentRepo.create(GroupDraft(if (index % 2 == 0) "Café" else "CAFE\u0301"))
                    }
                }
            start.complete(Unit)
            val outcomes = results.awaitAll()
            val winner = outcomes.filterIsInstance<Outcome.Success<Long>>().single().value
            assertEquals(7, outcomes.count { it == Outcome.Failure(GroupError.NameTaken(winner)) })
            assertEquals(listOf(winner), repo.observeGroups().first().map { it.id })
        }

    @Test fun concurrentDistinctCreatesUseDifferentOrderKeysAndPaletteColors() =
        runTest {
            val results =
                (0 until 4)
                    .map { index ->
                        async(Dispatchers.Default) { repo.create(GroupDraft("Group $index")) }
                    }.awaitAll()
            assertTrue(results.all { it is Outcome.Success })
            val groups = repo.observeGroups().first()
            assertEquals(listOf("0.5", "0.75", "0.875", "0.9375"), groups.map { it.orderKey })
            assertEquals(GroupPalette.COLORS.take(4), groups.map { it.colorArgb })
        }

    @Test fun everyEditPreservesIdentityUnknownIconAndUnrelatedFields() =
        runTest {
            val id = created(GroupDraft("Original", colorArgb = 0x123456, iconKey = "future_icon"))
            val original = assertNotNull(db.groupDao().groupById(id))
            assertEquals(0xFF123456.toInt(), original.colorArgb)
            clock.nowMs++
            repo.observeGroup(id).test {
                assertEquals("Original", awaitItem()?.name)
                assertEquals(Outcome.Success(Unit), repo.update(id, GroupEdit.Rename("Renamed")))
                assertEquals("Renamed", awaitItem()?.name)
                repo.update(id, GroupEdit.Orders(FeedOrder.OLDEST_FIRST, FeedOrder.OLDEST_FIRST))
                assertEquals(FeedOrder.OLDEST_FIRST, awaitItem()?.playOrder)
                repo.update(id, GroupEdit.View(3, MediaFilter.VIDEO, 7))
                assertEquals(MediaFilter.VIDEO, awaitItem()?.mediaFilter)
                repo.update(id, GroupEdit.ShowAsTab(false))
                assertFalse(assertNotNull(awaitItem()).showAsTab)
            }
            val edited = assertNotNull(db.groupDao().groupById(id))
            assertEquals(
                original.copy(
                    name = "Renamed",
                    nameKey = "renamed",
                    feedOrder = FeedOrder.OLDEST_FIRST,
                    playOrder = FeedOrder.OLDEST_FIRST,
                    filterFlags = 3,
                    mediaFilter = MediaFilter.VIDEO,
                    hideOlderThanDays = 7,
                    showAsTab = false,
                    updatedAt = clock.now(),
                ),
                edited,
            )
            repo.update(id, GroupEdit.Look(0x123456, "another_future_icon"))
            assertEquals("another_future_icon", repo.observeGroup(id).first()?.iconKey)
            repo.update(id, GroupEdit.Look(null, null))
            assertNull(repo.observeGroup(id).first()?.colorArgb)
            assertNull(repo.observeGroup(id).first()?.iconKey)
            assertEquals(original.uuid, repo.observeGroup(id).first()?.uuid)
        }

    @Test fun membershipReadsAndCountsObserveExistingDaoMemberships() =
        runTest {
            val first = created(GroupDraft("First"))
            val next = created(GroupDraft("Next"))
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            repo.observeGroupsOf(podcast).test {
                assertEquals(emptyList(), awaitItem())
                db.groupDao().insertMember(memberEntity(next, podcast, "a"))
                assertEquals(listOf(next), awaitItem().map { it.id })
                db.groupDao().insertMember(memberEntity(first, podcast, "a"))
                val groups = awaitItem()
                assertEquals(listOf(first, next), groups.map { it.id })
                assertEquals(listOf(1, 1), groups.map { it.memberCount })
                assertEquals(setOf(podcast), repo.observeMemberIds(first).first())
            }
            assertEquals(emptySet(), repo.observeMemberIds(-1).first())
        }

    @Test fun createSeedsInitialMembersWithIncreasingKeys() =
        runTest {
            val p1 = db.podcastDao().insertPodcast(podcastEntity())
            val p2 = db.podcastDao().insertPodcast(podcastEntity())
            val tech = created(GroupDraft("Tech"), setOf(p1, p2))
            val members = db.groupDao().membersOf(tech)
            assertEquals(setOf(p1, p2), members.mapTo(HashSet()) { it.podcastId })
            assertEquals(listOf("0.5", "0.75"), members.map { it.orderKey }.sorted())
            assertEquals(2, repo.observeGroup(tech).first()?.memberCount)
            assertEquals(mapOf(p1 to setOf(tech), p2 to setOf(tech)), repo.observeMemberships().first())
        }

    @Test fun overlappingMembershipsAndSetMembersWritesOnlyChangedRows() =
        runTest {
            val p1 = db.podcastDao().insertPodcast(podcastEntity())
            val p2 = db.podcastDao().insertPodcast(podcastEntity())
            val p3 = db.podcastDao().insertPodcast(podcastEntity())
            val tech = created(GroupDraft("Tech"), setOf(p1, p2))
            val news = created(GroupDraft("News"), setOf(p2))
            val memberships = repo.observeMemberships().first()
            assertEquals(setOf(tech, news), memberships.getValue(p2))
            assertEquals(setOf(tech), memberships.getValue(p1))

            val kept = db.groupDao().membersOf(tech).single { it.podcastId == p2 }
            val newsStamp = db.groupDao().groupById(news)?.updatedAt
            clock.nowMs++
            assertEquals(Outcome.Success(Unit), repo.setMembers(tech, setOf(p2, p3)))
            val members = db.groupDao().membersOf(tech)
            assertEquals(setOf(p2, p3), members.mapTo(HashSet()) { it.podcastId })
            // The kept row was not rewritten and the new member appends after the last key.
            assertEquals(kept, members.single { it.podcastId == p2 })
            assertTrue(members.single { it.podcastId == p3 }.orderKey > kept.orderKey)
            // Only the edited group is stamped.
            assertEquals(clock.now(), db.groupDao().groupById(tech)?.updatedAt)
            assertEquals(newsStamp, db.groupDao().groupById(news)?.updatedAt)
        }

    @Test fun membershipNoOpsAndUnknownIdsDoNotWrite() =
        runTest {
            val p1 = db.podcastDao().insertPodcast(podcastEntity())
            val tech = created(GroupDraft("Tech"), setOf(p1))
            val before = db.groupDao().groupById(tech)
            clock.nowMs++
            assertEquals(Outcome.Success(Unit), repo.setMembers(tech, setOf(p1)))
            assertEquals(Outcome.Success(Unit), repo.applyMembership(setOf(p1), add = setOf(tech), remove = emptySet()))
            assertEquals(before, db.groupDao().groupById(tech))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.setMembers(-1, setOf(p1)))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.setMembers(tech, setOf(-1)))
            assertEquals(
                Outcome.Failure(GroupError.NotFound),
                repo.applyMembership(setOf(-1), add = emptySet(), remove = emptySet()),
            )
            assertEquals(
                Outcome.Failure(GroupError.NotFound),
                repo.applyMembership(setOf(p1), add = emptySet(), remove = setOf(99)),
            )
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.create(GroupDraft("X"), setOf(-1)))
        }

    @Test fun applyMembershipRemovesThenAddsAndStampsOnlyChangedGroups() =
        runTest {
            val p1 = db.podcastDao().insertPodcast(podcastEntity())
            val tech = created(GroupDraft("Tech"), setOf(p1))
            val news = created(GroupDraft("News"))
            val techStamp = db.groupDao().groupById(tech)?.updatedAt
            val newsStamp = db.groupDao().groupById(news)?.updatedAt
            clock.nowMs++
            assertEquals(
                Outcome.Success(Unit),
                repo.applyMembership(setOf(p1), add = setOf(tech, news), remove = emptySet()),
            )
            assertEquals(setOf(tech, news), repo.observeMemberships().first().getValue(p1))
            assertEquals(techStamp, db.groupDao().groupById(tech)?.updatedAt)
            assertEquals(clock.now(), db.groupDao().groupById(news)?.updatedAt)
            // A group in both sets is removed first, then re-added — the row is rewritten.
            val before = db.groupDao().membersOf(news).single { it.podcastId == p1 }
            clock.nowMs++
            assertEquals(
                Outcome.Success(Unit),
                repo.applyMembership(setOf(p1), add = setOf(news), remove = setOf(news)),
            )
            val after = db.groupDao().membersOf(news).single { it.podcastId == p1 }
            assertTrue(after.addedAt > before.addedAt)
            // Removing the last membership makes the podcast ungrouped.
            assertEquals(
                Outcome.Success(Unit),
                repo.applyMembership(setOf(p1), add = emptySet(), remove = setOf(tech, news)),
            )
            assertEquals(emptyMap(), repo.observeMemberships().first().filterValues { it.isNotEmpty() })
            assertEquals(emptySet(), repo.observeMemberIds(tech).first())
        }

    @Test fun repeatedEditsDoNotAdvanceUpdatedAt() =
        runTest {
            val id = created(GroupDraft("Name"))
            val before = assertNotNull(db.groupDao().groupById(id))
            clock.nowMs++
            repo.update(id, GroupEdit.Rename("Name"))
            repo.update(id, GroupEdit.Look(before.colorArgb, null))
            repo.update(id, GroupEdit.Orders(before.feedOrder, before.playOrder))
            repo.update(id, GroupEdit.View(0, MediaFilter.ALL, null))
            repo.update(id, GroupEdit.ShowAsTab(true))
            assertEquals(before, db.groupDao().groupById(id))
        }

    @Test fun unrelatedStorageFailuresPropagateRatherThanBecomingNameTaken() =
        runTest {
            created(GroupDraft("Tech"))
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER fail_group BEFORE INSERT ON podcast_group" +
                        " BEGIN SELECT RAISE(ABORT, 'disk fault'); END",
                )
            }
            var thrown: Throwable? = null
            try {
                repo.create(GroupDraft("Tech"))
            } catch (error: Exception) {
                thrown = error
            }
            assertNotNull(thrown)
            assertTrue(thrown.message.orEmpty().contains("disk fault"))
            assertEquals(1, repo.observeGroups().first().size)
        }

    private suspend fun created(
        draft: GroupDraft,
        memberIds: Set<Long> = emptySet(),
    ): Long = assertIs<Outcome.Success<Long>>(repo.create(draft, memberIds)).value
}
