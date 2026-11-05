// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.core.testing.sync.SyncAddress
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.ValidatedChange
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkConfirmRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkTokenRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LoginRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AC2 at the engine level: two devices converge through the shared merge rules. */
private const val NOW = 1_800_000_000_000L

class SyncEngineTest {
    @Test
    fun twoDevicesConvergeAfterOfflineEdits() =
        runTest {
            val server = InMemorySyncServer { NOW }
            val a = device("a", server)
            val b = device("b", server)
            try {
                // A subscribes to one feed, B to another, both offline.
                a.addPodcast(syncId("0a"), "https://a.example/feed", "A")
                b.addPodcast(syncId("0b"), "https://b.example/feed", "B")

                a.round()
                b.round()
                a.round()

                val aPodcasts =
                    a.db
                        .podcastDao()
                        .allForExport()
                        .sortedBy { it.syncId }
                val bPodcasts =
                    b.db
                        .podcastDao()
                        .allForExport()
                        .sortedBy { it.syncId }
                assertEquals(aPodcasts.map { it.syncId }, bPodcasts.map { it.syncId })
                assertEquals(2, aPodcasts.size)

                // A plays an episode with an enclosure hint; B stubs it and sees the state.
                val aPodcast = aPodcasts.first { it.syncId == syncId("0a") }
                val aEpisode =
                    a.addEpisode(
                        aPodcast.id,
                        "g:one",
                        enclosureUrl = "https://cdn.example.com/one.mp3",
                    )
                a.setPosition(aEpisode, 1_000L)
                a.round()
                b.round()

                val bPodcast = b.db.podcastDao().bySyncId(syncId("0a"))!!
                val bEpisode = b.db.episodeDao().byIdentityKey(bPodcast.id, "g:one")!!
                assertEquals(
                    1_000L,
                    b.db
                        .positionDao()
                        .byEpisode(bEpisode.id)
                        ?.positionMs,
                )

                a.markPlayed(aEpisode)
                a.round()
                b.round()
                assertEquals(
                    a.db
                        .episodeStateDao()
                        .byEpisode(aEpisode)
                        ?.playedAt,
                    b.db
                        .episodeStateDao()
                        .byEpisode(bEpisode.id)
                        ?.playedAt,
                )
            } finally {
                a.close()
                b.close()
            }
        }

    /**
     * 10 Outbox and coalescing: the acknowledgement merges the exact sent fields into
     * `sync_clock` raw state with their sent clocks, so the accepted values survive without a
     * redelivery and a resync replay compares against them.
     */
    @Test
    fun anAcknowledgedPushMergesItsSentFieldsIntoRawState() =
        runTest {
            val server = InMemorySyncServer { NOW }
            val a = device("a", server)
            try {
                a.addPodcast(syncId("0a"), "https://a.example/feed", "A")
                val podcast = a.db.podcastDao().bySyncId(syncId("0a"))!!
                val episode =
                    a.addEpisode(
                        podcast.id,
                        "g:one",
                        enclosureUrl = "https://cdn.example.com/one.mp3",
                    )
                a.markPlayed(episode)

                a.round()

                val raw = a.db.syncClockDao().get("episode", syncId("0a") + "g:one")
                assertTrue(raw != null, "the ack seeded the raw state")
                val state =
                    ch.lkmc.neutrodyne.sync.protocol.SyncJson
                        .decodeFromString<RawSyncRecord>(raw!!.clocks)
                        .state
                val played = state.fields["played"]
                assertTrue(played != null, "fields=${state.fields.keys}")
                assertEquals(true, (played!!.v as? kotlinx.serialization.json.JsonPrimitive)?.content == "true")
                assertEquals("000000000000000a", played.c?.nodeId?.value, "the sent clock's origin rides along")
            } finally {
                a.close()
            }
        }

    /**
     * A pushed `auth` keeps only its protected representation when acknowledged: the raw state
     * carries the `sync:pending:` marker — the same representation the receive path stores, so
     * an equal-clock echo of the push compares equal — and never the plaintext that left the
     * device (10 Durable apply effects).
     */
    @Test
    fun anAcknowledgedAuthKeepsOnlyItsProtectedMarker() =
        runTest {
            val server = InMemorySyncServer { NOW }
            val a = device("a", server, sharePasswords = true)
            try {
                a.addPodcast(syncId("0a"), "https://a.example/feed", "A")
                val podcast = a.db.podcastDao().bySyncId(syncId("0a"))!!
                val credentialId =
                    a.secrets.put("https://a.example", "anna", "hunter2".toCharArray()) ?: 0L
                a.secrets.podcastCredentialIds.value = mapOf(podcast.id to credentialId)
                a.db.withWriteTransaction {
                    a.db.syncOutboxDao().captureIntent("podcast", syncId("0a"), "auth", "null")
                }

                a.round()

                val raw = a.db.syncClockDao().get("podcast", syncId("0a"))
                assertTrue(raw != null, "the ack seeded the raw state")
                assertTrue(!raw!!.clocks.contains("hunter2"), "no plaintext password in Room: ${raw.clocks}")
                val state =
                    ch.lkmc.neutrodyne.sync.protocol.SyncJson
                        .decodeFromString<RawSyncRecord>(raw.clocks)
                        .state
                val auth = state.fields["auth"]!!
                val ref =
                    (auth.v as? kotlinx.serialization.json.JsonObject)
                        ?.get("ref")
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                assertTrue(ref != null && ref.startsWith("sync:pending:"), "the marker names the store: ${auth.v}")
            } finally {
                a.close()
            }
        }

    @Test
    fun aGroupAndMembershipConverge() =
        runTest {
            val server = InMemorySyncServer { NOW }
            val a = device("a", server)
            val b = device("b", server)
            try {
                a.addPodcast(syncId("0a"), "https://a.example/feed", "A")
                a.addGroup(syncId("07"), "News")
                a.round()
                b.round()
                b.addMember(syncId("07"), syncId("0a"))
                b.round()
                a.round()

                val aGroup = a.db.groupDao().groupByUuid(syncId("07"))
                val bGroup = b.db.groupDao().groupByUuid(syncId("07"))
                assertEquals("News", aGroup?.name)
                assertEquals("News", bGroup?.name)
                assertEquals(
                    1,
                    a.db
                        .groupDao()
                        .membersOf(aGroup!!.id)
                        .size,
                )
                assertEquals(
                    1,
                    b.db
                        .groupDao()
                        .membersOf(bGroup!!.id)
                        .size,
                )
            } finally {
                a.close()
                b.close()
            }
        }

    @Test
    fun theHasMoreDrainAdvancesTheCursor() =
        runTest {
            val calls = mutableListOf<String>()
            val client =
                object : SyncClient by InMemorySyncClient(InMemorySyncServer { NOW }) {
                    override suspend fun sync(
                        since: String?,
                        changes: List<ChangeDto>,
                    ): SyncResponse =
                        SyncResponse(
                            results = emptyList(),
                            records = emptyList(),
                            cursor = "c:00000001:5",
                            hasMore = true,
                            serverTime = "",
                        )

                    override suspend fun changes(
                        since: String?,
                        limit: Int,
                    ): ChangesPage {
                        calls += since.orEmpty()
                        return ChangesPage(
                            records = emptyList(),
                            cursor = "c:00000001:9",
                            hasMore = since != "c:00000001:9",
                            serverTime = "",
                        )
                    }
                }
            val db = TestDb.inMemory()
            try {
                db.withWriteTransaction {
                    db
                        .syncStateDao()
                        .upsert(SyncStateEntity(enabled = true, nodeId = "a".padStart(16, '0'), hlc = 1L shl 16))
                }
                Device(db, client).round()
                assertEquals(listOf("c:00000001:5", "c:00000001:9"), calls)
            } finally {
                db.close()
            }
        }

    @Test
    fun aMalformedRecordClockDoesNotFailTheRound() =
        runTest {
            val badRecord =
                ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto(
                    coll = "setting",
                    id = kotlinx.serialization.json.JsonPrimitive("theme"),
                    seq = 1,
                    by = null,
                    fields =
                        mapOf(
                            "v" to
                                ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue(
                                    v = kotlinx.serialization.json.JsonPrimitive("dark"),
                                    c = "not-an-hlc",
                                ),
                        ),
                )
            val client =
                object : SyncClient by InMemorySyncClient(InMemorySyncServer { NOW }) {
                    override suspend fun sync(
                        since: String?,
                        changes: List<ChangeDto>,
                    ): SyncResponse =
                        SyncResponse(
                            results = emptyList(),
                            records = listOf(badRecord),
                            cursor = "c:00000001:5",
                            hasMore = false,
                            serverTime = "",
                        )
                }
            val db = TestDb.inMemory()
            try {
                db.withWriteTransaction {
                    db
                        .syncStateDao()
                        .upsert(SyncStateEntity(enabled = true, nodeId = "a".padStart(16, '0'), hlc = 1L shl 16))
                }
                Device(db, client).round()
            } finally {
                db.close()
            }
        }

    private class Device(
        val db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        val client: SyncClient,
        sharePasswords: Boolean = false,
    ) {
        val clock = TestClock(NOW)
        val secrets = FakeSecretStore()
        val engine =
            SyncEngine(
                db = db,
                client = client,
                applier =
                    SyncApplier(
                        db = db,
                        clock = clock,
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
                    ),
                clock = clock,
                secrets = secrets,
                sharePasswords = { sharePasswords },
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
                tokens = SyncTokenStore(FakeSecretStore()).apply { runBlocking { save("sync.example", "token") } },
                effects =
                    SyncApplyEffects(
                        db = db,
                        settings = FakeSettingsSyncPort(),
                        secrets = secrets,
                        coordinator =
                            ch.lkmc.neutrodyne.core.testing
                                .FakeCredentialCommitCoordinator(),
                    ),
                notices = SyncNoticeBus(),
            )

        suspend fun round() = engine.round(SyncRoundReason.SYNC_NOW)

        suspend fun addPodcast(
            syncId: String,
            feedUrl: String,
            title: String,
        ) {
            db.withWriteTransaction {
                db
                    .podcastDao()
                    .insertPodcast(podcastEntity(syncId = syncId, feedUrl = feedUrl, feedKey = feedUrl, title = title))
            }
        }

        suspend fun addEpisode(
            podcastId: Long,
            key: String,
            enclosureUrl: String? = null,
        ): Long =
            db
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(podcastId = podcastId, identityKey = key).let { episode ->
                            if (enclosureUrl == null) episode else episode.copy(enclosureUrl = enclosureUrl)
                        },
                    ),
                ).single()

        suspend fun setPosition(
            episodeId: Long,
            positionMs: Long,
        ) {
            db.withWriteTransaction {
                db
                    .positionDao()
                    .upsert(
                        ch.lkmc.neutrodyne.core.database.EpisodePositionEntity(
                            episodeId = episodeId,
                            positionMs = positionMs,
                            positionSource = ch.lkmc.neutrodyne.core.model.PositionSource.STREAM,
                            updatedAt = NOW,
                        ),
                    )
            }
        }

        suspend fun markPlayed(episodeId: Long) {
            db.withWriteTransaction {
                db.episodeStateDao().ensure(
                    ch.lkmc.neutrodyne.core.database
                        .EpisodeStateEntity(episodeId, updatedAt = 1),
                )
                db.episodeStateDao().markPlayed(listOf(episodeId), NOW)
            }
        }

        suspend fun addGroup(
            uuid: String,
            name: String,
        ) {
            db.withWriteTransaction {
                db
                    .groupDao()
                    .insert(groupEntity(uuid = uuid, name = name, orderKey = "a0"))
            }
        }

        suspend fun addMember(
            groupUuid: String,
            podcastSyncId: String,
        ) {
            db.withWriteTransaction {
                val group = db.groupDao().groupByUuid(groupUuid) ?: return@withWriteTransaction Unit
                val podcast = db.podcastDao().bySyncId(podcastSyncId) ?: return@withWriteTransaction Unit
                db.groupDao().insertMember(memberEntity(groupId = group.id, podcastId = podcast.id, orderKey = "a0"))
            }
        }

        fun close() = db.close()
    }

    private suspend fun device(
        name: String,
        server: InMemorySyncServer,
        sharePasswords: Boolean = false,
    ): Device {
        val db = TestDb.inMemory()
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = name.padStart(16, '0'), hlc = 1L shl 16))
        }
        return Device(db, InMemorySyncClient(server), sharePasswords)
    }

    private fun syncId(name: String): String = "00000000-0000-4000-8000-${name.padStart(12, '0')}"
}

/** The MS0 in-memory server behind the [SyncClient] surface, for engine tests. */
private class InMemorySyncClient(
    private val server: InMemorySyncServer,
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
    ): SyncResponse {
        val results =
            changes.map { change ->
                when (val validated = ProtocolValidation.change(change)) {
                    is ValidatedChange.Write -> {
                        server.push(SyncAddress(validated.state.coll, validated.id), validated.state)
                        ChangeResultDto(0, "applied")
                    }

                    is ValidatedChange.Rekey -> {
                        server.rekey(validated.from, validated.to)
                        ChangeResultDto(0, "merged")
                    }
                }
            }
        val page = server.page(since, ProtocolLimits.MAX_RECORDS)
        return SyncResponse(
            results = results.mapIndexed { index, result -> result.copy(i = index) },
            records = page.records.map { it.state.toDto(it.address.id, it.seq, null) },
            cursor = page.cursor,
            hasMore = page.hasMore,
            serverTime = "",
        )
    }

    override suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage {
        val page = server.page(since, limit)
        return ChangesPage(
            records = page.records.map { it.state.toDto(it.address.id, it.seq, null) },
            cursor = page.cursor,
            hasMore = page.hasMore,
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
