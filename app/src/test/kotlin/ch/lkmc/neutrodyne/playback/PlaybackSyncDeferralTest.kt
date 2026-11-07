// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback

import android.app.Application
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.core.testing.sync.SyncAddress
import ch.lkmc.neutrodyne.playback.core.RemotePlayedGuard
import ch.lkmc.neutrodyne.playback.impl.PlaybackSyncBridge
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.impl.SyncApplier
import ch.lkmc.neutrodyne.sync.impl.SyncApplyEffects
import ch.lkmc.neutrodyne.sync.impl.SyncClient
import ch.lkmc.neutrodyne.sync.impl.SyncEngine
import ch.lkmc.neutrodyne.sync.impl.SyncNoticeBus
import ch.lkmc.neutrodyne.sync.impl.SyncScheduleHooks
import ch.lkmc.neutrodyne.sync.impl.SyncScheduler
import ch.lkmc.neutrodyne.sync.impl.SyncTokenStore
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.PodcastId
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.ProtocolValidation
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.ValidatedChange
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * MS2 AC4's while-playing deferral, end to end through the real Android pieces: the real
 * `PlaybackSyncBridge` (driven as the playback service drives it), the real `SyncScheduler`'s
 * event wiring, the real `SyncEngine` and `SyncApplier` over Room, and the in-memory server — a
 * remote unsubscribe of the playing podcast is deferred until the player's `Stopped` boundary and
 * then applied (10 Mass-change guard rule 4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaybackSyncDeferralTest {
    private val now = 1_800_000_000_000L
    private val podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val remote = NodeId("0000000000000002")
    private val app: Application = RuntimeEnvironment.getApplication()
    private val clock = TestClock(now)
    private val server = InMemorySyncServer { now }
    private val db = TestDb.inMemory(app, clock = clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guard = RemotePlayedGuard()

    /** The bridge observes played state through the store; the deferral path never reads it. */
    private val positions =
        object : PositionStore {
            override fun observe(episodeId: Long): Flow<StoredPosition?> = flow { emit(null) }

            override suspend fun setMeasuredDuration(
                episodeId: Long,
                durationMs: Long,
            ) = Unit

            override suspend fun save(
                episodeId: Long,
                positionMs: Long,
                durationMs: Long?,
                source: PositionSource,
                now: Long,
                pinStartedAt: Long,
                allowed: () -> Boolean,
            ): Boolean = false

            override suspend fun reset(
                episodeId: Long,
                now: Long,
            ) = Unit

            override suspend fun markPlayed(
                episodeId: Long,
                now: Long,
            ) = Unit

            override suspend fun markUnplayedForReplay(
                episodeId: Long,
                now: Long,
            ) = Unit
        }

    private val bridge = PlaybackSyncBridge(guard, dev.zacsweers.metro.Provider { positions }, scope)

    private val engine =
        SyncEngine(
            db = db,
            client = EngineHarnessClient(server),
            applier =
                SyncApplier(
                    db = db,
                    clock = clock,
                    secrets = FakeSecretStore(),
                    settings = FakeSettingsSyncPort(),
                    playback = bridge,
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
            secrets = FakeSecretStore(),
            sharePasswords = { false },
            address =
                object : SyncServerAddress {
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
                    secrets = FakeSecretStore(),
                    coordinator = FakeCredentialCommitCoordinator(),
                ),
            notices = SyncNoticeBus(),
            playback = bridge,
        )

    private val scheduler =
        SyncScheduler(
            db = db,
            round = { reason -> engine.round(reason) },
            playback = bridge,
            network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
            clock = clock,
            scope = scope,
            hooks = InteractiveHarnessHooks(),
            releaseDeferred = { engine.releaseDeferred() },
        )

    private class InteractiveHarnessHooks : SyncScheduleHooks {
        override fun interactive(): Boolean = true

        override fun schedulePush(reason: SyncRoundReason) = Unit

        override suspend fun onLinked() = Unit

        override suspend fun onUnlinked() = Unit
    }

    @Before
    fun linkAndSeed() =
        runBlocking {
            db.withWriteTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        nodeId = "0000000000000001",
                        hlc = 1L shl 16,
                        linkedAt = now,
                        accountId = "acc-1",
                    ),
                )
            }
        }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun aRemoteUnsubscribeOfThePlayingPodcastDefersUntilStopped() =
        runBlocking {
            val podcastId =
                db.podcastDao().insertPodcast(
                    podcastEntity(
                        syncId = podcastSyncId,
                        feedUrl = "https://example.com/feed",
                        feedKey = "https://example.com/feed",
                    ),
                )
            val episodeId =
                db
                    .ingestDao()
                    .insertEpisodes(
                        listOf(
                            episodeEntity(podcastId = podcastId, identityKey = "g:one")
                                .copy(enclosureUrl = "https://cdn.example.com/one.mp3"),
                        ),
                    ).single()

            // The service side of the bridge, as AndroidServiceBridge publishes it.
            bridge.attach {}
            bridge.updateActive(episodeId, isPlaying = true, sinceMs = now)
            scheduler.start()

            // A Paused boundary with a pending `pos` row proves the scheduler's event collector
            // runs: its round is observable through `lastSyncAt` before anything is deferred (a
            // no-replay flow drops anything emitted before the subscription, so retry bounded).
            db.withWriteTransaction {
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        coll = "episode",
                        rid = "$podcastSyncId" + "g:one",
                        field = "pos",
                        hlc = now shl 16,
                        nodeId = "0000000000000001",
                        value = "{\"ms\":1000}",
                    ),
                )
            }
            withTimeout(15_000) {
                var attempts = 0
                while (db.syncStateDao().get()?.lastSyncAt == null) {
                    check(++attempts <= 10) { "the scheduler's event collector never ran a round" }
                    bridge.paused()
                    val observeUntil = System.currentTimeMillis() + 500
                    while (
                        db.syncStateDao().get()?.lastSyncAt == null &&
                        System.currentTimeMillis() < observeUntil
                    ) {
                        Thread.sleep(20)
                    }
                }
            }

            // Another device unsubscribes the podcast on the server.
            server.push(
                SyncAddress(SyncCollection.PODCAST, RecordId.Podcast(PodcastId(podcastSyncId))),
                RecordState(
                    coll = SyncCollection.PODCAST,
                    fields =
                        mapOf(
                            "subscribed" to FieldState(JsonPrimitive(false), Hlc.of(now, 5, remote)),
                        ),
                ),
            )

            engine.round(SyncRoundReason.SYNC_NOW)
            assertThat(db.podcastDao().bySyncId(podcastSyncId)).isNotNull()
            assertThat(db.syncHeldDao().count()).isEqualTo(1)

            // The player stops exactly as production does: the bridge clears `active` before its
            // Stopped boundary (06 Sync interplay), so the release reading the port sees nothing
            // playing and the removal applies through the scheduler's event wiring.
            bridge.stopped()
            assertThat(bridge.active.value).isNull()
            withTimeout(10_000) {
                awaitUntil { db.podcastDao().bySyncId(podcastSyncId) == null }
            }
            assertThat(db.syncHeldDao().count()).isEqualTo(0)
        }

    private suspend fun awaitUntil(predicate: suspend () -> Boolean) {
        while (!predicate()) {
            Thread.sleep(10)
        }
    }
}

/** The in-memory server behind the [SyncClient] surface (the MS0 engine-test client). */
private class EngineHarnessClient(
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
