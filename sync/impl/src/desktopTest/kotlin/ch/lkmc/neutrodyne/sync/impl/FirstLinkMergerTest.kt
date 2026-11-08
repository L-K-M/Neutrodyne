// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.testing.FakeLibraryMerger
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkTokenRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LoginRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first-link orchestration over 05's merger port (10 Merge / Use the server's library):
 * the staging file with its resume, the mapping into the staging directory, the sync_clock
 * seeding (local stamps, the shared field merge deciding winners), the capture pass (local
 * winners only, never server-won fields) and the settings durable effects. The `LINK_*`
 * rules themselves are :core:data's, pinned by its merger tests.
 *
 * The two database-outcome behaviours the old engine-merge shortcut owned — a newer remote
 * "unplayed" beating a local mark, and the server's Up-next set replacing local-only entries
 * with its order keys and match hints — run through the real [LibraryMergerImpl] in
 * `:core:data`'s `LibraryMergerImplTest`, which can see that internal port. This class keeps
 * the orchestration observable with the fake port.
 */
class FirstLinkMergerTest {
    private val now = 1_800_000_000_000L
    private val june = 1_780_000_000_000L
    private val october = 1_790_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val podcastSyncId = "00000000-0000-4000-8000-00000000000a"
    private val localOnlySyncId = "00000000-0000-4000-8000-00000000000b"
    private val device = "device-1"

    private val root = createTempDirectory("nd-first-link")
    private val cache = root.resolve("cache")
    private val paths =
        StoragePaths(
            AppDirs(
                data = root.resolve("data"),
                config = root.resolve("config"),
                cache = cache,
                state = root.resolve("state"),
                logs = root.resolve("logs"),
                downloadsDefault = root.resolve("downloads"),
            ),
        )
    private val fileSystem = FileSystem.SYSTEM

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    /**
     * 10 Merge steps 2–4: the staging file is mapped to the four archive-named entries, the
     * record's state clocks ride into `fc`, and the merger port runs under LINK_MERGE.
     */
    @Test
    fun mergeMapsTheStagingFileAndRunsTheLinkMerge() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val client = CountingClient(listOf(podcastRecord(), episodeRecord()))
                val merger = mergerOf(db, client, library)
                // A failing port call stands in for a crash before completion: the staging
                // directory survives it, so the mapped inputs can be inspected.
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val call = library.calls.single()
                assertEquals(MergePolicy.LINK_MERGE, call.policy)
                val staged = readStagedEpisodeLine(call.stagingDir)
                assertEquals("example.com/feed", staged?.p)
                assertEquals("g:one", staged?.k)
                assertEquals(october, staged?.fc?.get("played"), "the played clock rides into fc")
                assertEquals(october, staged?.ts, "ts is the newest non-pos state clock")
                assertEquals(1_790_000_000_100L, staged?.posAt, "posAt is the pos clock")
                assertEquals(1_790_000_000_100L, staged?.pos)

                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Success(
                        ch.lkmc.neutrodyne.core.model
                            .MergeSummary(0, 0, 0, 0, 0, 0, 0, 0),
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Completed)
                val link = db.syncStateDao().get()
                assertTrue(link?.enabled == true)
                assertNotNull(link?.linkedAt)
            } finally {
                db.close()
            }
        }

    /**
     * 10 Merge step 2: the mapper carries every server tombstone into the staging library with
     * its removal clock, so the merger can compare it with the local row's own stamp. The
     * database outcome is pinned by `:core:data`'s `LibraryMergerImplTest`.
     */
    @Test
    fun mergeStagesServerTombstonesWithTheirRemovalClocks() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val groupA = "11111111-1111-4111-8111-111111111111"
                val client =
                    CountingClient(
                        listOf(
                            podcastRecordWithSubscription(localOnlySyncId, "https://example.com/lone", false),
                            podcastRecord(),
                            groupRecord(groupA, "Group A", ok = "a0", deleted = true),
                            memberRecord(groupA, podcastSyncId, ok = "a0", inGroup = false),
                            upNextRecordWithPresence("g:one", "a0", inQueue = false),
                        ),
                    )
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val staged = readStagedLibrary(library.calls.single().stagingDir)
                assertEquals(october, staged.podcasts.first { it.key == "example.com/lone" }.removedAt)
                assertEquals(october, staged.groups.single().removedAt)
                assertEquals(
                    october,
                    staged.groups
                        .single()
                        .members
                        .single()
                        .removedAt,
                )
                val queue = readStagedQueue(library.calls.single().stagingDir)
                assertEquals(october, queue.upNext.single().removedAt)
            } finally {
                db.close()
            }
        }

    /** A resumed merge parks the same unmatched record once, not once per run. */
    @Test
    fun aResumedMergeDoesNotDuplicateParkedRecords() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                library.unmatched = listOf(podcastSyncId to "g:one")
                val client = CountingClient(listOf(podcastRecord(), episodeRecord()))
                val merger = mergerOf(db, client, library)

                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Success(
                        ch.lkmc.neutrodyne.core.model
                            .MergeSummary(0, 0, 0, 0, 0, 0, 0, 0),
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Completed)

                val parked = db.syncParkedDao().forPodcast(podcastSyncId)
                assertEquals(listOf("g:one"), parked.map { it.identityKey }, "one parked row per record")
            } finally {
                db.close()
            }
        }

    /**
     * The ALL_DEVICES preview counts the server's current library: it must not reuse a stale
     * completed staging file, or the destructive reach counts are wrong.
     */
    @Test
    fun previewAllDevicesReachDoesNotReuseAStaleStagingSnapshot() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                val records = mutableListOf(podcastRecord())
                val client =
                    object : SyncClient by ScriptedClient(records.toList()) {
                        override suspend fun changes(
                            since: String?,
                            limit: Int,
                        ): ChangesPage =
                            if (since == null) {
                                ChangesPage(records.toList(), "c:00000001:1", false, "")
                            } else {
                                ChangesPage(emptyList(), "c:00000001:1", false, "")
                            }
                    }
                val merger = mergerOf(db, client, FakeLibraryMerger())

                assertEquals(1 to 0, merger.previewAllDevicesReach(emptySet(), emptySet()))
                records += podcastRecordFor(localOnlySyncId, "https://example.com/lone")
                assertEquals(
                    2 to 0,
                    merger.previewAllDevicesReach(emptySet(), emptySet()),
                    "the preview must pull fresh, not reuse the stale snapshot",
                )
            } finally {
                db.close()
            }
        }

    /**
     * "Use the server's library" treats a server tombstone as an item the server lacks: the
     * mapper filters it out of the staging directory, so the Replace pre-step's local-only
     * cleanup removes the local row.
     */
    @Test
    fun useServerFiltersServerTombstonesOutOfTheStagingLibrary() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val client =
                    CountingClient(
                        listOf(
                            podcastRecordWithSubscription(localOnlySyncId, "https://example.com/lone", false),
                            podcastRecord(),
                        ),
                    )
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.USE_SERVER) is FirstLinkResult.Failed)

                val staged = readStagedLibrary(library.calls.single().stagingDir)
                assertEquals(listOf("example.com/feed"), staged.podcasts.map { it.key })
            } finally {
                db.close()
            }
        }

    /**
     * 10 Merge step 2 / 05 First-link column: the staging library carries the server's `ok`
     * order for groups and members, not the pull's `seq` order — a rename or reorder on another
     * device re-sequences the record, and the merger's Replace replay preserves the list order.
     */
    @Test
    fun mergeStagesGroupsAndMembersInTheServerOkOrder() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val groupA = "11111111-1111-4111-8111-111111111111"
                val groupB = "22222222-2222-4222-8222-222222222222"
                // The pull order is server seq order: A (ok a1) then B (ok a0). The server's
                // ok order is B then A; the members' ok order is the second podcast first.
                val client =
                    CountingClient(
                        listOf(
                            podcastRecord(),
                            podcastRecordFor(localOnlySyncId, "https://example.com/lone"),
                            groupRecord(groupA, "Group A", ok = "a1"),
                            groupRecord(groupB, "Group B", ok = "a0"),
                            memberRecord(groupA, podcastSyncId, ok = "a1"),
                            memberRecord(groupA, localOnlySyncId, ok = "a0"),
                        ),
                    )
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val staged = readStagedLibrary(library.calls.single().stagingDir)
                assertEquals(listOf("Group B", "Group A"), staged.groups.map { it.name })
                val members = staged.groups.first { it.name == "Group A" }.members
                assertEquals(
                    listOf("example.com/lone", "example.com/feed"),
                    members.map { it.podcastKey },
                )
            } finally {
                db.close()
            }
        }

    /**
     * 10 Merge step 2: `ts` excludes the `pos` FIELD, not every clock sharing its millis. A
     * record whose played/fav/pos clocks coincide still carries its played/fav state, so a
     * server-only episode imports its mark instead of silently dropping it.
     */
    @Test
    fun mergeMapsEqualStateClocksIntoTs() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val same = 1_790_000_000_000L
                val client = CountingClient(listOf(podcastRecord(), episodeRecordWithEqualClocks(same)))
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val staged = readStagedEpisodeLine(library.calls.single().stagingDir)
                assertEquals(same, staged?.fc?.get("played"))
                assertEquals(same, staged?.fc?.get("fav"))
                assertEquals(same, staged?.fc?.get("pos"))
                assertEquals(same, staged?.ts, "ts is the newest non-pos state clock, not 0")
            } finally {
                db.close()
            }
        }

    /**
     * The bootstrap writes run with capture enabled and the rows' own timestamps: an empty
     * account uploads this device's whole library, groups and members included (10 step 5).
     */
    @Test
    fun mergeUploadsTheLocalLibraryWhenTheAccountIsEmpty() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                db.withWriteTransaction {
                    db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                syncId = podcastSyncId,
                                feedUrl = "https://example.com/feed",
                                feedKey = "https://example.com/feed",
                                title = "Example",
                            ),
                        )
                    val group =
                        db.groupDao().insert(
                            ch.lkmc.neutrodyne.core.testing.database.groupEntity(
                                name = "News",
                                orderKey = "k1",
                            ),
                        )
                    db
                        .groupDao()
                        .insertMember(
                            ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity(
                                groupId = group,
                                podcastId = db.podcastDao().bySyncId(podcastSyncId)!!.id,
                                orderKey = "m1",
                                addedAt = 1L,
                            ),
                        )
                }
                val client = CountingClient(emptyList())
                val merger = mergerOf(db, client, FakeLibraryMerger())

                val result = merger.merge(FirstLinkChoice.MERGE)

                assertTrue(result is FirstLinkResult.Completed, "$result")
                val rows = db.syncOutboxDao().rows()
                val feedUrl = rows.first { it.coll == "podcast" && it.field == "feedUrl" }
                assertTrue(feedUrl.hlc ushr 16 <= now)
                assertTrue(rows.any { it.coll == "group" && it.rid.isNotEmpty() }, "groups upload too, rows=$rows")
                assertTrue(rows.any { it.coll == "member" }, "members upload too, rows=$rows")
                assertTrue(
                    rows.none { it.hlc ushr 16 > now + 1_000 },
                    "bootstrap writes keep their row timestamps, rows=$rows",
                )
            } finally {
                db.close()
            }
        }

    /**
     * The capture pass never echoes server-won fields: the server's October "unplayed" beat
     * the local June mark in the merged clocks, so only the local-won fields of the record
     * are captured — and the winner the field merge kept is the server's.
     */
    @Test
    fun mergeCapturesLocalWinnersAndNeverServerWonFields() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                // A row newer than the record: its fields win the merged clocks and are
                // captured; the episode's older June mark loses and is not echoed.
                val podcastId =
                    db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                syncId = podcastSyncId,
                                feedUrl = "https://example.com/feed",
                                feedKey = "https://example.com/feed",
                                title = "Example",
                                subscribedAt = october + 100_000L,
                            ),
                        )
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(
                                episodeEntity(
                                    podcastId = podcastId,
                                    identityKey = "g:one",
                                ) { copy(guid = "one") },
                            ),
                        ).single()
                db
                    .episodeStateDao()
                    .ensure(EpisodeStateEntity(episodeId = episodeId, playedAt = june, updatedAt = june))
                val client = CountingClient(listOf(podcastRecord(), episodeRecord()))
                val merger = mergerOf(db, client, library)

                val result = merger.merge(FirstLinkChoice.MERGE)

                assertTrue(result is FirstLinkResult.Completed, "$result")
                // The merged raw state: the server's newer fields won, the local kept ones stay.
                val raw = db.syncClockDao().get("episode", podcastSyncId + "g:one")
                assertNotNull(raw)
                val episode = decodeEpisode(raw.clocks)
                assertEquals(JsonPrimitive(false), episode.fields.getValue("played").v)
                assertEquals(JsonNull, episode.fields.getValue("playedAt").v)
                // The capture pass skips the server-won fields (their origin node differs).
                val rows = db.syncOutboxDao().rows()
                assertTrue(
                    rows.none { it.coll == "episode" && (it.field == "played" || it.field == "pos") },
                    "server-won fields not echoed, rows=$rows",
                )
                // The local podcast's identity fields carried local stamps: captured.
                assertTrue(rows.any { it.coll == "podcast" && it.field == "title" }, "rows=$rows")
            } finally {
                db.close()
            }
        }

    /**
     * The staging resume (10 Merge step 1): a pull that already completed is reused — the
     * second merge issues no further `changes` calls — and an interrupted pull (no end
     * marker) is re-pulled.
     */
    @Test
    fun theStagingFileResumesAfterAnInterruption() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            val client = CountingClient(listOf(podcastRecord()))
            try {
                link(db, deviceId = device)
                val merger = mergerOf(db, client, library)

                // A mergeStaged failure stands in for a crash after the pull, before completion.
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)
                assertEquals(1, client.changesCalls, "the first run pulled once")

                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Success(
                        ch.lkmc.neutrodyne.core.model
                            .MergeSummary(0, 0, 0, 0, 0, 0, 0, 0),
                    )
                val resumed = merger.merge(FirstLinkChoice.MERGE)
                check(resumed is FirstLinkResult.Completed) { "resume failed: $resumed" }
                assertEquals(1, client.changesCalls, "the resume reused the staging file")

                // A completed link deletes the staging file: the next merge pulls again.
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Completed)
                assertEquals(2, client.changesCalls)
            } finally {
                db.close()
            }
        }

    /** Unmatched lines park their record for the next ingest (10 Parked state and stubs). */
    @Test
    fun unmatchedLinesParkTheirRecord() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                library.unmatched = listOf(podcastSyncId to "g:one")
                val client = CountingClient(listOf(podcastRecord(), episodeRecord()))
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Completed)

                val parked = db.syncParkedDao().forPodcast(podcastSyncId)
                assertEquals(listOf("g:one"), parked.map { it.identityKey })
                assertEquals("one", parked.single().guid, "the match hints ride along")
            } finally {
                db.close()
            }
        }

    /** "Use the server's library" runs 05's Replace column through the port (LINK_REPLACE). */
    @Test
    fun useServerRunsTheLinkReplaceThroughThePort() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val client = CountingClient(listOf(podcastRecord()))
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.USE_SERVER) is FirstLinkResult.Completed)

                assertEquals(MergePolicy.LINK_REPLACE, library.calls.single().policy)
                // The server's records are the adopted state, and nothing is pushed.
                assertTrue(db.syncOutboxDao().rows().isEmpty(), "rows=${db.syncOutboxDao().rows()}")
                assertNotNull(db.syncClockDao().get("podcast", podcastSyncId))
                assertNotNull(db.syncStateDao().get()?.linkedAt)
            } finally {
                db.close()
            }
        }

    /**
     * "Use the server's library" maps the server's Up-next records with their order keys and
     * resolves a ref whose key differs from the local episode's key through the record's
     * match hints, so the merger's key step keeps the row the server means (10 Use the
     * server's library). The database outcome of the staged queue runs through the real
     * [LibraryMergerImpl] in `:core:data`'s `LibraryMergerImplTest`.
     */
    @Test
    fun useServerMapsTheServerUpNextWithOrderKeysAndMatchHints() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                seedLocalQueue(db)
                val server =
                    ScriptedClient(
                        listOf(
                            podcastRecord(),
                            upNextRecord("g:srv2", orderKey = "a2"),
                            upNextRecord("g:srv1", orderKey = "a1"),
                            upNextRecord("g:wire", orderKey = "a3", enc = "https://cdn.example.com/alias.mp3"),
                        ),
                    )
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                val merger = mergerOf(db, server, library)

                assertTrue(merger.merge(FirstLinkChoice.USE_SERVER) is FirstLinkResult.Failed)

                val queue = readStagedQueue(library.calls.single().stagingDir)
                assertEquals(
                    listOf("g:srv1" to "a1", "g:srv2" to "a2", "g:alias" to "a3"),
                    queue.upNext.map { it.k to it.ok },
                )
                // The failed merge touched no local state: the queue and the local-only
                // podcast survive for the retry. The pull staged the server rows but never
                // applied them, so the queue is exactly the pre-merge local list.
                assertEquals(
                    listOf("g:local", "g:srv1", "g:alias", "g:lone"),
                    queuedIdentityKeys(db),
                )
                assertNotNull(db.podcastDao().bySyncId(localOnlySyncId))
            } finally {
                db.close()
            }
        }

    /**
     * A pull that dies after an applied page must not reach the replace step: the local rows
     * the server lacks stay exactly as they were. The queue is never cleared before success.
     */
    @Test
    fun useServerDoesNotPruneTheQueueWhenThePullFails() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                seedLocalQueue(db)
                val server =
                    ScriptedClient(
                        listOf(
                            podcastRecord(),
                            upNextRecord("g:srv1", orderKey = "a1"),
                            upNextRecord("g:srv2", orderKey = "a2"),
                        ),
                        firstPageHasMore = true,
                        failAtPage = 1,
                    )
                val merger = mergerOf(db, server, FakeLibraryMerger())

                val result = merger.merge(FirstLinkChoice.USE_SERVER)
                assertTrue(result is FirstLinkResult.Failed)

                // Page one's staged server rows stay unused; nothing local was pruned. The
                // pull stages without applying, so the queue is the pre-merge local list.
                assertEquals(listOf("g:local", "g:srv1", "g:alias", "g:lone"), queuedIdentityKeys(db))
                assertNotNull(db.podcastDao().bySyncId(localOnlySyncId))
                val link = db.syncStateDao().get()
                assertNull(link?.cursor)
                assertNull(link?.linkedAt)
            } finally {
                db.close()
            }
        }

    /**
     * A pull that never reaches the server's last page must fail closed: a truncated snapshot
     * must not reach the merger (a LINK_REPLACE would clear local state by it), and the
     * partial staging file must not carry an end marker, so a retry re-pulls.
     */
    @Test
    fun aPullThatExceedsMaxPagesFailsClosedInsteadOfMergingAPartialSnapshot() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                val client = CountingClient(listOf(podcastRecord()), hasMore = true)
                val library = FakeLibraryMerger()
                val merger = mergerOf(db, client, library)

                val result = merger.merge(FirstLinkChoice.MERGE)

                assertTrue(result is FirstLinkResult.Failed, "$result")
                assertTrue(library.calls.isEmpty(), "a truncated snapshot must not reach the merger")
                assertFalse(
                    fileSystem.exists(cache.resolve("sync-link-$device.jsonl").toString().toPath()),
                    "the partial staging file must not survive as a resumable snapshot",
                )
            } finally {
                db.close()
            }
        }

    /** A cancelled pull propagates instead of reporting Failed; the queue is untouched. */
    @Test
    fun mergeRethrowsCancellationFromThePull() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                seedLocalQueue(db)
                val server =
                    ScriptedClient(
                        listOf(podcastRecord()),
                        failAtPage = 0,
                        failure = { CancellationException("link cancelled") },
                    )
                val merger = mergerOf(db, server, FakeLibraryMerger())

                assertFailsWith<CancellationException> { merger.merge(FirstLinkChoice.USE_SERVER) }
                assertEquals(listOf("g:local", "g:srv1", "g:alias", "g:lone"), queuedIdentityKeys(db))
            } finally {
                db.close()
            }
        }

    @Test
    fun resyncReassertsOriginalValuesAndCompleteClocks() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val podcastId =
                    db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                syncId = podcastSyncId,
                                feedUrl = "https://example.com/feed",
                                feedKey = "https://example.com/feed",
                                title = "Example",
                            ),
                        )
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                val clock = Hlc.of(october, 7, remote)
                db
                    .syncClockDao()
                    .upsert(
                        ch.lkmc.neutrodyne.core.database.SyncClockEntity(
                            "episode",
                            podcastSyncId + "g:one",
                            ch.lkmc.neutrodyne.sync.protocol.SyncJson.encodeToString(
                                RawSyncRecord(
                                    ch.lkmc.neutrodyne.sync.protocol.RecordState(
                                        ch.lkmc.neutrodyne.sync.protocol.SyncCollection.EPISODE,
                                        mapOf(
                                            "played" to
                                                ch.lkmc.neutrodyne.sync.protocol.FieldState(
                                                    JsonPrimitive(true),
                                                    clock,
                                                ),
                                            "playedAt" to
                                                ch.lkmc.neutrodyne.sync.protocol.FieldState(
                                                    JsonPrimitive(5L),
                                                    clock,
                                                ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )

                val activity = SyncActivityState()
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = ScriptedClient(emptyList()),
                        applier = applier(db),
                        engine = engine(db, ScriptedClient(emptyList()), activity),
                        libraryMerger = FakeLibraryMerger(),
                        storagePaths = paths,
                        settings = FakeSettingsSyncPort(),
                        clock = TestClock(now),
                        activity = activity,
                    )
                val resyncResult = merger.resync()
                assertTrue(resyncResult is FirstLinkResult.Completed)

                val outboxRows = db.syncOutboxDao().rows()
                assertTrue(
                    outboxRows.any { it.field == "played" },
                    "rows=$outboxRows clocks=${db.syncClockDao().all()}",
                )
                val played = outboxRows.first { it.field == "played" }
                assertEquals(remote.value, played.nodeId)
                assertEquals(7L, Hlc(played.hlc, NodeId(played.nodeId)).counter)
                assertEquals(ch.lkmc.neutrodyne.core.database.SyncCaptureKind.REPLAY, played.captureKind)
                assertEquals("true", played.value)
            } finally {
                db.close()
            }
        }

    /**
     * The merge marks the shared [SyncActivityState] for its whole duration (seeding, the
     * pull, the replace and the push) and clears it again when it returns. The pull is held
     * at a client gate, so the observation runs against the real database and a real merge.
     */
    @Test
    fun mergeMarksSyncActivityUntilItReturns() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                val gate = CompletableDeferred<Unit>()
                val client =
                    object : SyncClient by ScriptedClient(listOf(podcastRecord())) {
                        override suspend fun changes(
                            since: String?,
                            limit: Int,
                        ): ChangesPage {
                            gate.await()
                            return ChangesPage(emptyList(), "c:00000001:1", false, "")
                        }
                    }
                val activity = SyncActivityState()
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = client,
                        applier = applier(db),
                        engine = engine(db, client, activity),
                        libraryMerger = FakeLibraryMerger(),
                        storagePaths = paths,
                        settings = FakeSettingsSyncPort(),
                        clock = TestClock(now),
                        activity = activity,
                    )
                val job = launch { merger.merge(FirstLinkChoice.MERGE) }
                withTimeoutOrNull(1_000) { activity.count.first { it > 0 } }
                assertTrue(activity.active, "the merge must mark sync active while it runs")
                gate.complete(Unit)
                job.join()
                assertEquals(0, activity.count.value, "a completed merge clears the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    /**
     * The merge's push rounds run through [SyncEngine.round], which marks the same counter:
     * while a round is in flight the count is 2, and it reaches 0 only after both the round
     * and the merge have ended.
     */
    @Test
    fun mergeKeepsSyncActivityAcrossItsNestedRounds() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                // A local podcast newer than the server's record: its fields are local-won,
                // so the capture pass writes outbox rows and the merge pushes a round.
                db.withWriteTransaction {
                    db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                syncId = podcastSyncId,
                                feedUrl = "https://example.com/feed",
                                feedKey = "https://example.com/feed",
                                title = "Example",
                                subscribedAt = october + 100_000L,
                            ),
                        )
                }
                val gate = CompletableDeferred<Unit>()
                val counts = mutableListOf<Int>()
                val activity = SyncActivityState()
                val client =
                    object : SyncClient by ScriptedClient(listOf(podcastRecord())) {
                        override suspend fun changes(
                            since: String?,
                            limit: Int,
                        ): ChangesPage {
                            gate.await()
                            return ChangesPage(listOf(podcastRecord()), "c:00000001:1", false, "")
                        }

                        override suspend fun sync(
                            since: String?,
                            changes: List<ChangeDto>,
                        ): SyncResponse {
                            counts += activity.count.value
                            return SyncResponse(emptyList(), emptyList(), "c:00000001:1", false, "")
                        }
                    }
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = client,
                        applier = applier(db),
                        engine = engine(db, client, activity),
                        libraryMerger = FakeLibraryMerger(),
                        storagePaths = paths,
                        settings = FakeSettingsSyncPort(),
                        clock = TestClock(now),
                        activity = activity,
                    )
                val job = launch { merger.merge(FirstLinkChoice.MERGE) }
                withTimeoutOrNull(1_000) { activity.count.first { it > 0 } }
                assertTrue(activity.active, "the merge must mark sync active while it runs")
                gate.complete(Unit)
                job.join()
                assertTrue(counts.isNotEmpty(), "the merge's push rounds must run through the engine")
                assertTrue(
                    counts.all { it == 2 },
                    "the merge and its nested round share the counter: counts=$counts",
                )
                assertEquals(0, activity.count.value, "a completed merge clears the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    @Test
    fun aFailedMergeClearsSyncActivity() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                val activity = SyncActivityState()
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = ScriptedClient(emptyList(), failAtPage = 0),
                        applier = applier(db),
                        engine = engine(db, ScriptedClient(emptyList()), activity),
                        libraryMerger = FakeLibraryMerger(),
                        storagePaths = paths,
                        settings = FakeSettingsSyncPort(),
                        clock = TestClock(now),
                        activity = activity,
                    )
                val result = merger.merge(FirstLinkChoice.MERGE)
                assertTrue(result is FirstLinkResult.Failed)
                assertEquals(0, activity.count.value, "a failed merge must clear the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    @Test
    fun aCancelledMergeClearsSyncActivity() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                val activity = SyncActivityState()
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client =
                            ScriptedClient(
                                emptyList(),
                                failAtPage = 0,
                                failure = { CancellationException("link cancelled") },
                            ),
                        applier = applier(db),
                        engine = engine(db, ScriptedClient(emptyList()), activity),
                        libraryMerger = FakeLibraryMerger(),
                        storagePaths = paths,
                        settings = FakeSettingsSyncPort(),
                        clock = TestClock(now),
                        activity = activity,
                    )
                assertFailsWith<CancellationException> { merger.merge(FirstLinkChoice.MERGE) }
                assertEquals(0, activity.count.value, "a cancelled merge must clear the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    /**
     * A cancellation after the pull keeps the completed staging file: the retry reuses it
     * instead of pulling again, and the local state is untouched.
     */
    @Test
    fun aCancelledMergeLeavesTheCompletedStagingFileForResume() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                val client = CountingClient(listOf(podcastRecord()))
                val library = FakeLibraryMerger()
                library.failure = { CancellationException("link cancelled") }
                val merger = mergerOf(db, client, library)

                assertFailsWith<CancellationException> { merger.merge(FirstLinkChoice.MERGE) }
                assertEquals(1, client.changesCalls)

                library.failure = null
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Success(
                        ch.lkmc.neutrodyne.core.model
                            .MergeSummary(0, 0, 0, 0, 0, 0, 0, 0),
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Completed)
                assertEquals(1, client.changesCalls, "the completed staging file was reused after cancellation")
            } finally {
                db.close()
            }
        }

    /** The resync holds the same flag for its whole pull-and-reassert duration. */
    @Test
    fun resyncMarksSyncActivityUntilItReturns() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val gate = CompletableDeferred<Unit>()
                val client =
                    object : SyncClient by ScriptedClient(emptyList()) {
                        override suspend fun changes(
                            since: String?,
                            limit: Int,
                        ): ChangesPage {
                            gate.await()
                            return ChangesPage(emptyList(), "c:00000001:1", false, "")
                        }
                    }
                val activity = SyncActivityState()
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = client,
                        applier = applier(db),
                        engine = engine(db, client, activity),
                        libraryMerger = FakeLibraryMerger(),
                        storagePaths = paths,
                        settings = FakeSettingsSyncPort(),
                        clock = TestClock(now),
                        activity = activity,
                    )
                val job = launch { merger.resync() }
                withTimeoutOrNull(1_000) { activity.count.first { it > 0 } }
                assertTrue(activity.active, "the resync must mark sync active while it runs")
                gate.complete(Unit)
                job.join()
                assertEquals(0, activity.count.value, "a completed resync clears the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    @Test
    fun aFailedResyncClearsSyncActivity() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val activity = SyncActivityState()
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = ScriptedClient(emptyList(), failAtPage = 0),
                        applier = applier(db),
                        engine = engine(db, ScriptedClient(emptyList()), activity),
                        libraryMerger = FakeLibraryMerger(),
                        storagePaths = paths,
                        settings = FakeSettingsSyncPort(),
                        clock = TestClock(now),
                        activity = activity,
                    )
                val result = merger.resync()
                assertTrue(result is FirstLinkResult.Failed)
                assertEquals(0, activity.count.value, "a failed resync must clear the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    @Test
    fun aCancelledResyncClearsSyncActivity() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val activity = SyncActivityState()
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client =
                            ScriptedClient(
                                emptyList(),
                                failAtPage = 0,
                                failure = { CancellationException("link cancelled") },
                            ),
                        applier = applier(db),
                        engine = engine(db, ScriptedClient(emptyList()), activity),
                        libraryMerger = FakeLibraryMerger(),
                        storagePaths = paths,
                        settings = FakeSettingsSyncPort(),
                        clock = TestClock(now),
                        activity = activity,
                    )
                assertFailsWith<CancellationException> { merger.resync() }
                assertEquals(0, activity.count.value, "a cancelled resync must clear the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    // --- Fixtures -----------------------------------------------------------------------------------

    private fun mergerOf(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        client: SyncClient,
        library: FakeLibraryMerger,
        activity: SyncActivityState = SyncActivityState(),
    ): FirstLinkMerger =
        FirstLinkMerger(
            db = db,
            client = client,
            applier = applier(db),
            engine = engine(db, client, activity),
            libraryMerger = library,
            storagePaths = paths,
            settings = FakeSettingsSyncPort(),
            clock = TestClock(now),
            activity = activity,
        )

    private suspend fun link(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        enabled: Boolean = false,
        deviceId: String? = device,
    ) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(
                    SyncStateEntity(
                        enabled = enabled,
                        deviceId = deviceId,
                        nodeId = "0000000000000001",
                        hlc = 1L shl 16,
                    ),
                )
        }
    }

    private fun readStagedEpisodeLine(stagingDir: String): ch.lkmc.neutrodyne.feeds.backup.EpisodeLineV1? {
        val codec = BackupCodec(BackupCodec.Mode.STAGING)
        return fileSystem.source(stagingDir.toPath() / BackupCodec.ENTRY_EPISODES).use { source ->
            codec.episodeLines(source).use { lines -> if (lines.hasNext()) lines.next() else null }
        }
    }

    private fun readStagedQueue(stagingDir: String): ch.lkmc.neutrodyne.feeds.backup.QueueV1 {
        val codec = BackupCodec(BackupCodec.Mode.STAGING)
        return fileSystem.source(stagingDir.toPath() / BackupCodec.ENTRY_QUEUE).use(codec::decodeQueue)
    }

    private fun readStagedLibrary(stagingDir: String): ch.lkmc.neutrodyne.feeds.backup.LibraryV1 {
        val codec = BackupCodec(BackupCodec.Mode.STAGING)
        return fileSystem.source(stagingDir.toPath() / BackupCodec.ENTRY_LIBRARY).use(codec::decodeLibrary)
    }

    private fun decodeEpisode(json: String): ch.lkmc.neutrodyne.sync.protocol.RecordState =
        ch.lkmc.neutrodyne.sync.protocol.SyncJson
            .decodeFromString<RawSyncRecord>(json)
            .state

    /** The engine the merge's push rounds run through, over the same scripted client. */
    private fun engine(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        client: SyncClient,
        activity: SyncActivityState,
    ): SyncEngine {
        val secrets = FakeSecretStore()
        return SyncEngine(
            db = db,
            client = client,
            applier = applier(db),
            clock = TestClock(now),
            secrets = secrets,
            sharePasswords = { false },
            address =
                object : ch.lkmc.neutrodyne.core.domain.SyncServerAddress {
                    override suspend fun serverUrl(): String = "https://sync.example"

                    override suspend fun username(): String? = null

                    override suspend fun deviceName(): String? = null

                    override suspend fun shareFeedPasswords(): Boolean = false

                    override suspend fun syncPlaybackSettings(): Boolean = true

                    override suspend fun setServerUrl(url: String?) = Unit

                    override suspend fun setUsername(username: String?) = Unit

                    override suspend fun setShareFeedPasswords(enabled: Boolean) = Unit

                    override suspend fun setSyncPlaybackSettings(enabled: Boolean) = Unit
                },
            tokens =
                SyncTokenStore(secrets).apply {
                    kotlinx.coroutines.runBlocking { save("sync.example", "token") }
                },
            activity = activity,
            gate =
                ch.lkmc.neutrodyne.core.testing
                    .FakeLocalNetworkGate(),
        )
    }

    private fun applier(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase): SyncApplier =
        SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = FakeSecretStore(),
            settings = FakeSettingsSyncPort(),
            playback = NoPlaybackSync,
            syncIngestHook = SyncIngestHook.None,
            channelSync =
                object : GroupChannelSync {
                    override suspend fun sync() = Unit

                    override suspend fun onUuidChanged(
                        oldUuid: String,
                        newUuid: String,
                    ) = Unit

                    override suspend fun onDeleted(uuid: String) = Unit
                },
            sharePasswords = { false },
        )

    private fun podcastRecordFor(
        syncId: String,
        feedUrl: String,
    ): RecordDto =
        podcastRecord().copy(
            id = JsonPrimitive(syncId),
            fields =
                mapOf(
                    "feedUrl" to FieldValue(JsonPrimitive(feedUrl), clock(october)),
                    "subscribed" to FieldValue(JsonPrimitive(true), clock(october)),
                    "title" to FieldValue(JsonPrimitive("Example"), clock(october)),
                ),
        )

    private fun podcastRecordWithSubscription(
        syncId: String,
        feedUrl: String,
        subscribed: Boolean,
    ): RecordDto =
        podcastRecord().copy(
            id = JsonPrimitive(syncId),
            fields =
                mapOf(
                    "feedUrl" to FieldValue(JsonPrimitive(feedUrl), clock(october)),
                    "subscribed" to FieldValue(JsonPrimitive(subscribed), clock(october)),
                    "title" to FieldValue(JsonPrimitive("Example"), clock(october)),
                ),
        )

    private fun groupRecord(
        uuid: String,
        name: String,
        ok: String?,
        deleted: Boolean = false,
    ): RecordDto =
        RecordDto(
            coll = "group",
            id = JsonPrimitive(uuid),
            seq = 0,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields =
                buildMap {
                    put("name", FieldValue(JsonPrimitive(name), clock(october)))
                    put("deleted", FieldValue(JsonPrimitive(deleted), clock(october)))
                    ok?.let { put("ok", FieldValue(JsonPrimitive(it), clock(october))) }
                },
        )

    private fun memberRecord(
        groupUuid: String,
        podcastSyncIdValue: String,
        ok: String?,
        inGroup: Boolean = true,
    ): RecordDto =
        RecordDto(
            coll = "member",
            id =
                buildJsonObject {
                    put("g", groupUuid)
                    put("p", podcastSyncIdValue)
                },
            seq = 0,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields =
                buildMap {
                    put("in", FieldValue(JsonPrimitive(inGroup), clock(october)))
                    ok?.let { put("ok", FieldValue(JsonPrimitive(it), clock(october))) }
                },
        )

    private fun upNextRecordWithPresence(
        key: String,
        orderKey: String,
        inQueue: Boolean,
    ): RecordDto =
        upNextRecord(key, orderKey).copy(
            fields =
                mapOf(
                    "in" to FieldValue(JsonPrimitive(inQueue), clock(october)),
                    "ok" to FieldValue(JsonPrimitive(orderKey), clock(october)),
                ),
        )

    private fun podcastRecord(): RecordDto =
        RecordDto(
            coll = "podcast",
            id = JsonPrimitive(podcastSyncId),
            seq = 1,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields =
                mapOf(
                    "feedUrl" to FieldValue(JsonPrimitive("https://example.com/feed"), clock(october)),
                    "subscribed" to FieldValue(JsonPrimitive(true), clock(october)),
                    "title" to FieldValue(JsonPrimitive("Example"), clock(october)),
                ),
        )

    private fun upNextRecord(
        key: String,
        orderKey: String,
        enc: String? = null,
    ): RecordDto =
        RecordDto(
            coll = "upnext",
            id =
                buildJsonObject {
                    put("p", podcastSyncId)
                    put("k", key)
                },
            seq = 3,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields =
                mapOf(
                    "in" to FieldValue(JsonPrimitive(true), clock(october)),
                    "ok" to FieldValue(JsonPrimitive(orderKey), clock(october)),
                ),
            match = enc?.let { MatchHints(enc = it) },
            matchClocks = enc?.let { mapOf("enc" to clock(october)) } ?: emptyMap(),
        )

    /** An episode record whose played, fav and pos clocks all carry the same millis. */
    private fun episodeRecordWithEqualClocks(at: Long): RecordDto =
        RecordDto(
            coll = "episode",
            id =
                buildJsonObject {
                    put("p", podcastSyncId)
                    put("k", "g:one")
                },
            seq = 2,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields =
                mapOf(
                    "played" to FieldValue(JsonPrimitive(true), clock(at)),
                    "playedAt" to FieldValue(JsonPrimitive(at), clock(at)),
                    "fav" to FieldValue(JsonPrimitive(true), clock(at)),
                    "pos" to FieldValue(buildJsonObject { put("ms", at) }, clock(at)),
                ),
            match = MatchHints(guid = "one", title = "One"),
            matchClocks = mapOf("guid" to clock(at).toString()),
        )

    private fun episodeRecord(): RecordDto =
        RecordDto(
            coll = "episode",
            id =
                buildJsonObject {
                    put("p", podcastSyncId)
                    put("k", "g:one")
                },
            seq = 2,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields =
                mapOf(
                    // The server's October "unplayed" against a local June played mark.
                    "played" to FieldValue(JsonPrimitive(false), clock(october)),
                    "playedAt" to FieldValue(JsonNull, clock(october)),
                    "pos" to FieldValue(buildJsonObject { put("ms", 1_790_000_000_100L) }, clock(1_790_000_000_100L)),
                ),
            match = MatchHints(guid = "one", title = "One"),
            matchClocks = mapOf("guid" to clock(october).toString()),
        )

    private fun clock(atMs: Long): String = Hlc.of(atMs, 1, remote).toString()

    /**
     * The shared podcast's `g:srv1`/`g:srv2`/`g:local`/`g:alias` episodes and a local-only
     * podcast's `g:lone`; `g:srv1` is already queued at a local order key, `g:local`, `g:alias`
     * and `g:lone` are queued around it. `g:alias`'s enclosure matches the `g:wire` record.
     */
    private suspend fun seedLocalQueue(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        val shared =
            db
                .podcastDao()
                .insertPodcast(
                    podcastEntity(
                        syncId = podcastSyncId,
                        feedUrl = "https://example.com/feed",
                        feedKey = "https://example.com/feed",
                        title = "Example",
                    ),
                )
        val localOnly =
            db
                .podcastDao()
                .insertPodcast(
                    podcastEntity(
                        syncId = localOnlySyncId,
                        feedUrl = "https://example.com/lone",
                        feedKey = "https://example.com/lone",
                        title = "Local only",
                    ),
                )
        val episodes =
            db
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(podcastId = shared, identityKey = "g:srv1"),
                        episodeEntity(podcastId = shared, identityKey = "g:srv2"),
                        episodeEntity(podcastId = shared, identityKey = "g:local"),
                        episodeEntity(podcastId = localOnly, identityKey = "g:lone"),
                        episodeEntity(podcastId = shared, identityKey = "g:alias") {
                            copy(enclosureUrl = "https://cdn.example.com/alias.mp3")
                        },
                    ),
                )
        db.queueDao().insert(queueEntryEntity(episodes[2], orderKey = "a0"))
        db.queueDao().insert(queueEntryEntity(episodes[0], orderKey = "a5"))
        db.queueDao().insert(queueEntryEntity(episodes[4], orderKey = "a8"))
        db.queueDao().insert(queueEntryEntity(episodes[3], orderKey = "a9"))
    }

    private suspend fun queuedIdentityKeys(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase): List<String> =
        db.queueDao().entries().mapNotNull { db.episodeDao().byId(it.episodeId)?.identityKey }
}

/** A [SyncClient] that serves a fixed page and counts `changes` calls. */
private class CountingClient(
    private val records: List<RecordDto>,
    private val hasMore: Boolean = false,
) : SyncClient {
    var changesCalls = 0
        private set

    override suspend fun discover(baseUrl: String): DiscoveryDocument = unsupported()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        device: DeviceInfoDto,
    ): TokenResponse = unsupported()

    override suspend fun redeemInvite(
        baseUrl: String,
        code: String,
        device: DeviceInfoDto,
    ): TokenResponse = unsupported()

    override suspend fun linkStart(
        baseUrl: String,
        device: DeviceInfoDto,
    ): LinkStartResponse = unsupported()

    override suspend fun linkToken(
        baseUrl: String,
        deviceCode: String,
    ): TokenResponse = unsupported()

    override suspend fun linkApprove(
        baseUrl: String,
        token: String,
        userCode: String,
    ): LinkApproveResponse = unsupported()

    override suspend fun linkConfirm(
        baseUrl: String,
        token: String,
        userCode: String,
        approve: Boolean,
    ) = unsupported()

    override suspend fun logout() = unsupported()

    override suspend fun devices(): List<DeviceDto> = unsupported()

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): DeviceDto = unsupported()

    override suspend fun revokeDevice(deviceId: String) = unsupported()

    override suspend fun account(): AccountSummary = unsupported()

    override suspend fun sync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse =
        SyncResponse(
            results = emptyList(),
            records = emptyList(),
            cursor = "c:00000001:1",
            hasMore = false,
            serverTime = "",
        )

    override suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage {
        changesCalls++
        // A `since` cursor answers empty: the fixed page is the whole snapshot.
        return if (since == null) {
            ChangesPage(records = records, cursor = "c:00000001:1", hasMore = hasMore, serverTime = "")
        } else {
            ChangesPage(records = emptyList(), cursor = "c:00000001:1", hasMore = hasMore, serverTime = "")
        }
    }

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}

/** A [SyncClient] that serves a fixed page, for the first-link tests. */
private class ScriptedClient(
    private val records: List<RecordDto>,
    private val firstPageHasMore: Boolean = false,
    private val failAtPage: Int? = null,
    private val failure: () -> Throwable = { java.io.IOException("sync.example unreachable") },
) : SyncClient {
    private var pages = 0

    override suspend fun discover(baseUrl: String): DiscoveryDocument = unsupported()

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        device: DeviceInfoDto,
    ): TokenResponse = unsupported()

    override suspend fun redeemInvite(
        baseUrl: String,
        code: String,
        device: DeviceInfoDto,
    ): TokenResponse = unsupported()

    override suspend fun linkStart(
        baseUrl: String,
        device: DeviceInfoDto,
    ): LinkStartResponse = unsupported()

    override suspend fun linkToken(
        baseUrl: String,
        deviceCode: String,
    ): TokenResponse = unsupported()

    override suspend fun linkApprove(
        baseUrl: String,
        token: String,
        userCode: String,
    ): LinkApproveResponse = unsupported()

    override suspend fun linkConfirm(
        baseUrl: String,
        token: String,
        userCode: String,
        approve: Boolean,
    ) = unsupported()

    override suspend fun logout() = unsupported()

    override suspend fun devices(): List<DeviceDto> = unsupported()

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ): DeviceDto = unsupported()

    override suspend fun revokeDevice(deviceId: String) = unsupported()

    override suspend fun account(): AccountSummary = unsupported()

    override suspend fun sync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse =
        SyncResponse(
            results = emptyList(),
            records = records,
            cursor = "c:00000001:1",
            hasMore = false,
            serverTime = "",
        )

    override suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage {
        val page = pages++
        if (page == failAtPage) throw failure()
        return ChangesPage(
            records = records,
            cursor = "c:00000001:1",
            hasMore = firstPageHasMore && page == 0,
            serverTime = "",
        )
    }

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}
