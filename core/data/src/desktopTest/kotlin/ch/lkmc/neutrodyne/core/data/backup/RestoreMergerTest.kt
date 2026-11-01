// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.FakeRefreshScheduler
import ch.lkmc.neutrodyne.core.data.TestOrderKeys
import ch.lkmc.neutrodyne.core.data.execSql
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.newUnsubscribe
import ch.lkmc.neutrodyne.core.data.repo.GroupMerger
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.StoredSecret
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeDownloadLifecycle
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.backup.CredentialV1
import ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1
import ch.lkmc.neutrodyne.feeds.backup.GroupV1
import ch.lkmc.neutrodyne.feeds.backup.LibraryV1
import ch.lkmc.neutrodyne.feeds.backup.MemberV1
import ch.lkmc.neutrodyne.feeds.backup.OverridesV1
import ch.lkmc.neutrodyne.feeds.backup.PodcastV1
import ch.lkmc.neutrodyne.feeds.backup.QueueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * 05's `RestoreMerger` library phase (the [Merge and Replace rules] table's first two columns)
 * over the bundled driver: podcasts matched or inserted pending with their `syncId`, aliases,
 * groups matched by `uuid` then `nameKey` (with name swaps parked), memberships, the scope
 * settings rows, credentials under `CredentialCommitCoordinator`, and replay idempotence.
 * Episode lines, queue and session are B11's phase; the `LINK_*` policies are refused.
 */
class RestoreMergerTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val secrets = FakeSecretStore()
    private val coordinator = FakeCredentialCommitCoordinator()
    private val channels = FakeGroupChannelSync()
    private val scheduler = FakeRefreshScheduler()
    private val downloads = FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) }
    private val merger =
        RestoreMerger(
            db = db,
            podcasts = PodcastMatcher(db),
            writes = RestoreWrites(db),
            groupMerger = GroupMerger(db, clock, channels),
            unsubscribe = newUnsubscribe(db, scheduler, clock, secrets, coordinator),
            secrets = secrets,
            coordinator = coordinator,
            channelSync = channels,
            orderKeys = TestOrderKeys,
            clock = clock,
            downloads =
                Provider { downloads },
        )

    @AfterTest fun close() = db.close()

    // --- Policies --------------------------------------------------------------------------------

    @Test
    fun linkPoliciesFailWithoutWriting() =
        runTest {
            val staged = stagedOf(podcasts = listOf(podcastV1("k-1")))
            for (policy in listOf(MergePolicy.LINK_MERGE, MergePolicy.LINK_REPLACE)) {
                assertFailsWith<IllegalArgumentException> {
                    merger.mergeLibrary(staged, policy, ALL_CATEGORIES)
                }
            }
            assertTrue(db.podcastDao().allForExport().isEmpty())
            assertTrue(db.groupDao().groupsInOrder().isEmpty())
        }

    // --- Podcasts ----------------------------------------------------------------------------------

    @Test
    fun mergeInsertsPendingPodcastWithBackupIdentity() =
        runTest {
            val syncId = Uuid.random().toString()
            val staged =
                stagedOf(
                    podcasts =
                        listOf(
                            PodcastV1(
                                key = "https://a/feed",
                                feedUrl = "https://a/feed.xml?token=1",
                                aliases = listOf("https://a/old"),
                                podcastGuid = "urn:guid:a",
                                title = "Show A",
                                customTitle = "My A",
                                artworkUrl = "https://a/art.jpg",
                                link = "https://a",
                                subscribedAt = 42L,
                                includeInAll = false,
                                episodeOrder = "OLDEST_FIRST",
                                syncId = syncId,
                            ),
                        ),
                )

            val summary = merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES).summary

            assertEquals(1, summary.podcastsAdded)
            assertEquals(0, summary.podcastsMatched)
            val row = assertNotNull(db.podcastDao().byFeedKey("https://a/feed"))
            assertEquals(syncId, row.syncId)
            assertEquals(PodcastStatus.PENDING_FIRST_FETCH, row.status)
            assertTrue(row.initialFetch)
            assertEquals(42L, row.subscribedAt)
            assertEquals(clock.now(), row.nextRefreshAt)
            assertEquals("https://a/feed.xml?token=1", row.feedUrl)
            assertEquals("urn:guid:a", row.podcastGuid)
            assertEquals("My A", row.customTitle)
            assertFalse(row.includeInAll)
            assertEquals(FeedOrder.OLDEST_FIRST, row.episodeOrder)
            // D98: a row this build inserts is provenance-covered from insertion on.
            assertEquals(clock.now(), row.guidCoverageSince)

            val alias = assertNotNull(db.podcastDao().aliasOwner("https://a/old"))
            assertEquals(row.id, alias)
        }

    @Test
    fun mergeMatchesByFeedKeyKeepsLocalColumnsAndUnionsAliases() =
        runTest {
            val local =
                db.podcastDao().insertPodcast(
                    podcastEntity(feedKey = "https://a/feed") {
                        copy(customTitle = "Local name", includeInAll = false)
                    },
                )
            val before = db.podcastDao().byId(local)!!
            val staged =
                stagedOf(
                    podcasts =
                        listOf(
                            PodcastV1(
                                key = "https://a/feed",
                                feedUrl = "https://a/other-feed-url",
                                title = "Backup title",
                                customTitle = "Backup name",
                                subscribedAt = 5L,
                                includeInAll = true,
                                aliases = listOf("https://a/feed", "https://a/moved"),
                                syncId = Uuid.random().toString(),
                            ),
                        ),
                )

            val summary = merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES).summary

            assertEquals(0, summary.podcastsAdded)
            assertEquals(1, summary.podcastsMatched)
            val row = assertNotNull(db.podcastDao().byId(local))
            // Merge: local wins on the user columns; nothing feed-derived moved.
            assertEquals("Local name", row.customTitle)
            assertFalse(row.includeInAll)
            assertEquals(before.feedUrl, row.feedUrl)
            assertEquals(PodcastStatus.ACTIVE, row.status)
            // The staged syncId is not adopted onto a matched row (05 IDs: inserts only).
            assertEquals(before.syncId, row.syncId)
            // Aliases union; the one equal to the local feedKey never lands (02's invariant).
            assertEquals(local, db.podcastDao().aliasOwner("https://a/moved"))
            assertNull(db.podcastDao().aliasOwner("https://a/feed"))
        }

    @Test
    fun mergeMatchesByBackupAliasAndRealGuid() =
        runTest {
            val byAlias =
                db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://a/current"))
            val byGuid =
                db.podcastDao().insertPodcast(
                    podcastEntity(feedKey = "https://b/current") { copy(podcastGuid = "urn:real") },
                )
            val staged =
                stagedOf(
                    podcasts =
                        listOf(
                            podcastV1("https://a/old", aliases = listOf("https://a/current")),
                            podcastV1("https://b/unknown", podcastGuid = "urn:real"),
                        ),
                )

            val summary = merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES).summary

            assertEquals(2, summary.podcastsMatched)
            assertEquals(0, summary.podcastsAdded)
            assertEquals(2, db.podcastDao().allForExport().size)
            assertNotNull(db.podcastDao().byId(byAlias))
            assertNotNull(db.podcastDao().byId(byGuid))
        }

    @Test
    fun replaceRewritesMatchedPodcastUserColumns() =
        runTest {
            val local =
                db.podcastDao().insertPodcast(
                    podcastEntity(feedKey = "https://a/feed") {
                        copy(
                            customTitle = "Local name",
                            includeInAll = true,
                            episodeOrder = FeedOrder.NEWEST_FIRST,
                            youtubeVariants = 3,
                            title = "Local title",
                            status = PodcastStatus.ACTIVE,
                        )
                    },
                )
            merger.mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(
                            PodcastV1(
                                key = "https://a/feed",
                                feedUrl = "https://a/feed",
                                title = "Backup title",
                                customTitle = null,
                                subscribedAt = 7L,
                                includeInAll = false,
                                episodeOrder = "OLDEST_FIRST",
                                youtubeVariants = 5,
                            ),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            val row = assertNotNull(db.podcastDao().byId(local))
            // The four user columns take the backup's values verbatim (05) — even to null.
            assertNull(row.customTitle)
            assertFalse(row.includeInAll)
            assertEquals(FeedOrder.OLDEST_FIRST, row.episodeOrder)
            assertEquals(5, row.youtubeVariants)
            // Feed-derived and fetch-state columns stay local — the next refresh owns them.
            assertEquals("Local title", row.title)
            assertEquals(PodcastStatus.ACTIVE, row.status)
        }

    @Test
    fun mergeFillsOnlyAnUnsetCustomTitle() =
        runTest {
            val local =
                db.podcastDao().insertPodcast(
                    podcastEntity(feedKey = "https://a/feed") { copy(customTitle = null) },
                )
            merger.mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(
                            PodcastV1(
                                key = "https://a/feed",
                                feedUrl = "https://a/feed",
                                title = "T",
                                customTitle = "From backup",
                                subscribedAt = 1L,
                            ),
                        ),
                ),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )
            assertEquals("From backup", db.podcastDao().byId(local)!!.customTitle)
        }

    // --- Groups ----------------------------------------------------------------------------------

    @Test
    fun mergeAppendsNewGroupAfterLocalsAndKeepsMatchedLocalLook() =
        runTest {
            val localGroup =
                db.groupDao().insert(groupEntity(uuid = "u-same", name = "Local name", orderKey = "0.5"))
            val staged =
                stagedOf(
                    groups =
                        listOf(
                            groupV1(uuid = "u-same", name = "Backup name", orderKey = "0.9"),
                            groupV1(uuid = "u-new", name = "Fresh", orderKey = "0.1"),
                        ),
                )

            val summary = merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES).summary

            assertEquals(1, summary.groupsMatched)
            assertEquals(1, summary.groupsAdded)
            val local = assertNotNull(db.groupDao().groupById(localGroup))
            // Merge: a matched group keeps local name and order (05 Groups row).
            assertEquals("Local name", local.name)
            assertEquals("0.5", local.orderKey)
            // A new group lands after the locals in backup order, with a fresh uuid (05 IDs:
            // restores adopt a backup uuid only in Replace).
            val fresh = assertNotNull(db.groupDao().groupByNameKey("fresh"))
            assertEquals("0.75", fresh.orderKey)
            assertNotEquals("u-new", fresh.uuid)
        }

    @Test
    fun replaceMatchedGroupTakesBackupValuesAndOrder() =
        runTest {
            val g1 =
                db.groupDao().insert(groupEntity(uuid = "u-1", name = "Tech", orderKey = "0.5"))
            val g2 =
                db.groupDao().insert(groupEntity(uuid = "u-2", name = "News", orderKey = "0.25"))
            realMerger().mergeLibrary(
                stagedOf(
                    groups =
                        listOf(
                            // The file's list order is scrambled against `sortOrder`; valid
                            // ascending keys in rank order still land verbatim.
                            groupV1(uuid = "u-2", name = "News+", sortOrder = 1, orderKey = "a1"),
                            groupV1(uuid = "u-1", name = "Tech+", sortOrder = 0, orderKey = "a0"),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            val one = assertNotNull(db.groupDao().groupById(g1))
            val two = assertNotNull(db.groupDao().groupById(g2))
            assertEquals("Tech+", one.name)
            assertEquals("tech+", one.nameKey)
            assertEquals("a0", one.orderKey)
            assertEquals("News+", two.name)
            assertEquals("a1", two.orderKey)
            // The uuids matched — the rows keep them; no channel carry-over was needed.
            assertEquals("u-1", one.uuid)
            assertEquals(emptyList(), channels.calls)
        }

    @Test
    fun replaceNameKeyMatchAdoptsBackupUuidAndCarriesChannel() =
        runTest {
            val local =
                db.groupDao().insert(groupEntity(uuid = "u-local", name = "Tech", orderKey = "0.5"))
            realMerger().mergeLibrary(
                stagedOf(groups = listOf(groupV1(uuid = "u-backup", name = "tech ", orderKey = "a0"))),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            val row = assertNotNull(db.groupDao().groupById(local))
            // `GroupNames` truncates/empties; "tech " normalises onto the local nameKey.
            assertEquals("u-backup", row.uuid)
            assertEquals(listOf("onUuidChanged(u-local, u-backup)"), channels.calls)
        }

    @Test
    fun replaceSurvivesTwoGroupsSwappingNames() =
        runTest {
            val tech =
                db.groupDao().insert(groupEntity(uuid = "u-1", name = "Tech", orderKey = "0.25"))
            val news =
                db.groupDao().insert(groupEntity(uuid = "u-2", name = "News", orderKey = "0.5"))
            realMerger().mergeLibrary(
                stagedOf(
                    groups =
                        listOf(
                            groupV1(uuid = "u-1", name = "News", orderKey = "a0"),
                            groupV1(uuid = "u-2", name = "Tech", orderKey = "a1"),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // The parked nameKeys let each rewrite land; the names are swapped exactly.
            assertEquals("News", db.groupDao().groupById(tech)!!.name)
            assertEquals("Tech", db.groupDao().groupById(news)!!.name)
            assertEquals(2, db.groupDao().groupsInOrder().size)
        }

    @Test
    fun stagedGroupsSharingOneNameKeyFoldIntoTheFirst() =
        runTest {
            val loserUuid = Uuid.random().toString()
            val local =
                db.groupDao().insert(groupEntity(uuid = loserUuid, name = "Local", orderKey = "0.5"))
            val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://p/feed"))
            db.groupDao().insertMember(memberEntity(local, p, "0.5"))
            val staged =
                stagedOf(
                    podcasts = listOf(podcastV1("https://p/feed")),
                    groups =
                        listOf(
                            groupV1(uuid = "u-a", name = "News", members = listOf("https://p/feed")),
                            groupV1(uuid = loserUuid, name = "NEWS", members = listOf("https://p/feed")),
                        ),
                )

            merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES)

            // Both staged groups folded into the first; its nameKey matched no local row so it
            // inserted — then the folded group's local row merged into it via `GroupMerger`.
            val groups = db.groupDao().groupsInOrder()
            assertEquals(1, groups.size)
            assertEquals("News", groups.single().name)
            assertEquals(listOf(p), db.groupDao().membersOf(groups.single().id).map { it.podcastId })
            assertNull(db.groupDao().groupById(local))
            assertEquals(listOf("sync()"), channels.calls)
        }

    @Test
    fun stagedGroupsSharingOneUuidFoldIntoTheFirst() =
        runTest {
            merger.mergeLibrary(
                stagedOf(
                    podcasts = listOf(podcastV1("https://p1/feed"), podcastV1("https://p2/feed")),
                    groups =
                        listOf(
                            groupV1(uuid = "u-dup", name = "News", members = listOf("https://p1/feed")),
                            groupV1(uuid = "u-dup", name = "Other", members = listOf("https://p2/feed")),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // A second insert of `u-dup` cannot land on the unique index — the later group
            // folds into the first exactly like a shared nameKey (05).
            val groups = db.groupDao().groupsInOrder()
            assertEquals(1, groups.size)
            assertEquals("News", groups.single().name)
            val memberIds =
                db
                    .groupDao()
                    .membersOf(groups.single().id)
                    .map { it.podcastId }
                    .toSet()
            assertEquals(
                setOf(
                    db.podcastDao().byFeedKey("https://p1/feed")!!.id,
                    db.podcastDao().byFeedKey("https://p2/feed")!!.id,
                ),
                memberIds,
            )
        }

    @Test
    fun duplicateUuidStagedGroupClaimsALocalRowByNameKey() =
        runTest {
            val pLocal = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://pl/feed"))
            val p1Local = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://p1/feed"))
            val local =
                db.groupDao().insert(groupEntity(uuid = "u-x", name = "Local", orderKey = "a0"))
            db.groupDao().insertMember(memberEntity(local, pLocal, "a0"))

            realMerger().mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(podcastV1("https://p1/feed"), podcastV1("https://pl/feed")),
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-dup",
                                name = "News",
                                sortOrder = 0,
                                members = listOf("https://p1/feed"),
                            ),
                            groupV1(
                                uuid = "u-dup",
                                name = "Local",
                                sortOrder = 1,
                                members = listOf("https://pl/feed"),
                            ),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // The duplicate folds into the first; its nameKey still claims the local row,
            // which merges into the survivor post-commit — one group, members united.
            val groups = db.groupDao().groupsInOrder()
            assertEquals(1, groups.size)
            val survivor = groups.single()
            assertEquals("News", survivor.name)
            assertEquals("u-dup", survivor.uuid)
            assertEquals(
                setOf(p1Local, pLocal),
                db
                    .groupDao()
                    .membersOf(survivor.id)
                    .map { it.podcastId }
                    .toSet(),
            )
            assertNull(db.groupDao().groupById(local))
            assertEquals(listOf("sync()"), channels.calls)
        }

    @Test
    fun stagedGroupNameCannotCollideWithAParkedNameKey() =
        runTest {
            val local =
                db.groupDao().insert(groupEntity(uuid = "u-1", name = "Tech", orderKey = "a0"))
            realMerger().mergeLibrary(
                stagedOf(
                    groups =
                        listOf(
                            // A legal staged name — `GroupNames` keeps the tildes — that lands
                            // on the parked row's sentinel shape.
                            groupV1(uuid = "u-9", name = "~restore~$local", sortOrder = 0),
                            groupV1(uuid = "u-1", name = "News", sortOrder = 1),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            val groups = db.groupDao().groupsInOrder()
            assertEquals(listOf("~restore~$local", "News"), groups.map { it.name })
        }

    // --- Memberships ----------------------------------------------------------------------------

    @Test
    fun mergeUnionsMembershipsKeepingLocalOrder() =
        runTest {
            val p1 = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://p1/feed"))
            val p2 = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://p2/feed"))
            val pLocal = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://pl/feed"))
            val group = db.groupDao().insert(groupEntity(uuid = "u-1", name = "G", orderKey = "0.5"))
            db.groupDao().insertMember(memberEntity(group, p1, "0.5"))
            db.groupDao().insertMember(memberEntity(group, pLocal, "0.75"))
            val updatedAtBefore = db.groupDao().groupById(group)!!.updatedAt

            merger.mergeLibrary(
                stagedOf(
                    podcasts = listOf(podcastV1("https://p1/feed"), podcastV1("https://p2/feed")),
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-1",
                                name = "G",
                                members = listOf("https://p1/feed", "https://p2/feed"),
                            ),
                        ),
                ),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )

            val members = db.groupDao().membersOf(group).associateBy { it.podcastId }
            // Union: the backup adds p2, the local-only member survives, p1 keeps its key.
            assertEquals(setOf(p1, p2, pLocal), members.keys)
            assertEquals("0.5", members.getValue(p1).orderKey)
            assertEquals("0.875", members.getValue(p2).orderKey)
            assertEquals("0.75", members.getValue(pLocal).orderKey)
            // A membership write stamps the group (05 Membership).
            assertEquals(clock.now(), db.groupDao().groupById(group)!!.updatedAt)
            assertTrue(updatedAtBefore <= clock.now())
        }

    @Test
    fun replaceRebuildsMembershipsWithBackupKeys() =
        runTest {
            val p1 = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://p1/feed"))
            val p2 = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://p2/feed"))
            val pLocal = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://pl/feed"))
            val g1 = db.groupDao().insert(groupEntity(uuid = "u-1", name = "G1", orderKey = "0.25"))
            val g2 = db.groupDao().insert(groupEntity(uuid = "u-2", name = "G2", orderKey = "0.5"))
            db.groupDao().insertMember(memberEntity(g1, p1, "0.1"))
            db.groupDao().insertMember(memberEntity(g1, pLocal, "0.2"))
            db.groupDao().insertMember(memberEntity(g2, p1, "0.3"))

            realMerger().mergeLibrary(
                stagedOf(
                    podcasts = listOf(podcastV1("https://p1/feed"), podcastV1("https://p2/feed")),
                    groups =
                        listOf(
                            GroupV1(
                                uuid = "u-1",
                                name = "G1",
                                sortOrder = 0,
                                orderKey = "a0",
                                members =
                                    listOf(
                                        MemberV1("https://p1/feed", addedAt = 3L, orderKey = "a0"),
                                        MemberV1("https://p2/feed", addedAt = 5L, orderKey = "a1"),
                                    ),
                            ),
                            groupV1(uuid = "u-2", name = "G2", sortOrder = 1, orderKey = "a1"),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // Exactly the backup's: pLocal's row is gone (unsubscribed with its podcast… no —
            // unsubscribed means the row cascaded; here Replace also drops it from G1's list),
            // G2 is empty, and the staged keys land verbatim.
            val m1 = db.groupDao().membersOf(g1).associateBy { it.podcastId }
            assertEquals(setOf(p1, p2), m1.keys)
            assertEquals("a0", m1.getValue(p1).orderKey)
            assertEquals("a1", m1.getValue(p2).orderKey)
            assertEquals(3L, m1.getValue(p1).addedAt)
            assertTrue(db.groupDao().membersOf(g2).isEmpty())
        }

    // --- Order keys on Replace (05 transaction A) ------------------------------------------------
    //
    // A foreign `orderKey`/`ok` must neither persist (it poisons every later `after`/`between`
    // that reads it) nor reach the generator as a bound (it throws, aborting the restore).
    // These run on `ProtocolOrderKeys` — `TestOrderKeys` accepts decimal strings a real
    // archive can never carry.

    @Test
    fun replaceRewritesGroupKeysWhenAStagedKeyIsMalformed() =
        runTest {
            realMerger().mergeLibrary(
                stagedOf(
                    groups =
                        listOf(
                            groupV1(uuid = "u-a", name = "A", sortOrder = 0, orderKey = "a0"),
                            groupV1(uuid = "u-b", name = "B", sortOrder = 1, orderKey = "!!!"),
                            groupV1(uuid = "u-c", name = "C", sortOrder = 2, orderKey = "a1"),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // Every slot was non-null, so the malformed key would have persisted verbatim;
            // instead the whole list rewrites in `sortOrder` rank (05).
            val groups = db.groupDao().groupsInOrder()
            assertEquals(listOf("A", "B", "C"), groups.map { it.name })
            assertEquals(listOf("a0", "a1", "a2"), groups.map { it.orderKey })
        }

    @Test
    fun replaceRewritesGroupKeysInsteadOfFeedingTheGeneratorAMalformedBound() =
        runTest {
            realMerger().mergeLibrary(
                stagedOf(
                    groups =
                        listOf(
                            groupV1(uuid = "u-a", name = "A", sortOrder = 0, orderKey = "!!!"),
                            groupV1(uuid = "u-b", name = "B", sortOrder = 1),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // The old filler stored "!!!" then called `after("!!!")` for the missing slot —
            // a throw inside the write transaction. Both rows land on fresh keys instead.
            assertEquals(listOf("a0", "a1"), db.groupDao().groupsInOrder().map { it.orderKey })
        }

    @Test
    fun replaceRewritesGroupKeysWhenStagedKeysDescendOrRepeat() =
        runTest {
            val merger = realMerger()
            merger.mergeLibrary(
                stagedOf(
                    groups =
                        listOf(
                            groupV1(uuid = "u-a", name = "A", sortOrder = 0, orderKey = "a1"),
                            groupV1(uuid = "u-b", name = "B", sortOrder = 1, orderKey = "a0"),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // Verbatim descending keys would have ordered the list B, A — not the backup's.
            var groups = db.groupDao().groupsInOrder()
            assertEquals(listOf("A", "B"), groups.map { it.name })
            assertEquals(listOf("a0", "a1"), groups.map { it.orderKey })

            merger.mergeLibrary(
                stagedOf(
                    groups =
                        listOf(
                            groupV1(uuid = "u-a", name = "A", sortOrder = 0, orderKey = "a0"),
                            groupV1(uuid = "u-b", name = "B", sortOrder = 1, orderKey = "a0"),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // Equal keys cannot persist either — 10's equal-key rule is a rewrite trigger.
            groups = db.groupDao().groupsInOrder()
            assertEquals(2, groups.map { it.orderKey }.distinct().size)
        }

    @Test
    fun replaceAssignsRewrittenGroupKeysInSortOrderRank() =
        runTest {
            // The file's list order is not its rank order; the rewrite still follows
            // `sortOrder`, not the line order.
            realMerger().mergeLibrary(
                stagedOf(
                    groups =
                        listOf(
                            groupV1(uuid = "u-b", name = "B", sortOrder = 1),
                            groupV1(uuid = "u-a", name = "A", sortOrder = 0),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            assertEquals(listOf("A", "B"), db.groupDao().groupsInOrder().map { it.name })
        }

    @Test
    fun replaceRewritesMemberKeysWhenAStagedKeyIsMalformed() =
        runTest {
            realMerger().mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(
                            podcastV1("https://p1/feed"),
                            podcastV1("https://p2/feed"),
                            podcastV1("https://p3/feed"),
                        ),
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-g",
                                name = "G",
                                members =
                                    listOf("https://p1/feed", "https://p2/feed", "https://p3/feed"),
                                memberKeys =
                                    mapOf(
                                        "https://p1/feed" to "a0",
                                        "https://p2/feed" to "!!!",
                                        "https://p3/feed" to "a1",
                                    ),
                            ),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            val group = assertNotNull(db.groupDao().groupByNameKey("g"))
            val keys =
                db.groupDao().membersOf(group.id).associate {
                    db.podcastDao().byId(it.podcastId)!!.feedKey to it.orderKey
                }
            // rewrite(3) in the members' file order — "!!!" never persisted.
            assertEquals("a0", keys.getValue("https://p1/feed"))
            assertEquals("a1", keys.getValue("https://p2/feed"))
            assertEquals("a2", keys.getValue("https://p3/feed"))
        }

    @Test
    fun replaceRewritesMemberKeysInsteadOfFeedingTheGeneratorAMalformedBound() =
        runTest {
            realMerger().mergeLibrary(
                stagedOf(
                    podcasts = listOf(podcastV1("https://p1/feed"), podcastV1("https://p2/feed")),
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-g",
                                name = "G",
                                members = listOf("https://p1/feed", "https://p2/feed"),
                                memberKeys = mapOf("https://p1/feed" to "!!!"),
                            ),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            val group = assertNotNull(db.groupDao().groupByNameKey("g"))
            assertEquals(
                listOf("a0", "a1"),
                db.groupDao().membersOf(group.id).map { it.orderKey },
            )
        }

    @Test
    fun replaceRewritesMemberKeysWhenSomeAreMissingOrUnordered() =
        runTest {
            realMerger().mergeLibrary(
                stagedOf(
                    podcasts = (1..4).map { podcastV1("https://p$it/feed") },
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-g1",
                                name = "G1",
                                members = listOf("https://p1/feed", "https://p2/feed"),
                                memberKeys = mapOf("https://p1/feed" to "a5"),
                            ),
                            groupV1(
                                uuid = "u-g2",
                                name = "G2",
                                members = listOf("https://p3/feed", "https://p4/feed"),
                                memberKeys =
                                    mapOf("https://p3/feed" to "a1", "https://p4/feed" to "a0"),
                            ),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // Partially missing (G1) and descending (G2) both rewrite rather than filling
            // between bad bounds or persisting the scramble.
            for (name in listOf("g1", "g2")) {
                val group = assertNotNull(db.groupDao().groupByNameKey(name))
                assertEquals(
                    listOf("a0", "a1"),
                    db.groupDao().membersOf(group.id).map { it.orderKey },
                )
            }
        }

    @Test
    fun rewrittenMemberKeysAcceptFollowUpGeneratorCalls() =
        runTest {
            val merger = realMerger()
            merger.mergeLibrary(
                stagedOf(
                    podcasts = listOf(podcastV1("https://p1/feed")),
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-g",
                                name = "G",
                                members = listOf("https://p1/feed"),
                                memberKeys = mapOf("https://p1/feed" to "!!!"),
                            ),
                        ),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // The stored key is valid, so Merge's `OrderKey.after(last)` works instead of
            // crashing on a poisoned bound.
            merger.mergeLibrary(
                stagedOf(
                    podcasts = listOf(podcastV1("https://p2/feed")),
                    groups =
                        listOf(
                            groupV1(uuid = "u-g", name = "G", members = listOf("https://p2/feed")),
                        ),
                ),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )

            val group = assertNotNull(db.groupDao().groupByNameKey("g"))
            assertEquals(2, db.groupDao().membersOf(group.id).size)
        }

    // --- Replace pre-step ------------------------------------------------------------------------

    @Test
    fun mergeKeepsLocalExtrasReplaceRemovesThemThroughUnsubscribe() =
        runTest {
            val matched = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://m/feed"))
            val only = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://only/feed"))
            val ep = db.episodeDao().insertStub(episodeEntity(podcastId = only, identityKey = "g:x"))
            val matchedGroup = db.groupDao().insert(groupEntity(uuid = "u-1", name = "A", orderKey = "0.25"))
            val onlyGroup = db.groupDao().insert(groupEntity(uuid = "u-9", name = "B", orderKey = "0.5"))
            db.groupDao().insertMember(memberEntity(onlyGroup, only, "0.5"))
            val staged =
                stagedOf(
                    podcasts = listOf(podcastV1("https://m/feed")),
                    groups = listOf(groupV1(uuid = "u-1", name = "A")),
                )

            // Merge keeps the local extras.
            merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES)
            assertNotNull(db.podcastDao().byId(only))
            assertNotNull(db.groupDao().groupById(onlyGroup))

            assertEquals(0, downloads.replacements)
            // Replace unsubscribes the local-only podcast (episodes cascade, D24) and deletes
            // the local-only group without undo; matched rows stay.
            merger.mergeLibrary(staged, MergePolicy.RESTORE_REPLACE, ALL_CATEGORIES)
            assertEquals(1, downloads.replacements)
            assertNotNull(db.podcastDao().byId(matched))
            assertNull(db.podcastDao().byId(only))
            assertNull(db.episodeDao().byId(ep))
            assertNotNull(db.groupDao().groupById(matchedGroup))
            assertNull(db.groupDao().groupById(onlyGroup))
            assertEquals(listOf("onDeleted(u-9)"), channels.calls)
        }

    @Test
    fun replaceHistoryAndUpNextClearLocalRows() =
        runTest {
            val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://m/feed"))
            val e = db.episodeDao().insertStub(episodeEntity(podcastId = p, identityKey = "g:e"))
            db.episodeStateDao().upsert(EpisodeStateEntity(episodeId = e, playedAt = 100, updatedAt = 100))
            db.positionDao().upsert(
                EpisodePositionEntity(
                    episodeId = e,
                    positionMs = 50,
                    positionSource = PositionSource.STREAM,
                    updatedAt = 90,
                ),
            )
            db.queueDao().insert(QueueEntryEntity(episodeId = e, orderKey = "0.5", addedAt = 1))
            db.playSessionDao().ensureNow(10)
            db.useWriterConnection {
                it.execSql(
                    "UPDATE play_session SET contextType = 'PODCAST', contextId = $p WHERE id = 0",
                )
            }
            val staged = stagedOf(podcasts = listOf(podcastV1("https://m/feed")))

            // Without the categories the clears do not run.
            merger.mergeLibrary(staged, MergePolicy.RESTORE_REPLACE, emptySet())
            assertNotNull(db.episodeStateDao().byEpisode(e))
            assertNotNull(db.positionDao().byEpisode(e))
            assertEquals(1, db.queueDao().entries().size)

            merger.mergeLibrary(
                staged,
                MergePolicy.RESTORE_REPLACE,
                setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT),
            )
            // The matched podcast's state/position rows go so B11's rows can land verbatim.
            assertNull(db.episodeStateDao().byEpisode(e))
            assertNull(db.positionDao().byEpisode(e))
            assertTrue(db.queueDao().entries().isEmpty())
            assertNull(db.playSessionDao().get()!!.contextType)
        }

    // --- syncId (02 Podcast syncId, AC12) ----------------------------------------------------------

    @Test
    fun syncIdsAdoptWhenFreeRegenerateOnCollisionAndStayUnique() =
        runTest {
            val taken = Uuid.random().toString()
            db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://x/feed", syncId = taken))
            val free = Uuid.random().toString()
            merger.mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(
                            podcastV1("https://a/feed", syncId = free),
                            podcastV1("https://b/feed", syncId = taken),
                            podcastV1("https://c/feed", syncId = null),
                        ),
                ),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )

            val rows = db.podcastDao().allForExport().associateBy { it.feedKey }
            assertEquals(free, rows.getValue("https://a/feed").syncId)
            val regenerated = rows.getValue("https://b/feed").syncId
            assertNotEquals(taken, regenerated)
            val fresh = rows.getValue("https://c/feed").syncId
            assertEquals(
                4,
                rows.values
                    .map { it.syncId }
                    .distinct()
                    .size,
            )
        }

    @Test
    fun syncTablesStayUntouchedByEitherPolicy() =
        runTest {
            db.groupDao().insert(groupEntity(uuid = "u-1", name = "A", orderKey = "0.5"))
            val staged =
                stagedOf(
                    podcasts = listOf(podcastV1("https://a/feed", syncId = Uuid.random().toString())),
                    groups = listOf(groupV1(uuid = "u-1", name = "A"), groupV1(uuid = "u-2", name = "B")),
                )
            for (policy in listOf(MergePolicy.RESTORE_MERGE, MergePolicy.RESTORE_REPLACE)) {
                merger.mergeLibrary(staged, policy, ALL_CATEGORIES)
                assertEquals(0, db.syncOutboxDao().count())
                assertEquals(0, db.syncClockDao().count())
                assertEquals(0, db.syncParkedDao().count())
                assertEquals(0, db.syncHeldDao().count())
                // An unlinked device keeps the onCreate default row.
                assertEquals(SyncStateEntity(), db.syncStateDao().get())
            }
        }

    // --- Settings rows ----------------------------------------------------------------------------

    @Test
    fun mergeKeepsLocalOverrideColumnsAndFillsUnset() =
        runTest {
            val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://a/feed"))
            val g = db.groupDao().insert(groupEntity(uuid = "u-1", name = "G", orderKey = "0.5"))
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(p, ScopeOverrides(playbackSpeed = 2.0f)),
            )
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(g, ScopeOverrides(notifyNewEpisodes = true)),
            )
            merger.mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(
                            podcastV1(
                                "https://a/feed",
                                settings = OverridesV1(playbackSpeed = 1.0f, skipSilence = true),
                            ),
                        ),
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-1",
                                name = "G",
                                settings = OverridesV1(notifyNewEpisodes = false, boostDb = 3.0f),
                            ),
                        ),
                ),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )

            // Per column: local non-null wins, else the backup's (05).
            assertEquals(
                ScopeOverrides(playbackSpeed = 2.0f, skipSilence = true),
                db.scopeSettingsDao().forPodcast(p)?.o,
            )
            assertEquals(
                ScopeOverrides(notifyNewEpisodes = true, boostDb = 3.0f),
                db.scopeSettingsDao().forGroup(g)?.o,
            )
        }

    @Test
    fun replaceWritesTheBackupSettingsRowVerbatim() =
        runTest {
            val p = db.podcastDao().insertPodcast(podcastEntity(feedKey = "https://a/feed"))
            val g = db.groupDao().insert(groupEntity(uuid = "u-1", name = "G", orderKey = "0.5"))
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(p, ScopeOverrides(playbackSpeed = 2.0f, skipSilence = true)),
            )
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(g, ScopeOverrides(notifyNewEpisodes = true)),
            )
            merger.mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(podcastV1("https://a/feed", settings = OverridesV1(boostDb = 4.0f))),
                    groups = listOf(groupV1(uuid = "u-1", name = "G", settings = null)),
                ),
                MergePolicy.RESTORE_REPLACE,
                ALL_CATEGORIES,
            )

            // The backup row replaces the local row; a backup without settings deletes it.
            assertEquals(ScopeOverrides(boostDb = 4.0f), db.scopeSettingsDao().forPodcast(p)?.o)
            assertNull(db.scopeSettingsDao().forGroup(g))
        }

    /**
     * 05 Rules 4 (W4): an `OverridesV1` speed outside the 0.5–3.0/0.05 grid drops before persistence;
     * the effective value inherits and valid speeds round-trip verbatim.
     */
    @Test
    fun outOfRangeScopeSpeedsDropBeforePersistence() =
        runTest {
            merger.mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(
                            podcastV1(
                                "https://a/feed",
                                settings = OverridesV1(playbackSpeed = 9.9f, skipSilence = true),
                            ),
                            podcastV1("https://b/feed", settings = OverridesV1(playbackSpeed = 1.5f)),
                        ),
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-1",
                                name = "News",
                                members = listOf("https://a/feed"),
                                settings = OverridesV1(playbackSpeed = 1.25f),
                            ),
                        ),
                ),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )

            val resolver = EffectiveSettingsResolverImpl(db, FakeSettingsRepository())
            val a = assertNotNull(db.podcastDao().byFeedKey("https://a/feed")).id
            val b = assertNotNull(db.podcastDao().byFeedKey("https://b/feed")).id
            val group = db.groupDao().groupsInOrder().single()
            val groupId = group.id

            assertNull(
                db
                    .scopeSettingsDao()
                    .forPodcast(a)
                    ?.o
                    ?.playbackSpeed,
            )
            assertEquals(
                true,
                db
                    .scopeSettingsDao()
                    .forPodcast(a)
                    ?.o
                    ?.skipSilence,
            )
            assertEquals(
                1.5f,
                db
                    .scopeSettingsDao()
                    .forPodcast(b)
                    ?.o
                    ?.playbackSpeed,
            )

            // a's invalid 9.9 drops: the valid context-group override takes over (dropped = inherit).
            assertEquals(
                Effective(1.25f, SettingSource.Group(group.uuid, "News")),
                resolver.playback(a, groupId).speed,
            )
            assertEquals(
                Effective(true, SettingSource.PodcastOverride),
                resolver.playback(a, groupId).skipSilence,
            )
            // With no context group the invalid speed drops straight to the global default.
            assertEquals(Effective(1.0f, SettingSource.AppDefault), resolver.playback(a, null).speed)
            // A valid scope speed restores verbatim and wins the chain.
            assertEquals(Effective(1.5f, SettingSource.PodcastOverride), resolver.playback(b, groupId).speed)
        }

    // --- Credentials ------------------------------------------------------------------------------

    @Test
    fun podcastWithoutRestoredCredentialIsMarkedNeedsCredentials() =
        runTest {
            merger.mergeLibrary(
                stagedOf(
                    podcasts = listOf(podcastV1("https://a/feed", credentialOrigin = "https://a")),
                ),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )
            val row = assertNotNull(db.podcastDao().byFeedKey("https://a/feed"))
            assertTrue(row.needsCredentials)
            assertNull(row.credentialId)
        }

    @Test
    fun credentialsRestoreThroughTheCoordinatorBeforeTheCommit() =
        runTest {
            // FakeSecretStore's row ids are synthetic; the real credential table needs the rows
            // so `podcast.credentialId`'s FK holds — seed them (ids 1, 2 = the fake's ids).
            seedCredentialRow("https://a")
            val events = mutableListOf<String>()
            val orderedSecrets =
                object : SecretStore by secrets {
                    override suspend fun put(
                        origin: String,
                        username: String,
                        secret: CharArray,
                        attributes: Map<String, String>,
                    ): Long? {
                        events += "put:$origin"
                        return secrets.put(origin, username, secret, attributes)
                    }
                }
            val orderedCoordinator =
                object : CredentialCommitCoordinator {
                    override suspend fun <T> withCredentialCommit(block: suspend () -> T): T {
                        events += "commit:enter"
                        return try {
                            block()
                        } finally {
                            events += "commit:exit"
                        }
                    }
                }
            val ordered =
                merger.copy(
                    secrets = orderedSecrets,
                    coordinator = orderedCoordinator,
                )

            ordered.mergeLibrary(
                stagedOf(
                    podcasts =
                        listOf(
                            podcastV1("https://a/feed", credentialOrigin = "https://a"),
                            podcastV1("https://b/feed"),
                        ),
                    credentials = listOf(CredentialV1("https://a", "user", "pass")),
                ),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )

            // The put ran inside the commit window, before transaction A's `credentialId` write.
            assertEquals(listOf("commit:enter", "put:https://a", "commit:exit"), events)
            val stored = assertNotNull(secrets.get("https://a"))
            assertEquals("user", stored.username)
            assertEquals("pass", stored.secret.concatToString())
            val row = assertNotNull(db.podcastDao().byFeedKey("https://a/feed"))
            assertEquals(1L, row.credentialId)
            assertFalse(row.needsCredentials)
            assertFalse(assertNotNull(db.podcastDao().byFeedKey("https://b/feed")).needsCredentials)
        }

    @Test
    fun mergeKeepsAnExistingLocalCredentialReplaceRewritesIt() =
        runTest {
            secrets.put("https://a", "old-user", "old-pass".toCharArray())
            // The put returned fake row id 1; the matching real row makes the FK reference valid.
            seedCredentialRow("https://a", id = 1)
            val staged =
                stagedOf(
                    podcasts = listOf(podcastV1("https://a/feed", credentialOrigin = "https://a")),
                    credentials = listOf(CredentialV1("https://a", "new-user", "new-pass")),
                )

            merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES)
            // Merge: only origins without a local credential are written (05).
            assertEquals("old-user", secrets.get("https://a")!!.username)
            assertEquals(1L, db.podcastDao().byFeedKey("https://a/feed")!!.credentialId)

            merger.mergeLibrary(staged, MergePolicy.RESTORE_REPLACE, ALL_CATEGORIES)
            // Replace rewrites per origin; the existing row's id survives (02's putByOrigin).
            assertEquals("new-user", secrets.get("https://a")!!.username)
        }

    @Test
    fun noStagedCredentialsMeansNoSecretStoreWrites() =
        runTest {
            val reads = mutableListOf<String>()
            val counting =
                object : SecretStore by secrets {
                    override suspend fun put(
                        origin: String,
                        username: String,
                        secret: CharArray,
                        attributes: Map<String, String>,
                    ): Long? {
                        reads += "put"
                        return secrets.put(origin, username, secret, attributes)
                    }

                    override suspend fun get(origin: String): StoredSecret? {
                        reads += "get"
                        return secrets.get(origin)
                    }
                }
            merger.copy(secrets = counting).mergeLibrary(
                stagedOf(podcasts = listOf(podcastV1("https://a/feed", credentialOrigin = "https://a"))),
                MergePolicy.RESTORE_MERGE,
                ALL_CATEGORIES,
            )
            // No `put` without staged credentials; the credentialOrigin flag still marks the row.
            assertEquals(emptyList(), reads.filter { it == "put" })
            assertTrue(db.podcastDao().byFeedKey("https://a/feed")!!.needsCredentials)
        }

    // --- Replay -------------------------------------------------------------------------------------

    @Test
    fun replayingTheSameStagedLibraryChangesNothing() =
        runTest {
            val staged =
                stagedOf(
                    podcasts =
                        listOf(
                            PodcastV1(
                                key = "https://a/feed",
                                feedUrl = "https://a/feed",
                                aliases = listOf("https://a/old"),
                                title = "A",
                                subscribedAt = 1L,
                                syncId = Uuid.random().toString(),
                                settings = OverridesV1(skipSilence = true),
                            ),
                        ),
                    groups =
                        listOf(
                            groupV1(
                                uuid = "u-1",
                                name = "G",
                                members = listOf("https://a/feed"),
                            ),
                        ),
                )
            merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES)
            val first = libraryShape()

            val again = merger.mergeLibrary(staged, MergePolicy.RESTORE_MERGE, ALL_CATEGORIES).summary

            assertEquals(0, again.podcastsAdded)
            assertEquals(0, again.groupsAdded)
            assertEquals(1, again.podcastsMatched)
            assertEquals(1, again.groupsMatched)
            assertEquals(first, libraryShape())
        }

    @Test
    fun replaceIsAlsoIdempotent() =
        runTest {
            val staged =
                stagedOf(
                    podcasts = listOf(podcastV1("https://a/feed", syncId = Uuid.random().toString())),
                    groups = listOf(groupV1(uuid = "u-1", name = "G", members = listOf("https://a/feed"))),
                )
            merger.mergeLibrary(staged, MergePolicy.RESTORE_REPLACE, ALL_CATEGORIES)
            val first = libraryShape()
            merger.mergeLibrary(staged, MergePolicy.RESTORE_REPLACE, ALL_CATEGORIES)
            assertEquals(first, libraryShape())
        }

    // --- Fixture -----------------------------------------------------------------------------------

    /** A `credential` row the fake store's synthetic ids can point at (FK integrity). */
    private suspend fun seedCredentialRow(
        origin: String,
        id: Long = 1,
    ) {
        db.credentialDao().insert(
            CredentialEntity(
                id = id,
                origin = origin,
                username = "u",
                secretCipher = null,
                iv = null,
                createdAt = 1,
            ),
        )
    }

    /** A comparable snapshot of every table the library phase may write. */
    private suspend fun libraryShape(): List<Any?> {
        val podcasts = db.podcastDao().allForExport().sortedBy { it.feedKey }
        val groups = db.groupDao().groupsInOrder()
        return listOf(
            podcasts,
            groups,
            groups.map { g ->
                db.groupDao().membersOf(g.id).map { m -> Triple(m.podcastId, m.orderKey, m.addedAt) }
            },
            countSql("SELECT COUNT(*) FROM podcast_url_alias"),
            podcasts.map { db.scopeSettingsDao().forPodcast(it.id) },
            groups.map { db.scopeSettingsDao().forGroup(it.id) },
        )
    }

    private suspend fun countSql(sql: String): Long =
        db.useReaderConnection { conn ->
            conn.usePrepared(sql) { stmt ->
                stmt.step()
                stmt.getLong(0)
            }
        }

    private fun podcastV1(
        key: String,
        feedUrl: String = key,
        aliases: List<String> = emptyList(),
        podcastGuid: String? = null,
        customTitle: String? = null,
        credentialOrigin: String? = null,
        syncId: String? = null,
        settings: OverridesV1? = null,
        subscribedAt: Long = 100L,
    ): PodcastV1 =
        PodcastV1(
            key = key,
            feedUrl = feedUrl,
            aliases = aliases,
            podcastGuid = podcastGuid,
            title = "T-$key",
            customTitle = customTitle,
            credentialOrigin = credentialOrigin,
            subscribedAt = subscribedAt,
            syncId = syncId,
            settings = settings,
        )

    private fun groupV1(
        uuid: String,
        name: String,
        sortOrder: Int = 0,
        orderKey: String? = null,
        members: List<String> = emptyList(),
        memberKeys: Map<String, String?> = emptyMap(),
        settings: OverridesV1? = null,
    ): GroupV1 =
        GroupV1(
            uuid = uuid,
            name = name,
            sortOrder = sortOrder,
            orderKey = orderKey,
            settings = settings,
            members = members.map { MemberV1(it, addedAt = 1L, orderKey = memberKeys[it]) },
        )

    private fun stagedOf(
        podcasts: List<PodcastV1> = emptyList(),
        groups: List<GroupV1> = emptyList(),
        credentials: List<CredentialV1> = emptyList(),
    ): StagedLibrary =
        object : StagedLibrary {
            override val createdAtMs: Long = ARCHIVE_AT

            override suspend fun library(): LibraryV1 =
                LibraryV1(podcasts = podcasts, groups = groups, credentials = credentials)

            override suspend fun forEachEpisodeLine(block: suspend (EpisodeLineV1) -> Unit) {}

            override suspend fun queue(): QueueV1? = null

            override suspend fun settings(): SettingsV1? = null
        }

    /** Constructor indirection so a test can swap collaborators (credential ordering asserts). */
    private fun RestoreMerger.copy(
        secrets: SecretStore = this@RestoreMergerTest.secrets,
        coordinator: CredentialCommitCoordinator = this@RestoreMergerTest.coordinator,
        orderKeys: OrderKeys = TestOrderKeys,
    ): RestoreMerger =
        RestoreMerger(
            db = db,
            podcasts = PodcastMatcher(db),
            writes = RestoreWrites(db),
            groupMerger = GroupMerger(db, clock, channels),
            unsubscribe = newUnsubscribe(db, scheduler, clock, secrets, coordinator),
            secrets = secrets,
            coordinator = coordinator,
            channelSync = channels,
            orderKeys = orderKeys,
            clock = clock,
            downloads =
                Provider {
                    FakeDownloadLifecycle { db.downloadDao().removeUnclaimed(it) }
                },
        )

    /**
     * A merger bound to the real `OrderKey` port, for Replace order-key cases: `TestOrderKeys`
     * accepts decimal strings a real archive can never carry, so key-validity regressions run
     * against the production grammar.
     */
    private fun realMerger(): RestoreMerger = merger.copy(orderKeys = ProtocolOrderKeys)

    private companion object {
        const val ARCHIVE_AT = 1_650_000_000_000L
        val ALL_CATEGORIES = RestoreCategory.entries.toSet()
    }
}
