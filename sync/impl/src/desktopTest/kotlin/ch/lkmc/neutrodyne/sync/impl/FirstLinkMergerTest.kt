// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MS2 acceptance 3: Merge removes nothing local and decides state by row timestamps. */
class FirstLinkMergerTest {
    private val now = 1_800_000_000_000L
    private val june = 1_780_000_000_000L
    private val october = 1_790_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val podcastSyncId = "00000000-0000-4000-8000-00000000000a"

    @Test
    fun mergeKeepsLocalPodcastsAndANewerRemoteUnplayedWins() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
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
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one") { copy(guid = "one") }),
                        ).single()
                db
                    .episodeStateDao()
                    .ensure(EpisodeStateEntity(episodeId = episodeId, playedAt = june, updatedAt = june))
                db.episodeStateDao().markPlayed(listOf(episodeId), june)

                val server =
                    ScriptedClient(
                        listOf(
                            podcastRecord(),
                            episodeRecord(
                                "played" to FieldValue(JsonPrimitive(false), clock(october)),
                                "playedAt" to FieldValue(JsonNull, clock(october)),
                            ),
                        ),
                    )
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = server,
                        applier = applier(db),
                        engine = engine(db, server),
                        clock = TestClock(now),
                    )
                val result = merger.merge(FirstLinkChoice.MERGE)
                assertTrue(result is FirstLinkResult.Completed)

                // The local podcast stays; the newer remote "unplayed" wins.
                assertNotNull(db.podcastDao().bySyncId(podcastSyncId))
                val state = db.episodeStateDao().byEpisode(episodeId)
                assertNull(state?.playedAt)
                val link = db.syncStateDao().get()
                assertTrue(link?.enabled == true)
                assertNotNull(link?.linkedAt)
            } finally {
                db.close()
            }
        }

    /**
     * The bootstrap writes must run with capture already enabled: a Merge onto an empty account
     * uploads this device's library with the rows' own timestamps (10 Merge steps 4-5).
     */
    @Test
    fun mergeUploadsTheLocalLibraryWhenTheAccountIsEmpty() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
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
                }
                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = ScriptedClient(emptyList()),
                        applier = applier(db),
                        engine = engine(db, ScriptedClient(emptyList())),
                        clock = TestClock(now),
                    )
                val result = merger.merge(FirstLinkChoice.MERGE)
                assertTrue(result is FirstLinkResult.Completed)

                val rows = db.syncOutboxDao().rows()
                val feedUrl = rows.firstOrNull { it.coll == "podcast" && it.field == "feedUrl" }
                assertNotNull(feedUrl, "rows=$rows")
                assertTrue(feedUrl.hlc ushr 16 <= now)
                assertTrue(
                    rows.none { it.hlc ushr 16 > now + 1_000 },
                    "bootstrap writes keep their row timestamps, rows=$rows",
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

                val merger =
                    FirstLinkMerger(
                        db = db,
                        client = ScriptedClient(emptyList()),
                        applier = applier(db),
                        engine = engine(db, ScriptedClient(emptyList())),
                        clock = TestClock(now),
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

    /** The engine the merge's push rounds run through, over the same scripted client. */
    private fun engine(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        client: SyncClient,
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

    private suspend fun link(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        enabled: Boolean = false,
    ) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = enabled, nodeId = "0000000000000001", hlc = 1L shl 16))
        }
    }

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

    private fun episodeRecord(vararg fields: Pair<String, FieldValue>): RecordDto =
        RecordDto(
            coll = "episode",
            id =
                buildJsonObject {
                    put("p", podcastSyncId)
                    put("k", "g:one")
                },
            seq = 2,
            by = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            fields = fields.toMap(),
            match = MatchHints(guid = "one"),
            matchClocks = mapOf("guid" to clock(october).toString()),
        )

    private fun clock(atMs: Long): String = Hlc.of(atMs, 1, remote).toString()
}

/** A [SyncClient] that serves a fixed page, for the first-link tests. */
private class ScriptedClient(
    private val records: List<RecordDto>,
) : SyncClient {
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
    ): ChangesPage = ChangesPage(records = records, cursor = "c:00000001:1", hasMore = false, serverTime = "")

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ): AccountSummary = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}
