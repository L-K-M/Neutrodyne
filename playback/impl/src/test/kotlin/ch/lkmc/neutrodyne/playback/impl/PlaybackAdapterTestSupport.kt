// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.FakeEffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.testing.FakeLocalMediaIndex
import ch.lkmc.neutrodyne.core.testing.FakeScopeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import ch.lkmc.neutrodyne.playback.core.PlaybackHistory
import ch.lkmc.neutrodyne.playback.core.PlayedRule
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.StartOutcome
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.WindowDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import okio.Path
import org.junit.Assert.fail
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class AdapterFixture(
    val scope: TestScope,
    sessionStore: (PlaySessionStore) -> PlaySessionStore = { it },
) {
    val serviceJob = SupervisorJob(scope.backgroundScope.coroutineContext[Job])
    val playerScope = CoroutineScope(scope.backgroundScope.coroutineContext + serviceJob)
    val app: Application = RuntimeEnvironment.getApplication()
    val fakeClock = FakeClock(false)
    val clock: Clock =
        object : Clock {
            override fun now() = TestClock.DEFAULT_NOW + fakeClock.elapsedRealtime()

            override fun elapsedRealtime() = fakeClock.elapsedRealtime()
        }
    val db = TestDb.inMemory(app, clock = clock)
    val caps = FakeYouTubeCapabilitiesSource()
    val local = FakeLocalMediaIndex()
    val sessions = sessionStore(PlaybackTestStores.sessions(db, ProtocolOrderKeys, caps))
    val positions = PlaybackTestStores.positions(db)
    val queue = PlaybackTestStores.queue(db, sessions, ProtocolOrderKeys, clock, caps)
    val lookup = PlaybackTestStores.media(db)
    val sources = AdapterSources()
    val exo = TestExoPlayerBuilder(app).setClock(fakeClock).setMediaSourceFactory(sources).build()
    val player = RecordingPlayer(exo)
    val index = EpisodeSourceIndex { lookup.get(it) }
    val cache =
        StreamingCache(
            java.io.File(app.cacheDir, "adapter-media"),
            androidx.media3.database.StandaloneDatabaseProvider(app),
            Dispatchers.Unconfined,
            scope.backgroundScope,
        )
    val resolver = EpisodeResolver(index, local, cache)
    val failures = mutableListOf<Exception>()
    val events = mutableListOf<PlaybackEvent>()
    val tracker =
        PositionTracker(
            PositionSaver(positions, clock, PlayedRule()),
            clock,
            playerScope,
            scope.backgroundScope,
            {
                if (local.localUriOrNull(it) !=
                    null
                ) {
                    PositionSource.DOWNLOAD
                } else {
                    PositionSource.STREAM
                }
            },
            failures::add,
        )
    val settingsResolver = FakeEffectiveSettingsResolver()
    val settings = EffectivePlaybackSettings(settingsResolver, FakeScopeSettingsRepository(), FakeSettingsRepository())
    val applier = EffectivePlaybackApplier(settings, playerScope)
    val factory = MediaItemFactory(TestArtwork, local)
    val planner =
        QueueWindowPlanner(queue, caps, positions, local, clock, WindowDiff(), StartPositionRule(), PlayedRule())
    var smartResume = false
    val projector =
        QueueProjector(
            planner,
            sessions,
            queue,
            lookup,
            positions,
            caps,
            index,
            resolver,
            factory,
            tracker,
            applier,
            PlaybackHistory(),
            clock,
            playerScope,
            Dispatchers.Unconfined,
            failures::add,
            boundaryCompleted = { events += PlaybackEvent.MarkedPlayed(it) },
            smartResume = { smartResume },
        )

    fun attach() {
        tracker.attach(player)
        projector.attach(player)
        applier.attach(exo)
    }

    suspend fun seed(count: Int): List<Long> {
        val podcast = db.podcastDao().insertPodcast(podcastEntity(subscribedAt = 0))
        return (1..count).map { n ->
            db.episodeDao().insertStub(
                episodeEntity(podcastId = podcast, sortDate = n.toLong()) {
                    copy(enclosureUrl = "https://example.com/$n.mp3", durationMs = DURATION_MS)
                },
            )
        }
    }

    suspend fun start(
        ids: List<Long>,
        position: Long = 0,
        context: ch.lkmc.neutrodyne.core.model.PlayContextSpec? = null,
    ) {
        projector.beginOwnWrite()
        var generation: Long? = null
        try {
            generation =
                sessions.commitStart(
                    SessionStart(ids.first(), context, ContextRetention.REPLACE, null, null),
                    clock.now(),
                )
            queue.addLast(ids.drop(1))
            projector.applyStart(StartOutcome.Committed(sessions.virtualQueue(20), position))
        } finally {
            projector.endOwnWrite(generation)
        }
    }

    suspend fun close() {
        projector.detach()
        val saved = tracker.detach(tracker.flush(PositionFlushReason.DESTROY))
        applier.detach().join()
        scope.runCurrent()
        exo.release()
        serviceJob.cancel()
        saved?.join()
        cache.cache().release()
        db.close()
    }

    companion object {
        const val DURATION_MS = 3_600_000L
    }
}

internal class RecordingPlayer(
    player: androidx.media3.common.Player,
) : ForwardingPlayer(player) {
    val calls = mutableListOf<String>()
    var beforePrepare: () -> Unit = {}
    var advancing: Boolean? = null

    override fun isPlaying(): Boolean = advancing ?: super.isPlaying()

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        calls += "set"
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun moveMediaItem(
        currentIndex: Int,
        newIndex: Int,
    ) {
        calls += "move"
        super.moveMediaItem(currentIndex, newIndex)
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: MediaItem,
    ) {
        calls += "metadata"
        super.replaceMediaItem(index, mediaItem)
    }

    override fun prepare() {
        beforePrepare()
        calls += "prepare"
        super.prepare()
    }

    fun ids(): List<Long> =
        (0 until mediaItemCount).map { getMediaItemAt(it).mediaId.removePrefix("episode:").toLong() }
}

internal class AdapterSources : MediaSource.Factory {
    val created = mutableListOf<FakeMediaSource>()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val definition =
            FakeTimeline.TimelineWindowDefinition
                .Builder()
                .setMediaItem(mediaItem)
                .setDurationUs(AdapterFixture.DURATION_MS * 1000)
                .build()
        return FakeMediaSource(FakeTimeline(definition)).also { created += it }
    }

    override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun TestScope.awaitAdapter(
    message: String = "Adapter did not converge",
    predicate: () -> Boolean,
) {
    repeat(2_000) {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        if (predicate()) return
        Thread.sleep(2)
    }
    fail(message)
}

private object TestArtwork : ArtworkStore {
    override fun pinnedFile(key: String): Path? = null

    override fun pin(
        ref: ArtworkRef,
        reason: PinReason,
        ownerId: Long,
    ) = Unit

    override fun unpin(
        key: String,
        reason: PinReason,
        ownerId: Long,
    ) = Unit

    override fun contentUri(
        key: String,
        version: Int,
    ) = "content://ch.lkmc.neutrodyne.debug.artwork/$key?v=$version"

    override fun isPinned(key: String) = false

    override fun pinnedPath(key: String): Path? = null

    override suspend fun collectGarbage() = 0
}

/** Exercise feed/restore writes without adding a production mutation API for tests. */
internal suspend fun AdapterFixture.sql(statement: String) {
    db.withWriteTransaction {
        db.useWriterConnection { connection ->
            connection.usePrepared(statement) { it.step() }
        }
    }
}

internal suspend fun AdapterFixture.awaitSaved(
    id: Long,
    positionMs: Long,
) {
    positions.observe(id).first { it?.positionMs == positionMs }
}
