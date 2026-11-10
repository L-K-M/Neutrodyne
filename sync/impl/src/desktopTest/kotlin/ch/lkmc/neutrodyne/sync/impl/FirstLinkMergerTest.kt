// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.LibraryMerger
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeLibraryMerger
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
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
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
    private val localNode = NodeId("0000000000000001")
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
     * The first link re-primes the volatile share gate before the merge's apply steps run —
     * `engine.round` is only reached at the upload step, long after the pull staged the
     * received credentials, so the persisted consent must be read at the merge's entry (10
     * Settings capture / Passwords). The probe observes the flag from inside `mergeStaged`,
     * and the pushed `auth` intent proves the hydrated gate carries bytes. No real network —
     * the scripted client captures the DTOs.
     */
    @Test
    fun theFirstLinkHydratesTheShareGateBeforeApplyingReceivedCredentials() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, deviceId = device)
                val flags = SyncFlags()
                val address = FakeSyncServerAddress(serverUrl = "https://sync.example")
                address.updateShareFeedPasswords(true)
                val secrets = FakeSecretStore()
                // A credential feed whose un-pushed local `auth` intent predates this restart.
                val podcastId =
                    db.podcastDao().insertPodcast(
                        podcastEntity(
                            syncId = podcastSyncId,
                            feedUrl = "$AUTH_ORIGIN/feed",
                            feedKey = "$AUTH_ORIGIN/feed",
                        ),
                    )
                val credentialId = secrets.put(AUTH_ORIGIN, AUTH_USER, AUTH_PASS.toCharArray())!!
                db
                    .credentialDao()
                    .insert(
                        CredentialEntity(
                            id = credentialId,
                            origin = AUTH_ORIGIN,
                            username = AUTH_USER,
                            secretCipher = null,
                            iv = null,
                            createdAt = now,
                        ),
                    )
                val intent = Hlc.of(now, 2, localNode)
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        db.podcastDao().setCredentialId(podcastId, credentialId)
                        db.syncOutboxDao().upsert(
                            SyncOutboxEntity(
                                coll = "podcast",
                                rid = podcastSyncId,
                                field = "auth",
                                hlc = intent.packed,
                                nodeId = localNode.value,
                            ),
                        )
                    }
                }
                val gateReads = mutableListOf<Boolean>()
                val library =
                    object : ch.lkmc.neutrodyne.core.domain.LibraryMerger {
                        override suspend fun mergeStaged(
                            stagingDir: String,
                            policy: MergePolicy,
                            onUnmatched: suspend (String, String) -> Unit,
                            progress: (Int, Int) -> Unit,
                            onApplied: (suspend (ch.lkmc.neutrodyne.core.model.MergedRecord) -> Unit)?,
                        ): Outcome<
                            ch.lkmc.neutrodyne.core.model.MergeSummary,
                            ch.lkmc.neutrodyne.core.domain.BackupError,
                        > {
                            gateReads += flags.sharePasswords
                            return Outcome.Success(
                                ch.lkmc.neutrodyne.core.model
                                    .MergeSummary(0, 0, 0, 0, 0, 0, 0, 0),
                            )
                        }
                    }
                val sent = mutableListOf<ChangeDto>()
                val client =
                    ScriptedClient(
                        listOf(podcastRecord()),
                        syncHandler = { since, changes ->
                            sent += changes
                            ackingSync(since, changes)
                        },
                    )
                val activity = SyncActivityState()
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = client,
                        applier = applier(db),
                        engine = engine(db, client, activity, flags, address, secrets),
                        libraryMerger = library,
                        storagePaths = paths,
                        effects = newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = secrets,
                        coordinator = FakeCredentialCommitCoordinator(),
                        beforeMerge = { flags.refreshFromStore(address) },
                    )

                assertFalse(flags.sharePasswords, "a restart left the cache at its default")
                val result = merger.merge(FirstLinkChoice.MERGE)

                assertTrue(result is FirstLinkResult.Completed)
                assertEquals(
                    listOf(true),
                    gateReads,
                    "the merge's apply steps must see the persisted gate",
                )
                val pushed =
                    sent.first {
                        it.coll == "podcast" && it.fields.containsKey("auth")
                    }
                assertEquals(
                    buildJsonObject {
                        put("u", AUTH_USER)
                        put("p", AUTH_PASS)
                    },
                    pushed.fields
                        .getValue("auth")
                        .v,
                    "the consented credential rides the first link's upload",
                )
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
     * 10 Merge step 2: the server's `youtubeVariants` rides into the staging podcast. The
     * default-1 insert path otherwise drops the user's Shorts/Live choice permanently, and no
     * later pull corrects it (the pull applier had no branch either).
     */
    @Test
    fun mergeStagesTheServerYoutubeVariants() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val record =
                    podcastRecord().copy(
                        fields =
                            podcastRecord().fields +
                                ("youtubeVariants" to FieldValue(JsonPrimitive(3), clock(october))),
                    )
                val client = CountingClient(listOf(record))
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val staged = readStagedLibrary(library.calls.single().stagingDir)
                assertEquals(3, staged.podcasts.single().youtubeVariants)
            } finally {
                db.close()
            }
        }

    /**
     * 10 Merge step 2: the server's session record carries the `episode` ref and the
     * `context` object (never the flat names the old mapper read). The staged session adopts
     * the server's current episode, context type/group, order, filters, min-sort date and
     * anchor, so LINK_REPLACE no longer clears a local current episode the server had.
     */
    @Test
    fun mergeStagesTheServerSessionWithCurrentAndContext() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val groupA = "11111111-1111-4111-8111-111111111111"
                val client = CountingClient(listOf(podcastRecord(), sessionRecord(groupA)))
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val queue = readStagedQueue(library.calls.single().stagingDir)
                val session = assertNotNull(queue.session)
                assertEquals("example.com/feed", session.current?.p)
                assertEquals("g:one", session.current?.k)
                assertEquals("GROUP", session.contextType)
                assertEquals(groupA, session.contextGroupUuid)
                assertEquals("OLDEST_FIRST", session.order)
                assertEquals(3, session.filterFlags)
                assertEquals("AUDIO", session.mediaFilter)
                assertEquals(5L, session.minSortDate)
                assertEquals("g:one", session.anchor?.k)
            } finally {
                db.close()
            }
        }

    /**
     * 10 Merge step 2: the pull is in server `seq` order, so a member, episode or Up-next
     * record can precede its parent podcast. The mapper resolves podcasts first, so the
     * children land even on a genuinely empty local DB — a later remote edit is not needed.
     */
    @Test
    fun mergeMapsChildrenThatPrecedeTheirParentPodcast() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            try {
                link(db, deviceId = device)
                val groupA = "11111111-1111-4111-8111-111111111111"
                val client =
                    CountingClient(
                        listOf(
                            groupRecord(groupA, "Group A", ok = "a0"),
                            memberRecord(groupA, podcastSyncId, ok = "a0"),
                            episodeRecord(),
                            upNextRecord("g:one", orderKey = "a0"),
                            podcastRecord(),
                        ),
                    )
                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                val merger = mergerOf(db, client, library)

                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val staged = readStagedLibrary(library.calls.single().stagingDir)
                assertEquals(
                    listOf("example.com/feed"),
                    staged.groups
                        .single()
                        .members
                        .map { it.podcastKey },
                )
                val episode = readStagedEpisodeLine(library.calls.single().stagingDir)
                assertEquals("example.com/feed", episode?.p)
                val queue = readStagedQueue(library.calls.single().stagingDir)
                assertEquals("example.com/feed", queue.upNext.single().p)
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

    /**
     * 10 Passwords / Merge step 1: the staging line carries the protected snapshot's
     * reference and clock, never the wire `{u,p}` — and the resume rehydrates the field from
     * the `SecretStore`, so the journaled marker is bound end to end.
     */
    @Test
    fun theStagingFileCarriesOnlyTheProtectedAuthReference() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            val secrets = FakeSecretStore()
            val stagedPass = "s3ntinel-link-3c7f"
            val client = CountingClient(listOf(authPodcastRecord(stagedPass)))
            try {
                link(db, deviceId = device)
                val merger = mergerOf(db, client, library, secrets = secrets)

                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val stagedText =
                    fileSystem.read(cache.resolve("sync-link-$device.jsonl").toString().toPath()) {
                        readUtf8()
                    }
                assertFalse(stagedText.contains(stagedPass))
                assertFalse(stagedText.contains("feed-user"))
                assertTrue(stagedText.contains("\"authRef\""))
                assertTrue(stagedText.contains("\"authClock\""))

                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Success(
                        ch.lkmc.neutrodyne.core.model
                            .MergeSummary(0, 0, 0, 0, 0, 0, 0, 0),
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Completed)
                assertEquals(1, client.changesCalls, "the resume reused the staged auth lines")

                // The rehydrated auth field journaled the durable marker and reference at
                // the snapshot's own key; the snapshot itself survived the whole flow.
                val raw = decodeRawClocks(db.syncClockDao().get("podcast", podcastSyncId)!!.clocks)!!
                val pending = raw.pendingAuth!!
                val key = pendingAuthKey("local", podcastSyncId, pending.clock)
                assertEquals(key, pending.reference)
                assertEquals(key, raw.authReference!!.storeKey)
                val snapshot = secrets.get(key)
                assertNotNull(snapshot)
                assertEquals(stagedPass, snapshot.secret.concatToString())
            } finally {
                db.close()
            }
        }

    /**
     * 10 Passwords / origin association: an auth whose companion credentialOrigin sits at a
     * different complete clock proves nothing — it stages no snapshot, and the staging line
     * still drops the field rather than leaking its plaintext to disk.
     */
    @Test
    fun anUnpairedAuthNeverReachesTheStagingFileOrRawState() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            val secrets = FakeSecretStore()
            val stagedPass = "s3ntinel-link-9e4d"
            val client = CountingClient(listOf(unpairedAuthPodcastRecord(stagedPass)))
            try {
                link(db, deviceId = device)
                val merger = mergerOf(db, client, library, secrets = secrets)

                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Failure(
                        ch.lkmc.neutrodyne.core.domain.BackupError.Storage,
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Failed)

                val stagedText =
                    fileSystem.read(cache.resolve("sync-link-$device.jsonl").toString().toPath()) {
                        readUtf8()
                    }
                assertFalse(stagedText.contains(stagedPass))
                assertFalse(stagedText.contains("feed-user"))
                assertFalse(stagedText.contains("\"authRef\""))
                assertFalse(stagedText.contains("\"authClock\""))

                library.result =
                    ch.lkmc.neutrodyne.core.common.Outcome.Success(
                        ch.lkmc.neutrodyne.core.model
                            .MergeSummary(0, 0, 0, 0, 0, 0, 0, 0),
                    )
                assertTrue(merger.merge(FirstLinkChoice.MERGE) is FirstLinkResult.Completed)

                val raw = decodeRawClocks(db.syncClockDao().get("podcast", podcastSyncId)!!.clocks)!!
                assertNull(raw.pendingAuth)
                assertNull(raw.authReference)
                assertTrue(secrets.originsWithPrefix(PENDING_AUTH_PREFIX).isEmpty())
            } finally {
                db.close()
            }
        }

    /**
     * "Use the server's library" applies the same proof to the seeded reference: an
     * unpaired server auth seeds no reference and no durable marker.
     */
    @Test
    fun useServerSeedsNoReferenceForAnUnpairedAuth() =
        runTest {
            val db = TestDb.inMemory()
            val library = FakeLibraryMerger()
            val secrets = FakeSecretStore()
            val client = CountingClient(listOf(unpairedAuthPodcastRecord("s3ntinel-use-server")))
            try {
                link(db, deviceId = device)
                val merger = mergerOf(db, client, library, secrets = secrets)

                assertTrue(merger.merge(FirstLinkChoice.USE_SERVER) is FirstLinkResult.Completed)

                val raw = decodeRawClocks(db.syncClockDao().get("podcast", podcastSyncId)!!.clocks)!!
                assertNull(raw.pendingAuth)
                assertNull(raw.authReference)
                assertTrue(secrets.originsWithPrefix(PENDING_AUTH_PREFIX).isEmpty())
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

    @Test
    fun adoptingTheServerLibraryKeepsTheCorrectedWallClock() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val offset = -21_600_000L
                db.syncStateDao().setClockOffset(offset)
                val merger = mergerOf(db, CountingClient(emptyList()), FakeLibraryMerger())
                assertTrue(merger.merge(FirstLinkChoice.USE_SERVER) is FirstLinkResult.Completed)
                val state = checkNotNull(db.syncStateDao().get())
                assertEquals(offset, state.clockOffsetMs)
                assertEquals(
                    (now + offset) shl Hlc.COUNTER_BITS,
                    state.hlc,
                    "first-link adoption must not restore the uncorrected future clock",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun firstLinkPullSamplesTheServerClockBeforeAdoption() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val offset = -21_600_000L
                val base = CountingClient(emptyList())
                val timed =
                    object : SyncClient by base {
                        override suspend fun changes(
                            since: String?,
                            limit: Int,
                        ): ChangesPage =
                            base.changes(since, limit).copy(
                                serverTime =
                                    kotlin.time.Instant
                                        .fromEpochMilliseconds(now + offset)
                                        .toString(),
                            )
                    }
                val merger = mergerOf(db, timed, FakeLibraryMerger())
                assertTrue(merger.merge(FirstLinkChoice.USE_SERVER) is FirstLinkResult.Completed)
                val state = checkNotNull(db.syncStateDao().get())
                assertEquals(offset, state.clockOffsetMs, "staging pulls also correct the clock")
                assertEquals((now + offset) shl Hlc.COUNTER_BITS, state.hlc)
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

    /**
     * 10 Durable apply effects at the link + 01 Sync write ordering: the merge's settings
     * drain takes the same per-key write lock the local repository takes, so a local set
     * that arrives while the drain applies a remote value waits for the lock and then
     * records the newest intent. A drain bound to a different coordinator would let the
     * local write run inside the apply window — this test fails in that wiring.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun mergeDrainsSettingsUnderTheSharedWriteLock() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val remoteClock = Hlc.of(1_577_836_800_000L, 1, remote)
                db
                    .syncStateDao()
                    .upsert(
                        SyncStateEntity(
                            enabled = true,
                            deviceId = device,
                            nodeId = "0000000000000001",
                            hlc = remoteClock.packed,
                            linkedAt = 1L,
                        ),
                    )
                val port = StoringSettingsSyncPort()
                val coordinator = TestSettingsWriteCoordinator()
                SettingsCapture(db, port, coordinator) { true }.install()
                val applyReached = CompletableDeferred<Unit>()
                val releaseApply = CompletableDeferred<Unit>()
                port.applyReached = applyReached
                port.releaseApply = releaseApply
                val client =
                    ScriptedClient(
                        listOf(settingRecord(key, 15_000L, remoteClock)),
                        syncHandler = ::ackingSync,
                    )
                val merger =
                    mergerOf(
                        db,
                        client,
                        FakeLibraryMerger(),
                        effects = newEffects(db, port, coordinator),
                    )

                val mergeJob = backgroundScope.launch { merger.merge(FirstLinkChoice.MERGE) }
                // The drain holds the per-key lock while it applies the remote value.
                applyReached.await()

                // A local set mid-drain: the Room intent first, then the DataStore write,
                // under the same per-key lock the drain holds (01's ordering).
                val stored = CompletableDeferred<Unit>()
                val localJob =
                    backgroundScope.launch {
                        coordinator.withLock(key) {
                            db.withWriteTransaction { port.recordLocal(key, 20_000L) }
                            port.storeLocal(key, "20000")
                            stored.complete(Unit)
                        }
                    }
                // A second `withLock` arrival proves the local write reached the shared
                // lock; the drain holding a different coordinator would time out here.
                val reachedLock =
                    withTimeoutOrNull(1_000) {
                        coordinator.secondAttempt.await()
                        true
                    } == true
                assertTrue(reachedLock, "the local write must reach the shared per-key lock")
                assertFalse(
                    withTimeoutOrNull(500) {
                        stored.await()
                        true
                    } == true,
                    "the local set must wait on the per-key lock the drain holds",
                )

                releaseApply.complete(Unit)
                mergeJob.join()
                localJob.join()

                // The remote value applied once before the local intent existed; the local
                // write then recorded the newest intent and stored its value. A surviving
                // intent row (the next round ships it when the merge's own push read the
                // outbox first) must carry a clock newer than the remote's.
                assertEquals(listOf(key to "15000"), port.applied)
                assertEquals("20000", port.stored[key.name])
                assertNull(pendingSetting(db, key.name))
                val local = db.syncOutboxDao().get("setting", key.name, "v")
                assertTrue(
                    local == null || Hlc(local.hlc, NodeId(local.nodeId)) > remoteClock,
                    "a surviving local intent must carry a clock newer than the remote's",
                )
            } finally {
                db.close()
            }
        }

    /**
     * The stale-pending scenario of 01's Sync write ordering through the real merge: a
     * local set committed before the link beats the server's older value — the drain
     * never applies it, clears the superseded marker, and the upload ack retires the
     * intent, so a later drain cannot resurrect the remote value either.
     */
    @Test
    fun mergeKeepsTheNewerLocalSettingPastTheUploadAck() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val remoteClock = Hlc.of(1_577_836_800_000L, 1, remote)
                db
                    .syncStateDao()
                    .upsert(
                        SyncStateEntity(
                            enabled = true,
                            deviceId = device,
                            nodeId = "0000000000000001",
                            hlc = remoteClock.packed,
                            linkedAt = 1L,
                        ),
                    )
                val port = StoringSettingsSyncPort()
                val coordinator = TestSettingsWriteCoordinator()
                SettingsCapture(db, port, coordinator) { true }.install()
                // The local set precedes the merge, sequenced like the repository's
                // local write: the Room intent first, then the DataStore write.
                coordinator.withLock(key) {
                    db.withWriteTransaction { port.recordLocal(key, 20_000L) }
                    port.storeLocal(key, "20000")
                }
                val client =
                    ScriptedClient(
                        listOf(settingRecord(key, 15_000L, remoteClock)),
                        syncHandler = ::ackingSync,
                    )
                val merger =
                    mergerOf(
                        db,
                        client,
                        FakeLibraryMerger(),
                        effects = newEffects(db, port, coordinator),
                    )

                val result = merger.merge(FirstLinkChoice.MERGE)

                assertTrue(result is FirstLinkResult.Completed, "$result")
                assertTrue(
                    port.applied.none { it.first == key },
                    "the newer local set must win over the stale remote marker",
                )
                assertEquals("20000", port.stored[key.name])
                assertNull(pendingSetting(db, key.name))
                assertNull(
                    db.syncOutboxDao().get("setting", key.name, "v"),
                    "the upload ack must retire the pushed intent",
                )

                // A later drain (a later link merge, or the start drain once wired) sees
                // no marker and must not resurrect the stale remote value.
                newEffects(db, port, coordinator).drainSettings()
                assertTrue(
                    port.applied.none { it.first == key },
                    "the stale remote value must not be re-applied after the upload ack",
                )
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
                        effects =
                            newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = FakeSecretStore(),
                        coordinator = FakeCredentialCommitCoordinator(),
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
                        effects =
                            newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = FakeSecretStore(),
                        coordinator = FakeCredentialCommitCoordinator(),
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
                        effects =
                            newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = FakeSecretStore(),
                        coordinator = FakeCredentialCommitCoordinator(),
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
                        effects =
                            newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = FakeSecretStore(),
                        coordinator = FakeCredentialCommitCoordinator(),
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
                        effects =
                            newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = FakeSecretStore(),
                        coordinator = FakeCredentialCommitCoordinator(),
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

    /**
     * 10 Durable apply effects, resync half: the pull's committed `pendingSetting` drains
     * before the re-assertion, so DataStore holds the server value, and the re-assert
     * replays the stored raw clock — the server's node, counter and value — without any
     * fresh local capture of the drained value (no echo).
     */
    @Test
    fun resyncDrainsPendingSettingsBeforeTheReassertion() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val port = StoringSettingsSyncPort()
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val remoteClock = Hlc.of(october, 5, remote)
                val client = ScriptedClient(listOf(settingRecord(key, 15_000L, remoteClock)))
                val merger = mergerOf(db, client, FakeLibraryMerger(), effects = newEffects(db, port))

                val result = merger.resync()

                assertTrue(result is FirstLinkResult.Completed, "$result")
                assertEquals(listOf(key to "15000"), port.applied)
                assertNull(pendingSetting(db, key.name))
                val replay = db.syncOutboxDao().get("setting", key.name, "v")
                assertNotNull(replay, "the re-assertion replays the setting with its stored clock")
                assertEquals(remoteClock.packed, replay.hlc)
                assertEquals(remote.value, replay.nodeId)
                assertEquals(ch.lkmc.neutrodyne.core.database.SyncCaptureKind.REPLAY, replay.captureKind)
                assertEquals("15000", replay.value)
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
                        effects =
                            newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = FakeSecretStore(),
                        coordinator = FakeCredentialCommitCoordinator(),
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
                        effects =
                            newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = FakeSecretStore(),
                        coordinator = FakeCredentialCommitCoordinator(),
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
                        effects =
                            newEffects(db),
                        clock = TestClock(now),
                        activity = activity,
                        secrets = FakeSecretStore(),
                        coordinator = FakeCredentialCommitCoordinator(),
                    )
                assertFailsWith<CancellationException> { merger.resync() }
                assertEquals(0, activity.count.value, "a cancelled resync must clear the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    @Test
    fun resyncAdoptsTheCursorOnlyAfterTheLastPage() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                db.syncStateDao().storeCursor("c:00000009:3")
                val cursors = mutableListOf<String?>()
                val client =
                    object : SyncClient by ScriptedClient(emptyList()) {
                        override suspend fun changes(
                            since: String?,
                            limit: Int,
                        ): ChangesPage {
                            cursors += since
                            assertEquals("c:00000009:3", db.syncStateDao().get()?.cursor)
                            return ChangesPage(
                                emptyList(),
                                if (since ==
                                    null
                                ) {
                                    "c:0000000a:1"
                                } else {
                                    "c:0000000a:2"
                                },
                                since == null,
                                "",
                            )
                        }
                    }
                val result = mergerOf(db, client, FakeLibraryMerger()).resync()
                assertTrue(result is FirstLinkResult.Completed, "$result")
                assertEquals(listOf(null, "c:0000000a:1"), cursors)
                assertEquals("c:0000000a:2", db.syncStateDao().get()?.cursor)
            } finally {
                db.close()
            }
        }

    @Test
    fun resyncDiscardsAPageAfterTheLinkIdentityChanges() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val client =
                    object : SyncClient by ScriptedClient(emptyList()) {
                        override suspend fun changes(
                            since: String?,
                            limit: Int,
                        ): ChangesPage {
                            db.withWriteTransaction { db.syncStateDao().unlink() }
                            return ChangesPage(
                                listOf(settingRecord(key, 15_000L, Hlc.of(october, 5, remote))),
                                "c:0000000a:2",
                                false,
                                "",
                            )
                        }
                    }
                val result = mergerOf(db, client, FakeLibraryMerger()).resync()
                assertTrue(result is FirstLinkResult.Failed, "$result")
                assertNull(db.syncStateDao().get()?.cursor)
                assertTrue(db.syncClockDao().all().isEmpty(), "a stale page must not seed the unlinked profile")
            } finally {
                db.close()
            }
        }

    @Test
    fun resyncReplaysEveryKeyPageAndSkipsAMalformedTail() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val stamp = Hlc.of(october, 7, remote)
                db.withWriteTransaction {
                    repeat(300) { index ->
                        val raw =
                            RawSyncRecord(
                                ch.lkmc.neutrodyne.sync.protocol.RecordState(
                                    ch.lkmc.neutrodyne.sync.protocol.SyncCollection.SETTING,
                                    mapOf(
                                        "v" to
                                            ch.lkmc.neutrodyne.sync.protocol
                                                .FieldState(JsonPrimitive(index), stamp),
                                    ),
                                ),
                            )
                        db.syncClockDao().upsert(
                            ch.lkmc.neutrodyne.core.database
                                .SyncClockEntity(
                                    "setting",
                                    "page.$index",
                                    ch.lkmc.neutrodyne.sync.protocol.SyncJson
                                        .encodeToString(raw),
                                ),
                        )
                    }
                    db.syncClockDao().upsert(
                        ch.lkmc.neutrodyne.core.database
                            .SyncClockEntity("setting", "zz.invalid", "not-json"),
                    )
                }
                val result = mergerOf(db, ScriptedClient(emptyList()), FakeLibraryMerger()).resync()
                assertTrue(result is FirstLinkResult.Completed, "$result")
                val captured = db.syncOutboxDao().rows()
                assertEquals(300, captured.size)
                assertEquals((0 until 300).map { "page.$it" }.toSet(), captured.map { it.rid }.toSet())
                assertTrue(captured.all { it.hlc == stamp.packed && it.nodeId == remote.value })
            } finally {
                db.close()
            }
        }

    @Test
    fun aFullResyncPublishesLargeRemovalsThroughTheGuard() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val ids = (1..5).map { "00000000-0000-4000-8000-" + it.toString().padStart(12, '0') }
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        ids.forEachIndexed {
                            index,
                            id,
                            ->
                            db.podcastDao().insertPodcast(
                                podcastEntity(
                                    syncId = id,
                                    feedUrl = "https://feed$index.example/rss",
                                    feedKey = "feed$index.example/rss",
                                ),
                            )
                        }
                    }
                }
                val stamp = Hlc.of(october, 7, remote)
                val records =
                    ids.take(3).mapIndexed { index, id ->
                        RecordDto(
                            "podcast",
                            JsonPrimitive(id),
                            index.toLong(),
                            null,
                            mapOf("subscribed" to FieldValue(JsonPrimitive(false), stamp.toString())),
                        )
                    }
                val result = mergerOf(db, ScriptedClient(records), FakeLibraryMerger()).resync()
                assertTrue(result is FirstLinkResult.Completed, "$result")
                val prompts = db.syncHeldDao().all().mapNotNull { MassChangeGuard.heldBatch(it.batch) }
                assertEquals(3, prompts.single().podcasts.size)
                assertEquals(5, db.podcastDao().allForExport().size, "held removals must not delete the local library")
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerPendingPlayedPairNeverGetsAnOlderReplayTimestamp() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val old = Hlc.of(october, 7, remote)
                val pending = Hlc.of(now, 3, localNode)
                val rid = podcastSyncId + "g:one"
                db.withWriteTransaction {
                    val raw =
                        RawSyncRecord(
                            ch.lkmc.neutrodyne.sync.protocol.RecordState(
                                ch.lkmc.neutrodyne.sync.protocol.SyncCollection.EPISODE,
                                mapOf(
                                    "played" to
                                        ch.lkmc.neutrodyne.sync.protocol
                                            .FieldState(JsonPrimitive(true), old),
                                    "playedAt" to
                                        ch.lkmc.neutrodyne.sync.protocol
                                            .FieldState(JsonPrimitive(october), old),
                                ),
                            ),
                        )
                    db.syncClockDao().upsert(
                        ch.lkmc.neutrodyne.core.database
                            .SyncClockEntity(
                                "episode",
                                rid,
                                ch.lkmc.neutrodyne.sync.protocol.SyncJson
                                    .encodeToString(raw),
                            ),
                    )
                    db.syncOutboxDao().upsert(
                        SyncOutboxEntity(
                            "episode",
                            rid,
                            "played",
                            pending.packed,
                            localNode.value,
                            value = """{"played":false,"playedAt":null}""",
                        ),
                    )
                }
                val result = mergerOf(db, ScriptedClient(emptyList()), FakeLibraryMerger()).resync()
                assertTrue(result is FirstLinkResult.Completed, "$result")
                val changes = ChangeBuilder(db, FakeSecretStore()) { false }.build(db.syncOutboxDao().rows())
                assertEquals(JsonPrimitive(false), changes.single().fields["played"]?.v)
                assertEquals(JsonNull, changes.single().fields["playedAt"]?.v)
                assertEquals(pending.toString(), changes.single().fields["playedAt"]?.c)
            } finally {
                db.close()
            }
        }

    @Test
    fun aStaleRecoveryPushCannotAcknowledgeANewLink() =
        runTest {
            val db = TestDb.inMemory()
            val activity = SyncActivityState()
            try {
                link(db, enabled = true)
                db.withWriteTransaction {
                    val state = checkNotNull(db.syncStateDao().get())
                    db.syncStateDao().upsert(
                        state.copy(serverUrl = "https://sync.example", accountId = "a", linkedAt = now),
                    )
                }
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                var pushes = 0
                val client =
                    object : SyncClient by ScriptedClient(emptyList()) {
                        override suspend fun sync(
                            since: String?,
                            changes: List<ChangeDto>,
                        ): SyncResponse {
                            if (pushes++ == 0) throw SyncTransportException(410, "cursor_expired", "fixture")
                            db.withWriteTransaction {
                                db.syncStateDao().unlink()
                                db.syncStateDao().upsert(
                                    SyncStateEntity(
                                        enabled = true,
                                        serverUrl = "https://sync.example",
                                        accountId = "b",
                                        deviceId = "b-device",
                                        nodeId = "0000000000000003",
                                        linkedAt =
                                            now + 1,
                                        cursor = "c:0000000b:1",
                                    ),
                                )
                                db.syncOutboxDao().upsert(
                                    SyncOutboxEntity(
                                        "setting",
                                        key.name,
                                        "v",
                                        Hlc.of(june, 0, NodeId("0000000000000003")).packed,
                                        "0000000000000003",
                                        value = "30000",
                                    ),
                                )
                            }
                            return ackingSync(since, changes)
                        }
                    }
                lateinit var merger: FirstLinkMerger
                val engine =
                    engine(db, client, activity, recover = {
                        profile,
                        scoped,
                        ->
                        merger.resyncForRound(profile, scoped)
                    })
                merger =
                    FirstLinkMerger(
                        db,
                        client,
                        applier(db),
                        engine,
                        FakeLibraryMerger(),
                        paths,
                        newEffects(db),
                        TestClock(now),
                        activity,
                        FakeSecretStore(),
                        FakeCredentialCommitCoordinator(),
                    )
                assertFailsWith<IllegalStateException> {
                    engine.round(
                        ch.lkmc.neutrodyne.sync.api.SyncRoundReason.SYNC_NOW,
                    )
                }
                assertEquals(
                    "c:0000000b:1",
                    db.syncStateDao().get()?.cursor,
                    "the old response must not advance the new link",
                )
                assertEquals(
                    "30000",
                    db.syncOutboxDao().get("setting", key.name, "v")?.value,
                    "the old ACK must not delete the new link's intent",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aSavedAddressForAnotherProfileCannotSendTheLinkedOutbox() =
        runTest {
            val db = TestDb.inMemory()
            val activity = SyncActivityState()
            try {
                link(db, enabled = true)
                db.withWriteTransaction {
                    val state = checkNotNull(db.syncStateDao().get())
                    db.syncStateDao().upsert(state.copy(serverUrl = "https://original.example", linkedAt = now))
                }
                val sent = mutableListOf<ChangeDto>()
                val client =
                    ScriptedClient(emptyList(), syncHandler = { since, changes ->
                        sent += changes
                        ackingSync(since, changes)
                    })
                val engine =
                    engine(
                        db,
                        client,
                        activity,
                        address = FakeSyncServerAddress(serverUrl = "https://replacement.example"),
                    )

                val result = runCatching { engine.round(ch.lkmc.neutrodyne.sync.api.SyncRoundReason.SYNC_NOW) }

                assertTrue(sent.isEmpty(), "The linked account's writes reached the replacement transport: $sent")
                assertNotNull(result.exceptionOrNull(), "A mismatched persisted identity must fail closed")
                assertEquals("https://original.example", db.syncStateDao().get()?.serverUrl)
                assertEquals(0, activity.count.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun aRejectedReplayCannotAnnounceSuccessfulRecovery() =
        runTest {
            val db = TestDb.inMemory()
            val activity = SyncActivityState()
            try {
                link(db, enabled = true)
                db.withWriteTransaction {
                    val state = checkNotNull(db.syncStateDao().get())
                    db.syncStateDao().upsert(state.copy(serverUrl = "https://sync.example", linkedAt = now))
                }
                val key = PlaybackSettingKeys.SKIP_FORWARD_MS
                val replayClock = Hlc.of(now + 1_000, 7, remote)
                var pushes = 0
                val client =
                    ScriptedClient(
                        listOf(settingRecord(key, 15_000L, replayClock)),
                        syncHandler = { _, changes ->
                            pushes++
                            if (pushes == 1) throw SyncTransportException(410, "cursor_expired", "fixture")
                            val results =
                                changes.mapIndexed { index, _ ->
                                    ChangeResultDto(index, "rejected", code = "clock_skew")
                                }
                            SyncResponse(results, emptyList(), "c:0000000a:1", false, "")
                        },
                    )
                val bus = SyncNoticeBus()
                val published = mutableListOf<ch.lkmc.neutrodyne.sync.api.SyncNotice>()
                backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    bus.notices.collect { published += it }
                }
                lateinit var merger: FirstLinkMerger
                val engine =
                    engine(
                        db,
                        client,
                        activity,
                        noticeBus = bus,
                        recover = { profile, scoped -> merger.resyncForRound(profile, scoped) },
                    )
                merger =
                    FirstLinkMerger(
                        db,
                        client,
                        applier(db),
                        engine,
                        FakeLibraryMerger(),
                        paths,
                        newEffects(db),
                        TestClock(now),
                        activity,
                        FakeSecretStore(),
                        FakeCredentialCommitCoordinator(),
                    )

                val result = runCatching { engine.round(ch.lkmc.neutrodyne.sync.api.SyncRoundReason.SYNC_NOW) }
                runCurrent()

                assertTrue(published.isEmpty(), "A rejected replay must not announce success: $published")
                assertNotNull(result.exceptionOrNull(), "The rejection must be visible to the caller")
                assertEquals(2, pushes, "Do not resend an unchanged rejected replay in the same round")
                val replay = assertNotNull(db.syncOutboxDao().get("setting", key.name, "v"))
                assertEquals(ch.lkmc.neutrodyne.core.database.SyncCaptureKind.REPLAY, replay.captureKind)
                assertEquals(replayClock.packed, replay.hlc, "Rejected replays keep the original complete clock")
                assertEquals(replayClock.nodeId.value, replay.nodeId)
                assertEquals(0, activity.count.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun unauthorizedDuringFullRecoveryRevokesTheSameLink() =
        runTest {
            val db = TestDb.inMemory()
            val activity = SyncActivityState()
            try {
                link(db, enabled = true)
                db.withWriteTransaction {
                    val state = checkNotNull(db.syncStateDao().get())
                    db.syncStateDao().upsert(state.copy(serverUrl = "https://sync.example", linkedAt = now))
                }
                val client =
                    ScriptedClient(emptyList(), failAtPage = 0, failure = {
                        SyncTransportException(401, "unauthorized", "fixture")
                    }, syncHandler = {
                        _,
                        _,
                        ->
                        throw SyncTransportException(410, "cursor_expired", "fixture")
                    })
                lateinit var merger: FirstLinkMerger
                val engine =
                    engine(db, client, activity, recover = {
                        profile,
                        scoped,
                        ->
                        merger.resyncForRound(profile, scoped)
                    })
                merger =
                    FirstLinkMerger(
                        db,
                        client,
                        applier(db),
                        engine,
                        FakeLibraryMerger(),
                        paths,
                        newEffects(db),
                        TestClock(now),
                        activity,
                        FakeSecretStore(),
                        FakeCredentialCommitCoordinator(),
                    )
                val failure =
                    assertFailsWith<SyncTransportException> {
                        engine.round(
                            ch.lkmc.neutrodyne.sync.api.SyncRoundReason.SYNC_NOW,
                        )
                    }
                assertEquals(401, failure.status)
                assertEquals(SYNC_ERROR_REVOKED, db.syncStateDao().get()?.lastError)
                assertEquals(0, activity.count.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun resyncKeepsTheExpiredCursorUntilReplayIsDurable() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db, enabled = true)
                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val stamp = Hlc.of(october, 7, remote)
                val raw = RawSyncRecord(ProtocolValidation.record(settingRecord(key, 15_000L, stamp)))
                db.withWriteTransaction {
                    db.syncStateDao().storeCursor("c:00000009:3")
                    db.syncClockDao().upsert(
                        ch.lkmc.neutrodyne.core.database
                            .SyncClockEntity(
                                "setting",
                                key.name,
                                ch.lkmc.neutrodyne.sync.protocol.SyncJson
                                    .encodeToString(raw),
                            ),
                    )
                    db.useWriterConnection { connection ->
                        connection.usePrepared(
                            "CREATE TRIGGER fail_reassert BEFORE INSERT ON sync_outbox " +
                                "BEGIN SELECT RAISE(ABORT, 'fixture reassert failure'); END",
                        ) { it.step() }
                    }
                }
                val result = mergerOf(db, ScriptedClient(emptyList()), FakeLibraryMerger()).resync()
                assertTrue(result is FirstLinkResult.Failed, "$result")
                assertEquals(
                    "c:00000009:3",
                    db.syncStateDao().get()?.cursor,
                    "a retry must still enter full recovery when replay capture failed",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun anExpiredCursorRecoversOverHttpAndAcknowledgesOriginalReplays() =
        runTest {
            val db = TestDb.inMemory()
            val server = mockwebserver3.MockWebServer().apply { start() }
            val http = recoveryHttpClients()
            val activity = SyncActivityState()
            val wireUrl = server.url("/").toString().trimEnd('/')
            val wireAddress = FakeSyncServerAddress(serverUrl = wireUrl)
            try {
                link(db, enabled = true)
                val stored = Hlc.of(october, 7, remote)
                val pending = Hlc.of(now, 3, localNode)
                val rid = podcastSyncId + "g:one"
                db.withWriteTransaction {
                    val state = checkNotNull(db.syncStateDao().get())
                    db.syncStateDao().upsert(
                        state.copy(
                            serverUrl = wireUrl,
                            accountId = "a",
                            linkedAt = now,
                            cursor = "c:00000009:3",
                        ),
                    )
                    db.syncClockDao().upsert(
                        ch.lkmc.neutrodyne.core.database.SyncClockEntity(
                            "episode",
                            rid,
                            ch.lkmc.neutrodyne.sync.protocol.SyncJson.encodeToString(
                                RawSyncRecord(
                                    ch.lkmc.neutrodyne.sync.protocol.RecordState(
                                        ch.lkmc.neutrodyne.sync.protocol.SyncCollection.EPISODE,
                                        mapOf(
                                            "played" to
                                                ch.lkmc.neutrodyne.sync.protocol.FieldState(
                                                    JsonPrimitive(true),
                                                    stored,
                                                ),
                                            "playedAt" to
                                                ch.lkmc.neutrodyne.sync.protocol
                                                    .FieldState(JsonNull, stored),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                    db.syncOutboxDao().upsert(
                        SyncOutboxEntity("episode", rid, "fav", pending.packed, localNode.value, value = "true"),
                    )
                }

                fun json(
                    body: String,
                    code: Int = 200,
                ) = mockwebserver3.MockResponse
                    .Builder()
                    .code(
                        code,
                    ).addHeader("Content-Type", "application/json")
                    .body(body)
                    .build()
                val requestIndex =
                    java.util.concurrent.atomic
                        .AtomicInteger()
                server.dispatcher =
                    object : mockwebserver3.Dispatcher() {
                        override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse =
                            when (requestIndex.getAndIncrement()) {
                                0 -> {
                                    json(
                                        """{"type":"about:blank","title":"Old","status":410,"code":"cursor_expired"}""",
                                        410,
                                    )
                                }

                                1 -> {
                                    json("""{"records":[],"cursor":"c:0000000a:1","hasMore":true,"serverTime":""}""")
                                }

                                2 -> {
                                    json("""{"records":[],"cursor":"c:0000000a:2","hasMore":false,"serverTime":""}""")
                                }

                                3 -> {
                                    val push =
                                        ch.lkmc.neutrodyne.sync.protocol.SyncJson
                                            .decodeFromString<SyncRequest>(checkNotNull(request.body).utf8())
                                    json(
                                        ch.lkmc.neutrodyne.sync.protocol.SyncJson.encodeToString(
                                            SyncResponse(
                                                push.changes.mapIndexed {
                                                    index,
                                                    _,
                                                    ->
                                                    ChangeResultDto(index, "applied")
                                                },
                                                emptyList(),
                                                "c:0000000a:3",
                                                false,
                                                "",
                                            ),
                                        ),
                                    )
                                }

                                else -> {
                                    json(
                                        """{"type":"about:blank","title":"Oops","status":400,"code":"unexpected"}""",
                                        400,
                                    )
                                }
                            }
                    }
                val client = KtorSyncClient(http, { "fixture-token" }, { server.url("/").toString() })
                val notices = SyncNoticeBus()
                lateinit var merger: FirstLinkMerger
                val engine =
                    engine(db, client, activity, address = wireAddress, noticeBus = notices, recover = {
                        profile,
                        scoped,
                        ->
                        merger.resyncForRound(profile, scoped)
                    })
                merger =
                    FirstLinkMerger(
                        db,
                        client,
                        applier(db),
                        engine,
                        FakeLibraryMerger(),
                        paths,
                        newEffects(db),
                        TestClock(now),
                        activity,
                        FakeSecretStore(),
                        FakeCredentialCommitCoordinator(),
                    )
                val notice =
                    backgroundScope.async(
                        start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED,
                    ) { notices.notices.first() }

                val report = engine.round(ch.lkmc.neutrodyne.sync.api.SyncRoundReason.SYNC_NOW)

                fun take() = assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("POST", take().method)
                val firstPull = take()
                assertEquals("GET", firstPull.method)
                assertTrue(firstPull.url.queryParameter("since").isNullOrEmpty())
                assertEquals("c:0000000a:1", take().url.queryParameter("since"))
                val pushed =
                    ch.lkmc.neutrodyne.sync.protocol.SyncJson
                        .decodeFromString<SyncRequest>(
                            checkNotNull(take().body).utf8(),
                        )
                assertEquals("c:0000000a:2", pushed.since)
                val fields = pushed.changes.single { it.coll == "episode" }.fields
                assertEquals(stored.toString(), fields["played"]?.c)
                assertEquals(JsonPrimitive(true), fields["played"]?.v)
                assertEquals(stored.toString(), fields["playedAt"]?.c)
                assertEquals(JsonNull, fields["playedAt"]?.v)
                assertEquals(pending.toString(), fields["fav"]?.c)
                assertEquals(JsonPrimitive(true), fields["fav"]?.v)
                assertEquals(pushed.changes.size, report.pushed)
                assertTrue(report.pushed > 0)
                assertEquals(
                    ch.lkmc.neutrodyne.sync.api.SyncNotice
                        .Resynced(report.pushed),
                    notice.await(),
                )
                assertEquals("c:0000000a:3", db.syncStateDao().get()?.cursor)
                assertTrue(db.syncOutboxDao().rows().isEmpty())
                assertEquals(0, activity.count.value)
            } finally {
                http.close()
                server.close()
                db.close()
            }
        }

    private fun recoveryHttpClients(): ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients {
        val platform =
            object : ch.lkmc.neutrodyne.core.common.PlatformInfo {
                override val kind = ch.lkmc.neutrodyne.core.common.PlatformKind.DESKTOP
                override val userAgentPlatform = "Linux; x64"
                override val androidSdkInt: Int? = null
                override val regionCode = "DE"
            }
        val userAgent =
            ch.lkmc.neutrodyne.core.common
                .UserAgentProvider(platform, "0.1.0", "https://example.com/repo")
        return ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients(
            ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients(
                ch.lkmc.neutrodyne.core.network.okhttp.CoreClients(
                    ch.lkmc.neutrodyne.core.network.okhttp
                        .UserAgentInterceptor(userAgent),
                    ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard(
                        ch.lkmc.neutrodyne.core.common
                            .LocalNetworkAccess(),
                        platform,
                    ),
                    ch.lkmc.neutrodyne.core.network.okhttp
                        .DnsFamilyHints(),
                    emptySet(),
                ),
                ch.lkmc.neutrodyne.core.network.okhttp
                    .AuthInterceptor { ch.lkmc.neutrodyne.core.common.CredentialLookup.None },
            ),
            userAgent,
        )
    }

    // --- Fixtures -----------------------------------------------------------------------------------

    private fun mergerOf(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        client: SyncClient,
        library: LibraryMerger,
        activity: SyncActivityState = SyncActivityState(),
        effects: SyncApplyEffects = newEffects(db),
        secrets: FakeSecretStore = FakeSecretStore(),
        beforeMerge: suspend () -> Unit = {},
    ): FirstLinkMerger =
        FirstLinkMerger(
            db = db,
            client = client,
            applier = applier(db),
            engine = engine(db, client, activity),
            libraryMerger = library,
            storagePaths = paths,
            effects = effects,
            clock = TestClock(now),
            activity = activity,
            secrets = secrets,
            coordinator = FakeCredentialCommitCoordinator(),
            beforeMerge = beforeMerge,
        )

    /**
     * The fixture for 10's durable-effects drainer: [coordinator] is the same per-key write
     * lock the test's local settings writes take, matching the one app-scoped
     * [ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator] the graph binds.
     */
    private fun newEffects(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        port: ch.lkmc.neutrodyne.core.domain.SettingsSyncPort = FakeSettingsSyncPort(),
        coordinator: ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator =
            TestSettingsWriteCoordinator(),
        secrets: FakeSecretStore = FakeSecretStore(),
        credentials: FakeCredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
    ): SyncApplyEffects =
        SyncApplyEffects(
            db,
            port,
            coordinator,
            { true },
            secrets,
            credentials,
            paths.cacheDir.toPath(),
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

    /** The wire record a server pull serves for one synced setting's `v` field. */
    private fun settingRecord(
        key: ch.lkmc.neutrodyne.core.model.settings.SettingKey<*>,
        value: Long,
        clock: Hlc,
    ): RecordDto =
        RecordDto(
            coll = "setting",
            id = JsonPrimitive(key.name),
            seq = 0,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields = mapOf("v" to FieldValue(JsonPrimitive(value), clock.toString())),
        )

    /** The scripted server's acknowledgement of every pushed change, no echo page. */
    private fun ackingSync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse =
        SyncResponse(
            results = changes.mapIndexed { index, _ -> ChangeResultDto(index, "applied") },
            records = emptyList(),
            cursor = "c:00000002:1",
            hasMore = false,
            serverTime = "",
        )

    private suspend fun pendingSetting(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        key: String,
    ): String? {
        val row = db.syncClockDao().get("setting", key) ?: return null
        val raw =
            runCatching {
                ch.lkmc.neutrodyne.sync.protocol.SyncJson
                    .decodeFromString<RawSyncRecord>(row.clocks)
            }.getOrNull()
        return raw?.pendingSetting?.value
    }

    /** The bare engine's default address — the store the fixtures' refresh reads. */
    private val defaultEngineAddress =
        object : ch.lkmc.neutrodyne.core.domain.SyncServerAddress {
            override suspend fun serverUrl(): String = "https://sync.example"

            override suspend fun username(): String? = null

            override suspend fun deviceName(): String? = null

            override suspend fun shareFeedPasswords(): Boolean = false

            override suspend fun syncPlaybackSettings(): Boolean = true

            override suspend fun setServerUrl(url: String?) = Unit

            override suspend fun setUsername(username: String?) = Unit

            override suspend fun clearServerIdentity() = Outcome.Success(Unit)

            override suspend fun setShareFeedPasswords(enabled: Boolean) = Outcome.Success(Unit)

            override suspend fun setSyncPlaybackSettings(enabled: Boolean) = Outcome.Success(Unit)
        }

    /**
     * The engine the merge's push rounds run through, over the same scripted client. [flags],
     * [address] and the cache's refresh lambda mirror `SyncBindings`' production wiring.
     */
    private fun engine(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        client: SyncClient,
        activity: SyncActivityState,
        flags: SyncFlags = SyncFlags(),
        address: ch.lkmc.neutrodyne.core.domain.SyncServerAddress = defaultEngineAddress,
        secrets: FakeSecretStore = FakeSecretStore(),
        noticeBus: SyncNoticeBus = SyncNoticeBus(),
        recover: (suspend (SyncStateEntity, SyncClient) -> SyncReport)? = null,
    ): SyncEngine {
        val writeLocks = TestSettingsWriteCoordinator()
        val settings =
            FakeSettingsSyncPort().apply {
                // A fresh real DataStore reads typed defaults, rather than an unreadable value.
                for (key in ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys.list
                    .filter { it.synced }) {
                    current[key.name] = encode(key, key.default)!!
                }
            }
        return SyncEngine(
            db = db,
            client = client,
            applier = applier(db),
            playback = NoPlaybackSync,
            clock = TestClock(now),
            secrets = secrets,
            sharePasswords = { flags.sharePasswords },
            address = address,
            tokens =
                SyncTokenStore(secrets).apply {
                    kotlinx.coroutines.runBlocking { save(hostOf(checkNotNull(address.serverUrl())), "token") }
                },
            activity = activity,
            gate =
                ch.lkmc.neutrodyne.core.testing
                    .FakeLocalNetworkGate(),
            effects = newEffects(db, settings, writeLocks, secrets),
            capture = SettingsCapture(db, settings, writeLocks) { true },
            notices = noticeBus,
            refreshPreferences = { flags.refreshFromStore(address) },
            recoverExpiredCursor = recover,
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
            notices = SyncNoticeBus(),
            deviceNames = ClientDeviceNames { emptyList() },
            sharePasswords = { false },
            coordinator = FakeCredentialCommitCoordinator(),
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

    /**
     * A server record whose `auth` clock differs from its `credentialOrigin` clock — the
     * mixed full record of an origin move, where nothing proves the pair (10 Passwords).
     */
    private fun unpairedAuthPodcastRecord(pass: String): RecordDto =
        podcastRecord().copy(
            fields =
                podcastRecord().fields +
                    mapOf(
                        "credentialOrigin" to
                            FieldValue(JsonPrimitive("https://a.example"), clock(october + 1_000)),
                        "auth" to
                            FieldValue(
                                buildJsonObject {
                                    put("u", "feed-user")
                                    put("p", pass)
                                },
                                clock(october),
                            ),
                    ),
        )

    /** A podcast record carrying a wire `auth` pair for the protected-staging tests. */
    private fun authPodcastRecord(pass: String): RecordDto =
        podcastRecord().copy(
            fields =
                podcastRecord().fields +
                    mapOf(
                        "credentialOrigin" to
                            FieldValue(JsonPrimitive("https://a.example"), clock(october)),
                        "auth" to
                            FieldValue(
                                buildJsonObject {
                                    put("u", "feed-user")
                                    put("p", pass)
                                },
                                clock(october),
                            ),
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

    /** A session record in the wire shape: an `episode` ref and a `context` object. */
    private fun sessionRecord(groupUuid: String): RecordDto =
        RecordDto(
            coll = "session",
            id = JsonPrimitive("current"),
            seq = 4,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields =
                mapOf(
                    "episode" to
                        FieldValue(
                            buildJsonObject {
                                put("p", podcastSyncId)
                                put("k", "g:one")
                            },
                            clock(october),
                        ),
                    "context" to
                        FieldValue(
                            buildJsonObject {
                                put("type", "GROUP")
                                put("group", groupUuid)
                                put("order", "OLDEST_FIRST")
                                put(
                                    "filters",
                                    buildJsonObject {
                                        put("flags", 3)
                                        put("media", "AUDIO")
                                    },
                                )
                                put("minSortDate", 5)
                                put(
                                    "anchor",
                                    buildJsonObject {
                                        put("p", podcastSyncId)
                                        put("k", "g:one")
                                    },
                                )
                            },
                            clock(october),
                        ),
                ),
        )

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

    private companion object {
        const val AUTH_ORIGIN = "https://a.example"
        const val AUTH_USER = "sync-user"
        const val AUTH_PASS = "s3ntinel-pass-9f8e"
    }
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
    private val syncHandler: (String?, List<ChangeDto>) -> SyncResponse = { _, _ ->
        SyncResponse(emptyList(), records, "c:00000001:1", false, "")
    },
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
    ): SyncResponse = syncHandler(since, changes)

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
