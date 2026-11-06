// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.playback

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.testing.FakeAudioEngine
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.sync.InMemorySyncServer
import ch.lkmc.neutrodyne.core.testing.sync.SyncAddress
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.playback.api.PlayResult
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
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * MS2 AC4's while-playing deferral on the desktop, end to end (the desktop counterpart of the
 * Android `PlaybackSyncDeferralTest`): the real `DesktopPlaybackController` as 06's
 * `PlaybackSyncPort`, the real `SyncScheduler`'s event wiring, the real `SyncEngine` and
 * `SyncApplier` over Room, and the in-memory server — a remote unsubscribe of the playing
 * podcast stays deferred until the controller's real `Stopped` boundary, then applies
 * (10 Mass-change guard rule 4).
 */
class DesktopPlaybackSyncDeferralTest {
    private val now = 1_800_000_000_000L
    private val podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val remote = NodeId("0000000000000002")

    @Test
    fun aRemoteUnsubscribeOfThePlayingPodcastDefersUntilStopped() =
        runBlocking {
            val fixture = DeferralFixture()
            try {
                val db = fixture.db
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
                fixture.link()

                // Real playback of the podcast's episode through the fake engine.
                fixture.controller.initialize()
                assertEquals(PlayResult.Started, fixture.controller.playEpisode(episodeId))
                fixture.awaitEpisode(episodeId)
                fixture.engine.ready()
                fixture.awaitPlaying()
                assertEquals(
                    episodeId,
                    fixture.port.active.value
                        ?.episodeId,
                )
                assertEquals(
                    true,
                    fixture.port.active.value
                        ?.isPlaying,
                )

                // The scheduler's event collector is live: the controller's own pause boundary
                // (after its pause save) runs a round observable through `lastSyncAt`.
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
                fixture.controller.pause()
                fixture.awaitLastSync()
                fixture.controller.play()
                fixture.awaitPlaying()

                // Another device unsubscribes the podcast on the server.
                fixture.server.push(
                    SyncAddress(SyncCollection.PODCAST, RecordId.Podcast(PodcastId(podcastSyncId))),
                    RecordState(
                        coll = SyncCollection.PODCAST,
                        fields =
                            mapOf(
                                "subscribed" to FieldState(JsonPrimitive(false), Hlc.of(now, 5, remote)),
                            ),
                    ),
                )
                fixture.syncEngine.round(SyncRoundReason.SYNC_NOW)
                assertNotNull(db.podcastDao().bySyncId(podcastSyncId))
                assertEquals(1, db.syncHeldDao().count())

                // The controller stops: its real Stopped boundary releases the deferral through
                // the scheduler's wiring, and the removal applies.
                fixture.controller.dismiss()
                withTimeout(10_000) {
                    while (db.podcastDao().bySyncId(podcastSyncId) != null) {
                        Thread.sleep(10)
                    }
                }
                assertEquals(0, db.syncHeldDao().count())
                assertNull(fixture.port.active.value)
            } finally {
                fixture.close()
            }
        }
}

private class DeferralFixture {
    val clock = TestClock(1_800_000_000_000L)
    val engine = FakeAudioEngine()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val dirs = tempAppDirs("ms2-sync-deferral").also { it.ensureCreated() }
    private val info = BuildInfoLoader.load()
    private val graph =
        dev.zacsweers.metro
            .createDynamicGraphFactory<DesktopAppGraph.Factory>(
                DeferralTestBindings(engine, clock, network),
            ).create(dirs, info, DesktopCrashReporter(dirs, info, clock, RecentLogBuffer()))
    val controller = graph.desktopPlaybackController
    val port = controller.syncPort
    val db =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            graph.databaseOpener.requireDatabase()
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val server = InMemorySyncServer { clock.now() }

    val syncEngine =
        SyncEngine(
            db = db,
            client = DeferralHarnessClient(server),
            applier =
                SyncApplier(
                    db = db,
                    clock = clock,
                    secrets = FakeSecretStore(),
                    settings = FakeSettingsSyncPort(),
                    playback = port,
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
            playback = port,
        )

    private val scheduler =
        SyncScheduler(
            db = db,
            round = { reason -> syncEngine.round(reason) },
            playback = port,
            network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
            clock = clock,
            scope = scope,
            hooks =
                object : SyncScheduleHooks {
                    override fun interactive(): Boolean = true

                    override fun schedulePush(reason: SyncRoundReason) = Unit

                    override suspend fun onLinked() = Unit

                    override suspend fun onUnlinked() = Unit
                },
            releaseDeferred = { syncEngine.releaseDeferred() },
        )

    suspend fun link() {
        db.withWriteTransaction {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    nodeId = "0000000000000001",
                    hlc = 1L shl 16,
                    linkedAt = clock.now(),
                    accountId = "acc-1",
                ),
            )
        }
        scheduler.start()
    }

    suspend fun awaitEpisode(id: Long) {
        withTimeout(10_000) { controller.nowPlaying.filter { it?.episodeId == id }.first() }
    }

    suspend fun awaitPlaying() {
        withTimeout(10_000) { controller.nowPlaying.filter { it?.isPlaying == true }.first() }
    }

    suspend fun awaitLastSync() {
        withTimeout(15_000) {
            while (db.syncStateDao().get()?.lastSyncAt == null) {
                Thread.sleep(10)
            }
        }
    }

    fun close() {
        runBlocking {
            controller.shutdown()
            scope.cancel()
            graph.appScope.coroutineContext[Job]!!.cancelAndJoin()
            graph.databaseOpener.requireDatabase().close()
        }
        dirs.data.parent
            .toFile()
            .deleteRecursively()
    }
}

/** The in-memory server behind the [SyncClient] surface (as the Android harness test builds it). */
private class DeferralHarnessClient(
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
    ): ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse {
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
        return ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse(
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

@BindingContainer
private class DeferralTestBindings(
    private val engine: FakeAudioEngine,
    private val clock: TestClock,
    private val network: FakeNetworkMonitor,
) {
    @Provides fun engine(): ch.lkmc.neutrodyne.playback.engine.AudioEngine = engine

    @Provides fun clock(): ch.lkmc.neutrodyne.core.common.Clock = clock

    @Provides fun network(): ch.lkmc.neutrodyne.core.common.NetworkMonitor = network
}
