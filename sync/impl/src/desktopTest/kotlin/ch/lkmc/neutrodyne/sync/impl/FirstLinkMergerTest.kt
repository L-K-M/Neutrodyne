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
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
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
import kotlinx.coroutines.test.runTest
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
 */
class FirstLinkMergerTest {
    private val now = 1_800_000_000_000L
    private val june = 1_780_000_000_000L
    private val october = 1_790_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val podcastSyncId = "00000000-0000-4000-8000-00000000000a"
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
                                ch.lkmc.neutrodyne.core.testing.database.episodeEntity(
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

    // --- Fixtures -----------------------------------------------------------------------------------

    private fun mergerOf(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        client: SyncClient,
        library: FakeLibraryMerger,
    ): FirstLinkMerger {
        val secrets = FakeSecretStore()
        return FirstLinkMerger(
            db = db,
            client = client,
            applier = applier(db, secrets),
            engine = engine(db, client, secrets),
            libraryMerger = library,
            storagePaths = paths,
            settings = FakeSettingsSyncPort(),
            secrets = secrets,
            sharePasswords = { false },
            coordinator =
                ch.lkmc.neutrodyne.core.testing
                    .FakeCredentialCommitCoordinator(),
            effects = effects(db, secrets),
            clock = TestClock(now),
        )
    }

    private fun effects(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        secrets: ch.lkmc.neutrodyne.core.domain.SecretStore,
    ): SyncApplyEffects =
        SyncApplyEffects(
            db = db,
            settings = FakeSettingsSyncPort(),
            secrets = secrets,
            coordinator =
                ch.lkmc.neutrodyne.core.testing
                    .FakeCredentialCommitCoordinator(),
        )

    private suspend fun link(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        enabled: Boolean = false,
        deviceId: String? = null,
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

    private fun decodeEpisode(json: String): ch.lkmc.neutrodyne.sync.protocol.RecordState =
        ch.lkmc.neutrodyne.sync.protocol.SyncJson
            .decodeFromString<RawSyncRecord>(json)
            .state

    /** The engine the merge's push rounds run through, over the same scripted client. */
    private fun engine(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        client: SyncClient,
        secrets: ch.lkmc.neutrodyne.core.domain.SecretStore = FakeSecretStore(),
    ): SyncEngine =
        SyncEngine(
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
            effects = effects(db, secrets),
            notices = SyncNoticeBus(),
        )

    private fun applier(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        secrets: ch.lkmc.neutrodyne.core.domain.SecretStore = FakeSecretStore(),
    ): SyncApplier =
        SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = secrets,
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
}

/** A [SyncClient] that serves a fixed page and counts `changes` calls. */
private class CountingClient(
    private val records: List<RecordDto>,
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
            ChangesPage(records = records, cursor = "c:00000001:1", hasMore = false, serverTime = "")
        } else {
            ChangesPage(records = emptyList(), cursor = "c:00000001:1", hasMore = false, serverTime = "")
        }
    }

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}
