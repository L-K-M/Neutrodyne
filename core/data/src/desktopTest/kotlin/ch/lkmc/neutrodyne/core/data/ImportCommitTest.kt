// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.credentials.CredentialCommitCoordinatorImpl
import ch.lkmc.neutrodyne.core.data.credentials.DesktopSecretStore
import ch.lkmc.neutrodyne.core.data.credentials.IdentitySecretProtection
import ch.lkmc.neutrodyne.core.data.importing.ImportClassifier
import ch.lkmc.neutrodyne.core.data.importing.ImportCommitter
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportSessions
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkScheduler
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.CreateImportOptions
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceGroup
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.feeds.opml.ExportDocument
import ch.lkmc.neutrodyne.feeds.opml.ExportFeed
import ch.lkmc.neutrodyne.feeds.opml.ExportGroup
import ch.lkmc.neutrodyne.feeds.opml.OpmlLayout
import ch.lkmc.neutrodyne.feeds.opml.OpmlWriter
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `ImportCommitter.confirm` end to end (05 §6, 02 Import commit) against a real in-memory Room
 * database and a real `DesktopSecretStore` — no pipeline fakes: sessions/items are seeded the way
 * B4's classify persists them, then the commit's transactions, credential ordering, group
 * resolution and replay semantics are asserted straight off the tables.
 */
@OptIn(ExperimentalUuidApi::class)
class ImportCommitTest {
    private val clock = TestClock()
    private val driver = CommitCancellingDriver()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = FakeRefreshController()
    private val channels = FakeGroupChannelSync()
    private val scheduler = FakeImportWorkScheduler()

    private lateinit var root: File
    private lateinit var secrets: DesktopSecretStore
    private lateinit var committer: ImportCommitter
    private lateinit var payloads: FakePayloadSource
    private lateinit var sessions: ImportSessions

    @BeforeTest
    fun setUp() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-import-commit")
                .toFile()
        secrets =
            DesktopSecretStore(
                db,
                appDirsFor(root),
                CredentialCommitCoordinatorImpl(),
                clock,
                IdentitySecretProtection,
                appScope,
            )
        committer = newCommitter()
        payloads = FakePayloadSource()
        sessions =
            ImportSessions(
                db = db,
                payloads =
                    PayloadStore(
                        storagePaths = storagePathsFor(root),
                        fileSystem = FileSystem.SYSTEM,
                        payloads = payloads,
                        httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
                        netErrors = testClassifier,
                        io = Dispatchers.Default,
                    ),
                classifier = ImportClassifier(db, YouTubeUrlClassifier()),
                opmlReader = XmlPullOpmlReader.discovered(),
                archives = ZipGuard(),
                clock = clock,
                appScope = appScope,
                io = Dispatchers.Default,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        appScope.cancel()
        root.deleteRecursively()
    }

    private fun newCommitter(
        store: SecretStore = secrets,
        workScheduler: ImportWorkScheduler = scheduler,
        syncIds: () -> String = { Uuid.random().toString() },
    ) = ImportCommitter(
        db = db,
        secrets = store,
        coordinator = CredentialCommitCoordinatorImpl(),
        workScheduler = workScheduler,
        refresh = refresh,
        channels = channels,
        orderKeys = TestOrderKeys,
        clock = clock,
        newSyncId = syncIds,
    )

    // --- Happy path: subscriptions, groups, memberships, state ---------------------------------

    @Test
    fun flatCommitSubscribesPendingFeeds() =
        runTest {
            val sid =
                seedSession(
                    items =
                        listOf(
                            item(1, "https://atp.fm/rss"),
                            item(2, "https://feeds.example.com/daily"),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val feeds = db.podcastDao().allForExport()
            assertEquals(2, feeds.size)
            val atp = assertNotNull(db.podcastDao().byFeedKey("atp.fm/rss"))
            assertEquals("https://atp.fm/rss", atp.feedUrl)
            assertEquals(PodcastStatus.PENDING_FIRST_FETCH, atp.status)
            assertTrue(atp.initialFetch)
            assertEquals(clock.now(), atp.subscribedAt)
            assertEquals(clock.now(), atp.nextRefreshAt)
            assertTrue(atp.artworkKey.startsWith("m-"))
            assertNull(atp.lastAttemptAt)

            val items = db.importDao().itemsOf(sid)
            assertEquals(ImportItemStatus.QUEUED, items[0].status)
            assertEquals(atp.id, items[0].podcastId)
            assertCommitted(sid)
            assertEquals(listOf(sid), scheduler.sessions)
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(listOf("sync()"), channels.calls)
            assertEquals(0, db.syncOutboxDao().count())
        }

    @Test
    fun groupedCommitCreatesGroupsMembershipsAndRoundTripsEmpty() =
        runTest {
            val sid =
                seedSession(
                    options =
                        ImportOptions(
                            sourceGroups =
                                listOf(
                                    SourceGroup("tech", "Tech", 0x112233, "code", null),
                                    SourceGroup("news", "News", null, null, null),
                                    SourceGroup("empty", "Empty", null, null, null),
                                ),
                        ),
                    items =
                        listOf(
                            item(1, "https://a.example.com/f", groups = listOf("Tech", "News")),
                            item(2, "https://b.example.com/f", groups = listOf("Tech")),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val groups = db.groupDao().groupsInOrder()
            assertEquals(listOf("Tech", "News", "Empty"), groups.map { it.name })
            assertEquals(listOf("tech", "news", "empty"), groups.map { it.nameKey })
            assertEquals(0xFF112233.toInt(), groups[0].colorArgb)
            assertEquals("code", groups[0].iconKey)
            assertTrue(groups[1].colorArgb != null, "new group without a colour gets the palette")
            assertTrue(groups.all { it.uuid.matches(UUID_V4) }, "UUIDv4 group ids")
            val tails = groups.map { it.orderKey }
            assertEquals(tails.sorted(), tails, "ordered group keys, proposal order")

            val tech = groups[0]
            val members = db.groupDao().membersOf(tech.id)
            assertEquals(2, members.size)
            assertEquals(members.map { it.orderKey }.sorted(), members.map { it.orderKey })
            assertEquals(1, db.groupDao().membersOf(groups[1].id).size)
            assertTrue(db.groupDao().membersOf(groups[2].id).isEmpty(), "the empty folder round-trips")
        }

    @Test
    fun excludedProposalsAndRenamesResolveThroughStoredOptions() =
        runTest {
            val sid =
                seedSession(
                    options =
                        ImportOptions(
                            excludedGroupKeys = setOf("feeds"),
                            renamedGroups = mapOf("tech" to "Renamed Tech"),
                            sourceGroups =
                                listOf(
                                    SourceGroup("feeds", "Feeds", null, null, "wrapper"),
                                    SourceGroup("tech", "Tech", null, null, null),
                                ),
                        ),
                    items = listOf(item(1, "https://a.example.com/f", groups = listOf("Feeds", "Tech"))),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val groups = db.groupDao().groupsInOrder()
            assertEquals(listOf("Renamed Tech"), groups.map { it.name })
            assertEquals(listOf("renamed tech"), groups.map { it.nameKey })
            assertEquals(
                listOf("https://a.example.com/f"),
                db.groupDao().membersOf(groups[0].id).map { assertNotNull(db.podcastDao().byId(it.podcastId)).feedUrl },
            )
        }

    @Test
    fun targetGroupIdAppliesToCommittedAndSubscribedItems() =
        runTest {
            val existing = subscribeExisting("https://feeds.example.com/daily", "feeds.example.com/daily")
            val targetId = seedGroup("Into")
            val sid =
                seedSession(
                    options = ImportOptions(importGroups = false, targetGroupId = targetId),
                    items =
                        listOf(
                            item(1, "https://a.example.com/f", groups = listOf("Tech")),
                            item(
                                2,
                                "https://feeds.example.com/daily",
                                groups = listOf("Tech"),
                                selected = false,
                                status = ImportItemStatus.ALREADY_SUBSCRIBED,
                                podcastId = existing,
                            ),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val memberIds = db.groupDao().membersOf(targetId).map { it.podcastId }
            assertEquals(2, memberIds.size)
            assertTrue(existing in memberIds)
            assertTrue(db.groupDao().groupsInOrder().none { it.nameKey == "tech" }, "importGroups=false")
        }

    @Test
    fun targetGroupNameCreatesTheTarget() =
        runTest {
            val sid =
                seedSession(
                    options = ImportOptions(importGroups = false, targetGroupName = "Fresh Start"),
                    items = listOf(item(1, "https://a.example.com/f", groups = listOf("Tech"))),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val groups = db.groupDao().groupsInOrder()
            assertEquals(listOf("Fresh Start"), groups.map { it.name })
            assertEquals(1, db.groupDao().membersOf(groups[0].id).size)
        }

    @Test
    fun alreadySubscribedItemsGetMembershipsWithoutTouchingThePodcast() =
        runTest {
            val existingId = subscribeExisting("https://atp.fm/rss", "atp.fm/rss")
            val before = assertNotNull(db.podcastDao().byId(existingId))
            val sid =
                seedSession(
                    options =
                        ImportOptions(
                            notifyNewEpisodes = true,
                            sourceGroups = listOf(SourceGroup("tech", "Tech", null, null, null)),
                        ),
                    items =
                        listOf(
                            item(
                                1,
                                "https://atp.fm/rss",
                                groups = listOf("Tech"),
                                selected = false,
                                status = ImportItemStatus.ALREADY_SUBSCRIBED,
                                podcastId = existingId,
                            ),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val after = assertNotNull(db.podcastDao().byId(existingId))
            assertEquals(before, after)
            assertNull(db.scopeSettingsDao().forPodcast(existingId), "no override on a foreign podcast")
            assertEquals(
                existingId,
                db
                    .groupDao()
                    .membersOf(assertNotNull(db.groupDao().groupByNameKey("tech")).id)
                    .single()
                    .podcastId,
            )
            assertEquals(
                ImportItemStatus.ALREADY_SUBSCRIBED,
                db
                    .importDao()
                    .itemsOf(sid)
                    .single()
                    .status,
            )
        }

    // --- In-transaction conflict handling ---------------------------------------------------------

    @Test
    fun aSubscribeRaceContinuesAsAlreadySubscribed() =
        runTest {
            val sid = seedSession(items = listOf(item(1, "https://atp.fm/rss", groups = listOf("Tech"))))
            // The feed is subscribed between classify and confirm — the in-transaction feedKey
            // re-check must catch it, not the unique index (02).
            val racedId = subscribeExisting("https://atp.fm/rss", "atp.fm/rss")
            seedGroup("Tech")

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            assertEquals(1, db.podcastDao().allForExport().size, "no duplicate podcast")
            val item = db.importDao().itemsOf(sid).single()
            assertEquals(ImportItemStatus.ALREADY_SUBSCRIBED, item.status)
            assertEquals(racedId, item.podcastId)
            assertCommitted(sid)
        }

    @Test
    fun anAliasRaceContinuesAsAlreadySubscribed() =
        runTest {
            val owner = subscribeExisting("https://other.example.com/f", "other.example.com/f")
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "atp.fm/rss",
                    podcastId = owner,
                    reason = AliasReason.SUBSCRIBE_INPUT,
                    addedAt = clock.now(),
                ),
            )
            val sid = seedSession(items = listOf(item(1, "https://atp.fm/rss")))

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            assertEquals(1, db.podcastDao().allForExport().size)
            val item = db.importDao().itemsOf(sid).single()
            assertEquals(ImportItemStatus.ALREADY_SUBSCRIBED, item.status)
            assertEquals(owner, item.podcastId)
        }

    @Test
    fun aGroupNameRaceReusesTheExistingGroupAndKeepsItsLook() =
        runTest {
            val existingId = seedGroup("Tech", colorArgb = 0x777777)
            val sid =
                seedSession(
                    options =
                        ImportOptions(
                            sourceGroups = listOf(SourceGroup("tech", "Tech", 0x112233, "code", null)),
                        ),
                    items = listOf(item(1, "https://a.example.com/f", groups = listOf("Tech"))),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val groups = db.groupDao().groupsInOrder()
            assertEquals(1, groups.size)
            assertEquals(existingId, groups[0].id)
            assertEquals(0x777777, groups[0].colorArgb, "existing groups keep their look")
            assertEquals(1, db.groupDao().membersOf(existingId).size)
        }

    @Test
    fun anOriginalIdentityBecomesAnImportAlias() =
        runTest {
            val sid =
                seedSession(
                    items =
                        listOf(
                            item(
                                1,
                                "https://legacy.example.com/rss",
                                normalizedUrl = "https://atp.fm/rss",
                            ),
                            item(2, "https://b.example.com/f"),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val podcast = assertNotNull(db.podcastDao().byFeedKey("atp.fm/rss"))
            val aliases = db.podcastDao().aliases(podcast.id)
            assertEquals(1, aliases.size)
            assertEquals(AliasReason.IMPORT, aliases[0].reason)
            assertEquals("legacy.example.com/rss", aliases[0].url)
            assertTrue(
                db.podcastDao().aliases(assertNotNull(db.podcastDao().byFeedKey("b.example.com/f")).id).isEmpty(),
            )
        }

    // --- Chunking, replay, failure, cancellation --------------------------------------------------

    @Test
    fun fiveHundredPlusItemsCommitAcrossChunks() =
        runTest {
            val sid =
                seedSession(
                    items = (1..501).map { item(it, "https://h$it.example.com/feed") },
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            assertEquals(501, db.podcastDao().allForExport().size)
            assertEquals(501, db.importDao().queuedCount(sid))
            assertCommitted(sid)
        }

    @Test
    fun repeatedConfirmWritesNothingTwice() =
        runTest {
            val sid =
                seedSession(
                    options =
                        ImportOptions(sourceGroups = listOf(SourceGroup("tech", "Tech", null, null, null))),
                    items =
                        listOf(
                            item(
                                1,
                                "https://user:pw@a.example.com/f",
                                normalizedUrl = "https://a.example.com/f",
                                groups = listOf("Tech"),
                            ),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))
            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            assertEquals(1, db.podcastDao().allForExport().size)
            assertEquals(1, db.groupDao().groupsInOrder().size)
            assertEquals(1, db.groupDao().membersOf(db.groupDao().groupsInOrder()[0].id).size)
            assertEquals(1, db.credentialDao().byOrigin("https://a.example.com").size)
            assertEquals(1, scheduler.sessions.size)
        }

    @Test
    fun aCancelBetweenChunksKeepsCommittedWorkAndCleansOrphanPuts() =
        runTest {
            val items =
                (1..500).map { item(it, "https://h$it.example.com/f") }.toMutableList()
            // A private feed inside the first chunk (its put stays referenced) and one inside
            // the aborted second chunk (its put rolls back) cover both halves of the cleanup.
            items[0] =
                item(1, "https://u1:p1@h1.example.com/f", normalizedUrl = "https://h1.example.com/f")
            items +=
                item(501, "https://u2:p2@z.example.com/f", normalizedUrl = "https://z.example.com/f")
            val sid = seedSession(items = items)

            val job =
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    .launch { committer.confirm(sid) }
            driver.arm("INSERT OR IGNORE INTO `podcast` ") { job.cancel() }
            job.join()
            // Chunk one committed; chunk two's feed and credential never landed.
            assertTrue(job.isCancelled)
            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(sid)?.state)
            assertEquals(500, db.podcastDao().allForExport().size)
            assertNull(secrets.get("https://z.example.com"), "unreferenced put rolled back")
            val kept = assertNotNull(db.podcastDao().byFeedKey("h1.example.com/f"))
            assertNotNull(kept.credentialId)
            assertNotNull(secrets.get("https://h1.example.com"), "referenced put stays")

            // Re-confirm finishes the session with no duplicates.
            assertIs<Outcome.Success<*>>(committer.confirm(sid))
            assertEquals(501, db.podcastDao().allForExport().size)
            assertCommitted(sid)
        }

    @Test
    fun aCancelAtCommittedStillSchedulesItsFetchAndEffects() =
        runTest {
            val sid = seedSession(items = listOf(item(1, "https://a.example.com/f")))

            val job =
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    .launch(start = CoroutineStart.LAZY) { committer.confirm(sid) }
            driver.arm("UPDATE import_session SET state = 'COMMITTED'") { job.cancel() }
            job.start()
            job.join()

            assertTrue(job.isCancelled, "Room surfaces the post-commit cancellation")
            assertEquals(ImportState.COMMITTED, db.importDao().sessionById(sid)?.state)
            assertEquals(1, db.podcastDao().allForExport().size)
            assertEquals(listOf(sid), scheduler.sessions)
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(listOf("sync()"), channels.calls)
        }

    @Test
    fun aSessionDeletedBetweenChunksLeavesOnlyCommittedWork() =
        runTest {
            val items =
                (1..500)
                    .map { item(it, "https://h$it.example.com/f", groups = listOf("Tech")) }
                    .toMutableList()
            // A private feed inside the committed chunk (its put stays referenced) and one in
            // the aborted chunk (its put rolls back) cover both cleanup halves.
            items[0] =
                item(
                    1,
                    "https://u1:p1@h1.example.com/f",
                    normalizedUrl = "https://h1.example.com/f",
                    groups = listOf("Tech"),
                )
            items +=
                item(
                    501,
                    "https://u2:p2@h501.example.com/f",
                    normalizedUrl = "https://h501.example.com/f",
                    groups = listOf("Tech"),
                )
            val sid = seedSession(items = items)

            // The delete lands after chunk one's transaction commits — a PREVIEW cancel and
            // the expired-session sweep both run this same deleteSession (items cascade).
            driver.arm("INSERT OR IGNORE INTO `podcast` ") {
                driver.exec("DELETE FROM import_item WHERE sessionId = $sid")
                driver.exec("DELETE FROM import_session WHERE id = $sid")
            }
            val outcome = committer.confirm(sid)

            assertEquals(
                ImportError.SessionGone,
                assertIs<Outcome.Failure<ImportError>>(outcome).error,
            )
            assertNull(db.importDao().sessionById(sid))
            assertEquals(500, db.podcastDao().allForExport().size, "committed chunks stand")
            assertNull(db.podcastDao().byFeedKey("h501.example.com/f"), "the aborted chunk never landed")
            val tech = assertNotNull(db.groupDao().groupByNameKey("tech"))
            assertEquals(500, db.groupDao().membersOf(tech.id).size, "no later memberships")
            assertNull(secrets.get("https://h501.example.com"), "unreferenced put rolled back")
            assertNotNull(secrets.get("https://h1.example.com"), "referenced put stays")
            assertTrue(scheduler.sessions.isEmpty(), "a deleted session is never scheduled")
            assertTrue(refresh.calls.isEmpty() && channels.calls.isEmpty())
        }

    @Test
    fun aSessionLeavingPreviewBetweenChunksSkipsTheRest() =
        runTest {
            val sid = seedSession(items = (1..501).map { item(it, "https://h$it.example.com/f") })

            // A live row that left PREVIEW (a racing transition) is the same lost ownership —
            // the remaining chunks must not write and the marker must not run.
            driver.arm("INSERT OR IGNORE INTO `podcast` ") {
                driver.exec("UPDATE import_session SET state = 'CANCELLED' WHERE id = $sid")
            }
            val outcome = committer.confirm(sid)

            assertEquals(
                ImportError.SessionGone,
                assertIs<Outcome.Failure<ImportError>>(outcome).error,
            )
            assertEquals(ImportState.CANCELLED, db.importDao().sessionById(sid)?.state)
            assertEquals(500, db.podcastDao().allForExport().size, "committed chunks stand")
            assertNull(db.podcastDao().byFeedKey("h501.example.com/f"), "the aborted chunk never landed")
            val items = db.importDao().itemsOf(sid)
            assertEquals(501, items.size, "no cascade — the row moved on, it was not deleted")
            assertEquals(ImportItemStatus.QUEUED, items.first().status)
            assertEquals(ImportItemStatus.PREVIEW, items.last().status)
            assertTrue(scheduler.sessions.isEmpty(), "a cancelled session is never scheduled")
            assertTrue(refresh.calls.isEmpty() && channels.calls.isEmpty())
        }

    @Test
    fun aSchedulerFailureKeepsTheCommit() =
        runTest {
            scheduler.fail = true
            val sid = seedSession(items = listOf(item(1, "https://a.example.com/f")))

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            assertCommitted(sid)
            assertEquals(1, db.podcastDao().allForExport().size)
        }

    // --- Credentials -------------------------------------------------------------------------------

    @Test
    fun credentialsAreStoredBeforeThePodcastWriteAndRedactedAfter() =
        runTest {
            val ordered = OrderedSecretStore(secrets)
            val committer = newCommitter(store = ordered)
            val sid =
                seedSession(
                    items =
                        listOf(
                            item(
                                1,
                                "https://alice:s3cret@feeds.private.example.com/rss",
                                normalizedUrl = "https://feeds.private.example.com/rss",
                            ),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            assertEquals(1, ordered.totalPuts)
            assertEquals(1, ordered.putsBeforePodcastInsert, "the put precedes the Room write")
            val stored = assertNotNull(secrets.get("https://feeds.private.example.com"))
            assertEquals("alice", stored.username)
            assertEquals("s3cret", stored.secret.concatToString())

            val podcast = assertNotNull(db.podcastDao().byFeedKey("feeds.private.example.com/rss"))
            assertEquals("https://feeds.private.example.com/rss", podcast.feedUrl)
            assertEquals(
                db
                    .credentialDao()
                    .byOrigin("https://feeds.private.example.com")
                    .single()
                    .id,
                podcast.credentialId,
            )
            val item = db.importDao().itemsOf(sid).single()
            assertTrue(!item.originalUrl.contains("s3cret"), "no password survives in import_item")
            assertTrue(item.originalUrl.contains("feeds.private.example.com"))
        }

    @Test
    fun wrappedUrlsStoreCredentialsAgainstTheResolvedHttpOrigin() =
        runTest {
            val sid =
                seedSession(
                    items =
                        listOf(
                            item(1, "feed:https://u:s1@h1.example.com/f", normalizedUrl = "https://h1.example.com/f"),
                            item(2, "feed://u:s2@h2.example.com/f", normalizedUrl = "https://h2.example.com/f"),
                            item(3, "pcast://u:s3@h3.example.com/f", normalizedUrl = "https://h3.example.com/f"),
                            item(4, "podcast://u:s4@h4.example.com/f", normalizedUrl = "https://h4.example.com/f"),
                            item(5, "itpc://u:s5@h5.example.com/f", normalizedUrl = "https://h5.example.com/f"),
                            item(
                                6,
                                "neutrodyne://subscribe?url=https%3A%2F%2Fu%3As6%40h6.example.com%2Ff",
                                normalizedUrl = "https://h6.example.com/f",
                            ),
                            item(7, "https://u:s7@h7.example.com/f", normalizedUrl = "https://h7.example.com/f"),
                            item(
                                8,
                                "https://antennapod.org/deeplink/subscribe?url=https%3A%2F%2Fu%3As8%40h8.example.com%2Ff",
                                normalizedUrl = "https://h8.example.com/f",
                            ),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            for (n in 1..8) {
                val stored =
                    assertNotNull(
                        secrets.get("https://h$n.example.com"),
                        "credentials of item $n must reach the resolved http origin",
                    )
                assertEquals("u", stored.username)
                assertEquals("s$n", stored.secret.concatToString())
                assertNotNull(
                    assertNotNull(db.podcastDao().byFeedKey("h$n.example.com/f")).credentialId,
                    "podcast for item $n links its credential",
                )
            }
            val passwords = (1..8).map { "s$it" }
            val committedUrls =
                db.importDao().itemsOf(sid).map { it.originalUrl } +
                    db.podcastDao().allForExport().map { it.feedUrl } +
                    db
                        .podcastDao()
                        .allForExport()
                        .flatMap { db.podcastDao().aliases(it.id) }
                        .map { it.url }
            assertTrue(
                committedUrls.none { url -> passwords.any(url::contains) },
                "no password survives in committed URLs or aliases",
            )
        }

    @Test
    fun aFailingPutRollsBackTheEarlierUnreferencedPuts() =
        runTest {
            // The second item's put throws: the first one's credential row exists but no
            // podcast references it — the failure cleanup must remove it (03's rule) while
            // the commit reports the storage failure and never schedules.
            val committer = newCommitter(store = FailingAfterPuts(secrets, successes = 1))
            val sid =
                seedSession(
                    items =
                        listOf(
                            item(1, "https://u:s1@h1.example.com/f", normalizedUrl = "https://h1.example.com/f"),
                            item(2, "https://u:s2@h2.example.com/f", normalizedUrl = "https://h2.example.com/f"),
                        ),
                )

            assertEquals(
                ImportError.Storage,
                assertIs<Outcome.Failure<ImportError>>(committer.confirm(sid)).error,
            )
            assertTrue(db.podcastDao().allForExport().isEmpty(), "no podcast rows landed")
            assertEquals(
                0,
                db.credentialDao().byOrigin("https://h1.example.com").size,
                "the earlier put's row rolled back",
            )
            assertNull(secrets.get("https://h1.example.com"), "the earlier put rolled back")
            assertNull(secrets.get("https://h2.example.com"))
            assertEquals(ImportState.PREVIEW, db.importDao().sessionById(sid)?.state)
            assertTrue(scheduler.sessions.isEmpty())
            assertTrue(refresh.calls.isEmpty() && channels.calls.isEmpty())
        }

    // --- Session/options gates ----------------------------------------------------------------------

    @Test
    fun confirmGatesSessionStateAndRestoreIntent() =
        runTest {
            assertEquals(
                ImportError.SessionGone,
                assertIs<Outcome.Failure<ImportError>>(committer.confirm(999)).error,
            )

            val cancelled =
                seedSession(state = ImportState.CANCELLED, items = listOf(item(1, "https://a.example.com/f")))
            assertIs<Outcome.Success<*>>(committer.confirm(cancelled))
            assertEquals(0, db.podcastDao().allForExport().size)

            val backup = seedSession(format = ImportFormat.NEUTRODYNE_BACKUP)
            assertEquals(
                ImportError.SessionGone,
                assertIs<Outcome.Failure<ImportError>>(committer.confirm(backup)).error,
            )

            val restoreIntent =
                seedSession(
                    options = ImportOptions(restoreRequestedAt = clock.now()),
                    items = listOf(item(1, "https://a.example.com/f")),
                )
            assertEquals(
                ImportError.SessionGone,
                assertIs<Outcome.Failure<ImportError>>(committer.confirm(restoreIntent)).error,
            )
            assertEquals(0, db.podcastDao().allForExport().size)
        }

    @Test
    fun workerFieldsMergeIntoTheLatestStoredOptions() =
        runTest {
            val sid =
                seedSession(
                    options = ImportOptions(laneAttempts = 7, targetGroupName = "Late Group"),
                    items = listOf(item(1, "https://a.example.com/f")),
                )
            // A screen write landing before confirm applies — the commit reads the row, never a
            // snapshot the preview happened to render.
            db.importDao().setOptionsJson(
                sid,
                ImportOptionsJson.encode(
                    ImportOptions(
                        laneAttempts = 9,
                        sourceGroups = listOf(SourceGroup("late", "Late", null, null, null)),
                    ),
                ),
            )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val options = ImportOptionsJson.decode(assertNotNull(db.importDao().sessionById(sid)).optionsJson!!)
            assertEquals(9, options.laneAttempts, "worker fields keep the latest row's values")
            assertEquals(clock.now(), options.committedAt)
            assertEquals(clock.now(), options.attemptMarkerAt)
            assertEquals(listOf("Late"), db.groupDao().groupsInOrder().map { it.name })
        }

    @Test
    fun notifyOptionWritesTheOverrideWithoutFetching() =
        runTest {
            val sid =
                seedSession(
                    options = ImportOptions(notifyNewEpisodes = true),
                    items = listOf(item(1, "https://a.example.com/f")),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val podcast = assertNotNull(db.podcastDao().byFeedKey("a.example.com/f"))
            assertEquals(
                true,
                db
                    .scopeSettingsDao()
                    .forPodcast(podcast.id)
                    ?.o
                    ?.notifyNewEpisodes,
            )
            assertEquals(PodcastStatus.PENDING_FIRST_FETCH, podcast.status)
            assertTrue(podcast.initialFetch)
        }

    @Test
    fun youtubeAndUnselectedRowsNeverBecomeSubscriptions() =
        runTest {
            val sid =
                seedSession(
                    items =
                        listOf(
                            item(
                                1,
                                "https://www.youtube.com/feeds/videos.xml?channel_id=UCx",
                                kind = ImportItemKind.YOUTUBE,
                                selected = false,
                                status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                            ),
                            item(2, "https://b.example.com/f", selected = false),
                            item(
                                3,
                                "https://c.example.com/f",
                                status = ImportItemStatus.DUPLICATE_IN_FILE,
                                selected = false,
                            ),
                        ),
                )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            assertTrue(db.podcastDao().allForExport().isEmpty())
            val items = db.importDao().itemsOf(sid)
            assertEquals(ImportItemStatus.YOUTUBE_UNSUPPORTED_YET, items[0].status)
            assertCommitted(sid)
        }

    // --- syncId --------------------------------------------------------------------------------------

    @Test
    fun syncIdsAreLowercaseUuidV4AndUnique() =
        runTest {
            val sid = seedSession(items = (1..20).map { item(it, "https://h$it.example.com/f") })

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val syncIds = db.podcastDao().allForExport().map { it.syncId }
            assertEquals(20, syncIds.size)
            assertTrue(syncIds.all { it.matches(UUID_V4) }, "lowercase UUIDv4: $syncIds")
            assertEquals(syncIds.size, syncIds.toSet().size)
        }

    @Test
    fun aSyncIdCollisionRetriesWithAFreshUuid() =
        runTest {
            subscribeExisting("https://old.example.com/f", "old.example.com/f", syncId = "collided-sync-id")
            val ids = ArrayDeque(listOf("collided-sync-id"))
            val committer = newCommitter(syncIds = { ids.removeFirstOrNull() ?: Uuid.random().toString() })
            val sid = seedSession(items = listOf(item(1, "https://a.example.com/f")))

            assertIs<Outcome.Success<*>>(committer.confirm(sid))

            val podcast = assertNotNull(db.podcastDao().byFeedKey("a.example.com/f"))
            assertTrue(podcast.syncId != "collided-sync-id")
            assertEquals(2, db.podcastDao().allForExport().size)
        }

    // --- Custom titles: writer → reader → session → committer (05 §3.5 → §6.3) ---------------------

    @Test
    fun aGroupedExportRoundTripRestoresCustomTitles() =
        runTest {
            val sid = createSessionFromExport(OpmlLayout.GROUPED)

            // The rename is parser metadata: it rides optionsJson keyed by item ordinal —
            // import_item has no column for it (02's no-schema-change rule stands). The
            // duplicate keeps its own ordinal's rename in the map; it never commits.
            val session = assertNotNull(db.importDao().sessionById(sid))
            val options = ImportOptionsJson.decode(assertNotNull(session.optionsJson))
            assertEquals(
                mapOf(0 to "My Rename", 2 to "Zz Dup Rename"),
                options.itemCustomTitles,
            )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))
            assertCustomTitlesCommitted()
        }

    @Test
    fun aFlatExportRoundTripRestoresCustomTitles() =
        runTest {
            val sid = createSessionFromExport(OpmlLayout.FLAT)

            val session = assertNotNull(db.importDao().sessionById(sid))
            val options = ImportOptionsJson.decode(assertNotNull(session.optionsJson))
            assertEquals(
                mapOf(0 to "My Rename", 2 to "Zz Dup Rename"),
                options.itemCustomTitles,
            )

            assertIs<Outcome.Success<*>>(committer.confirm(sid))
            assertCustomTitlesCommitted()
        }

    private suspend fun assertCustomTitlesCommitted() {
        val renamed = assertNotNull(db.podcastDao().byFeedKey("a.example.com/f"))
        assertEquals("Publisher Title", renamed.title, "the feed's own title stays title")
        assertEquals("My Rename", renamed.customTitle)
        val plain = assertNotNull(db.podcastDao().byFeedKey("b.example.com/f"))
        assertEquals("Same Title", plain.title)
        assertNull(plain.customTitle, "an unrenamed feed leaves customTitle null")
        assertEquals(2, db.podcastDao().allForExport().size, "the duplicate never inserted")
    }

    /** Writes an `ExportDocument`, feeds it through `ImportSessions.create` — the real parse path. */
    private suspend fun createSessionFromExport(layout: OpmlLayout): Long {
        val out = Buffer()
        OpmlWriter.write(out, renamedExport(), layout)
        payloads.register(ROUND_TRIP_URI, bytes = out.readByteArray())
        return assertIs<Outcome.Success<Long>>(
            sessions.create(ImportSource(ROUND_TRIP_URI, "export.opml"), CreateImportOptions()),
        ).value
    }

    /**
     * Three entries: a rename (`text` ≠ `title`), an unrenamed feed, and a third repeating the
     * first feed's URL with a different rename. `OpmlWriter` sorts feeds by `displayTitle` and
     * the "Zz" prefix keeps the duplicate last in both layouts, so the file dedupe leaves it
     * `DUPLICATE_IN_FILE` at ordinal 2 and ordinal 0's rename is the one that must land.
     */
    private fun renamedExport(): ExportDocument =
        ExportDocument(
            title = "Neutrodyne",
            createdAt = clock.now(),
            groups = listOf(ExportGroup("g1", "Tech", 0x112233, "code")),
            feeds =
                listOf(
                    ExportFeed(
                        displayTitle = "My Rename",
                        feedTitle = "Publisher Title",
                        xmlUrl = "https://a.example.com/f",
                        htmlUrl = "https://a.example.com",
                        language = null,
                        groupKeys = listOf("g1"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "Same Title",
                        feedTitle = "Same Title",
                        xmlUrl = "https://b.example.com/f",
                        htmlUrl = null,
                        language = null,
                        groupKeys = listOf("g1"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "Zz Dup Rename",
                        feedTitle = "Zz Dup Title",
                        xmlUrl = "https://a.example.com/f",
                        htmlUrl = null,
                        language = null,
                        groupKeys = emptyList(),
                        youtube = false,
                        ytVariants = null,
                    ),
                ),
        )

    // --- Helpers ----------------------------------------------------------------------------------

    private suspend fun assertCommitted(sid: Long) {
        val session = assertNotNull(db.importDao().sessionById(sid))
        assertEquals(ImportState.COMMITTED, session.state)
        val options = ImportOptionsJson.decode(assertNotNull(session.optionsJson))
        assertEquals(clock.now(), options.committedAt)
        assertEquals(clock.now(), options.attemptMarkerAt)
    }

    private suspend fun seedSession(
        options: ImportOptions = ImportOptions(),
        format: ImportFormat = ImportFormat.OPML,
        state: ImportState = ImportState.PREVIEW,
        items: List<ImportItemEntity> = emptyList(),
    ): Long {
        val sid =
            db.importDao().insertSession(
                ImportSessionEntity(
                    createdAt = clock.now(),
                    sourceFormat = format,
                    state = state,
                    optionsJson = ImportOptionsJson.encode(options),
                ),
            )
        db.importDao().insertItems(items.map { it.copy(sessionId = sid) })
        return sid
    }

    private fun item(
        ordinal: Int,
        url: String,
        normalizedUrl: String? = url,
        title: String? = "Item $ordinal",
        groups: List<String> = emptyList(),
        selected: Boolean = true,
        kind: ImportItemKind = ImportItemKind.RSS,
        status: ImportItemStatus = ImportItemStatus.PREVIEW,
        podcastId: Long? = null,
    ) = ImportItemEntity(
        sessionId = 0,
        ordinal = ordinal,
        title = title,
        originalUrl = url,
        normalizedUrl = normalizedUrl,
        kind = kind,
        groupNamesJson = Json.encodeToString(groups),
        selected = selected,
        status = status,
        podcastId = podcastId,
    )

    private suspend fun subscribeExisting(
        feedUrl: String,
        feedKey: String,
        syncId: String = Uuid.random().toString(),
    ): Long =
        db.podcastDao().insertPodcast(
            podcastEntity(feedUrl = feedUrl, feedKey = feedKey, syncId = syncId).let {
                it.copy(nextRefreshAt = 42L, status = PodcastStatus.ACTIVE)
            },
        )

    private suspend fun seedGroup(
        name: String,
        colorArgb: Int? = null,
    ): Long =
        db.groupDao().insert(
            PodcastGroupEntity(
                uuid = Uuid.random().toString(),
                name = name,
                nameKey = (GroupNames.validate(name) as GroupNames.Validation.Valid).nameKey,
                orderKey = TestOrderKeys.after(db.groupDao().lastGroupOrderKey()),
                colorArgb = colorArgb,
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )

    /** Asserts every [put] lands before any `podcast.credentialId` reference does (03 ordering). */
    private inner class OrderedSecretStore(
        private val store: SecretStore,
    ) : SecretStore by store {
        var totalPuts = 0
        var putsBeforePodcastInsert = 0

        override suspend fun put(
            origin: String,
            username: String,
            secret: CharArray,
            attributes: Map<String, String>,
        ): Long? {
            val id = store.put(origin, username, secret, attributes)
            totalPuts++
            if (db.podcastDao().allForExport().all { it.credentialId == null }) putsBeforePodcastInsert++
            return id
        }
    }

    /** Lets the first [successes] puts through, then throws — everything else delegates. */
    private class FailingAfterPuts(
        private val store: SecretStore,
        private val successes: Int,
    ) : SecretStore by store {
        private var puts = 0

        override suspend fun put(
            origin: String,
            username: String,
            secret: CharArray,
            attributes: Map<String, String>,
        ): Long? {
            if (puts++ >= successes) error("credential put failed")
            return store.put(origin, username, secret, attributes)
        }
    }

    private class FakeImportWorkScheduler : ImportWorkScheduler {
        val sessions = mutableListOf<Long>()
        var fail = false

        override suspend fun schedule(sessionId: Long) {
            if (fail) error("scheduler unavailable")
            sessions += sessionId
        }
    }

    private companion object {
        const val ROUND_TRIP_URI = "content://provider/export.opml"
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
