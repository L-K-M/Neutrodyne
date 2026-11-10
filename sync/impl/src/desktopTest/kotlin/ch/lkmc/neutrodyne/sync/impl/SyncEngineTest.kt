// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakePlaybackSyncPort
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
    fun aRemoteUnsubscribeAndGroupDeleteBelowTheThresholdApply() =
        runTest {
            val server = InMemorySyncServer { NOW }
            val a = device("a", server)
            val b = device("b", server)
            try {
                a.addPodcast(syncId("0a"), "https://a.example/feed", "A")
                a.addPodcast(syncId("0b"), "https://b.example/feed", "B")
                a.addGroup(syncId("07"), "News")
                a.addGroup(syncId("08"), "Other")
                a.addMember(syncId("08"), syncId("0b"))
                a.round()
                b.round()

                // A unsubscribes one podcast and deletes one group — below every threshold.
                a.db.withWriteTransaction {
                    a.db.podcastDao().deleteCascade(
                        a.db
                            .podcastDao()
                            .bySyncId(syncId("0a"))!!
                            .id,
                        NOW,
                    )
                    a.db.groupDao().deleteGroup(
                        a.db
                            .groupDao()
                            .groupByUuid(syncId("07"))!!
                            .id,
                    )
                }
                // The group deletion pushes once 05's 10-s undo window has passed.
                a.clock.advanceBy(11.seconds)
                a.round()
                b.round()

                assertNull(
                    b.db.podcastDao().bySyncId(syncId("0a")),
                    "the below-threshold unsubscribe applies",
                )
                assertNotNull(b.db.podcastDao().bySyncId(syncId("0b")))
                assertNull(
                    b.db.groupDao().groupByUuid(syncId("07")),
                    "the below-threshold group delete applies",
                )
                val other = b.db.groupDao().groupByUuid(syncId("08"))
                assertNotNull(other, "an unrelated group is untouched")
                assertEquals(
                    1,
                    b.db
                        .groupDao()
                        .membersOf(other.id)
                        .size,
                )
                assertTrue(
                    b.db
                        .syncHeldDao()
                        .all()
                        .isEmpty(),
                    "no provisional or held row remains",
                )
                assertTrue(
                    b.db
                        .syncOutboxDao()
                        .rows()
                        .isEmpty(),
                    "applied removals never re-capture",
                )
            } finally {
                a.close()
                b.close()
            }
        }

    @Test
    fun aPlayingPodcastDefersItsRemoteUnsubscribe() =
        runTest {
            val server = InMemorySyncServer { NOW }
            val a = device("a", server)
            val playback = FakePlaybackSyncPort()
            val b = device("b", server, playback)
            try {
                a.addPodcast(syncId("0a"), "https://a.example/feed", "A")
                a.addPodcast(syncId("0b"), "https://b.example/feed", "B")
                a.round()
                b.round()

                // B is playing an episode of the podcast A is about to remove; 10 Mass-change
                // guard 4 defers the approved removal while the loaded item plays.
                val bPodcast = b.db.podcastDao().bySyncId(syncId("0a"))!!
                val bEpisode = b.addEpisode(bPodcast.id, "g:playing")
                playback.setActive(episodeId = bEpisode, isPlaying = true)

                a.db.withWriteTransaction {
                    a.db.podcastDao().deleteCascade(
                        a.db
                            .podcastDao()
                            .bySyncId(syncId("0a"))!!
                            .id,
                        NOW,
                    )
                }
                a.round()
                b.round()

                assertNotNull(
                    b.db.podcastDao().bySyncId(syncId("0a")),
                    "the playing podcast's removal defers",
                )
                assertNotNull(b.db.podcastDao().bySyncId(syncId("0b")))
                assertEquals(
                    1,
                    b.db
                        .syncHeldDao()
                        .all()
                        .size,
                    "the deferred remainder is retained",
                )

                playback.setPlaying(false)
                b.round()
                assertNull(
                    b.db.podcastDao().bySyncId(syncId("0a")),
                    "the next round applies the deferred removal",
                )
                assertTrue(
                    b.db
                        .syncHeldDao()
                        .all()
                        .isEmpty(),
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

    @Test
    fun aRoundMarksSyncActivityUntilItReturns() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val client =
                object : SyncClient by InMemorySyncClient(InMemorySyncServer { NOW }) {
                    override suspend fun sync(
                        since: String?,
                        changes: List<ChangeDto>,
                    ): SyncResponse {
                        gate.await()
                        return SyncResponse(emptyList(), emptyList(), "c:00000001:1", false, "")
                    }
                }
            val db = TestDb.inMemory()
            try {
                seedRound(db)
                val activity = SyncActivityState()
                val device = Device(db, client, activity)
                val job = launch { device.round() }
                activity.count.first { it > 0 }
                assertTrue(activity.active, "the round must mark sync active while it runs")
                gate.complete(Unit)
                job.join()
                assertEquals(0, activity.count.value, "a completed round clears the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    @Test
    fun aFailedRoundClearsSyncActivity() =
        runTest {
            val client =
                object : SyncClient by InMemorySyncClient(InMemorySyncServer { NOW }) {
                    override suspend fun sync(
                        since: String?,
                        changes: List<ChangeDto>,
                    ): SyncResponse = error("client down")
                }
            val db = TestDb.inMemory()
            try {
                seedRound(db)
                val activity = SyncActivityState()
                val device = Device(db, client, activity)
                val result = runCatching { device.round() }
                assertTrue(result.isFailure, "the round must propagate the client failure")
                assertEquals(0, activity.count.value, "a failed round must clear the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    @Test
    fun aCancelledRoundClearsSyncActivity() =
        runTest {
            val gate = CompletableDeferred<Unit>() // never completed; the cancel interrupts the await
            val client =
                object : SyncClient by InMemorySyncClient(InMemorySyncServer { NOW }) {
                    override suspend fun sync(
                        since: String?,
                        changes: List<ChangeDto>,
                    ): SyncResponse {
                        gate.await()
                        return SyncResponse(emptyList(), emptyList(), "c:00000001:1", false, "")
                    }
                }
            val db = TestDb.inMemory()
            try {
                seedRound(db)
                val activity = SyncActivityState()
                val device = Device(db, client, activity)
                val job = launch { device.round() }
                activity.count.first { it > 0 }
                job.cancelAndJoin()
                assertEquals(0, activity.count.value, "a cancelled round must clear the activity flag")
                assertFalse(activity.active)
            } finally {
                db.close()
            }
        }

    @Test
    fun aRoundAppliesTheGateVerdictToTheSyncBypass() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seedRound(db)
                val device = Device(db, InMemorySyncClient(InMemorySyncServer { NOW }))
                // The engine is the one boundary every round crosses: it must apply the gate's
                // live verdict before the first request, so an already-granted permission works
                // after a restart and a revocation blocks the round (10 Local-network gate 3-4).
                device.gate.setPermissionGranted(false)
                device.round()
                assertEquals(listOf(false), device.gate.results)
                device.gate.setPermissionGranted(true)
                device.round()
                assertEquals(listOf(false, true), device.gate.results)
            } finally {
                db.close()
            }
        }

    @Test
    fun overlappingRoundsKeepTheActivityFlagUntilTheLastOneEnds() {
        val activity = SyncActivityState()
        activity.begin()
        activity.begin()
        activity.end()
        assertTrue(activity.active)
        activity.end()
        assertFalse(activity.active)
    }

    private suspend fun seedRound(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = "a".padStart(16, '0'), hlc = 1L shl 16))
        }
    }

    private class Device(
        val db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        val client: SyncClient,
        private val activity: SyncActivityState = SyncActivityState(),
        playback: PlaybackSyncPort = NoPlaybackSync,
    ) {
        val clock = TestClock(NOW)
        val secrets = FakeSecretStore()
        val gate = FakeLocalNetworkGate()
        val settingsPort = FakeSettingsSyncPort()
        val writeLocks = TestSettingsWriteCoordinator()
        val credentials = FakeCredentialCommitCoordinator()
        val effects =
            SyncApplyEffects(
                db,
                settingsPort,
                writeLocks,
                { true },
                secrets,
                credentials,
                "__absent_cache__".toPath(),
            )
        val engine =
            SyncEngine(
                db = db,
                client = client,
                applier =
                    SyncApplier(
                        db = db,
                        clock = clock,
                        secrets = secrets,
                        settings = settingsPort,
                        playback = playback,
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
                        deviceNames = DeviceNames { emptyList() },
                        sharePasswords = { false },
                        coordinator = credentials,
                    ),
                playback = playback,
                clock = clock,
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

                        override suspend fun setShareFeedPasswords(enabled: Boolean) = Outcome.Success(Unit)

                        override suspend fun setSyncPlaybackSettings(enabled: Boolean) = Outcome.Success(Unit)
                    },
                tokens = SyncTokenStore(FakeSecretStore()).apply { runBlocking { save("sync.example", "token") } },
                activity = activity,
                gate = gate,
                effects = effects,
                capture = SettingsCapture(db, settingsPort, writeLocks) { true },
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
        playback: PlaybackSyncPort = NoPlaybackSync,
    ): Device {
        val db = TestDb.inMemory()
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = name.padStart(16, '0'), hlc = 1L shl 16))
        }
        return Device(db, InMemorySyncClient(server), playback = playback)
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
