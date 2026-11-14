// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.session.legacy.MediaBrowserCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.NeutrodyneApplication
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Legacy MediaBrowserCompat/transport bridge, including legacy unset start index/time.
 * MediaBrowserCompat reports its client version in the root hints, so the service
 * returns a messenger binder and subscriptions ride it with the real RemoteUserInfo.
 * The framework MediaBrowser path stays anonymous on API <28 and cannot browse a
 * media3 library; compat clients (Android Auto among them) can. DHU requires
 * separate head-unit evidence.
 */
@RunWith(AndroidJUnit4::class)
class CarLibraryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NeutrodyneApplication
    private val graph get() = app.graph
    private val server = MockWebServer()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var browser: MediaBrowserCompat? = null
    private var podcast: Long? = null
    private var group: Long? = null
    private var episode = 0L
    private var groupName = ""

    @Before fun prepare() =
        runBlocking {
            graph.databaseOpener.awaitOpen()
            graph.playbackController.dismiss()
            server.start()
            val mp3 =
                instrumentation.context.assets
                    .open("media/cbr_128k_2s.mp3")
                    .use { it.readBytes() }
            repeat(10) {
                val body = Buffer().apply { repeat(20) { write(mp3) } }
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "audio/mpeg")
                        .body(body)
                        .build(),
                )
            }
            val db = graph.databaseOpener.requireDatabase()
            val p =
                db.podcastDao().insertPodcast(podcastEntity(title = "M5 legacy fixture")).also { podcast = it }
            groupName = "M5 car ${System.nanoTime()}"
            val g =
                db.groupDao().insertGroupOrIgnore(groupEntity(name = groupName, orderKey = "a0")).also { group = it }
            db.groupDao().insertMember(memberEntity(groupId = g, podcastId = p, orderKey = "a0"))
            episode =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = p) {
                        copy(
                            enclosureUrl = server.url("/episode.mp3").toString(),
                            durationMs = 40_000,
                        )
                    },
                )
            graph.queueRepository().addNext(listOf(episode))
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }

    @After fun close() =
        runBlocking {
            withContext(Dispatchers.Main) { browser?.disconnect() }
            dismissAfterStartSettles()
            scenario?.close()
            app.stopService(Intent(app, NeutrodynePlaybackService::class.java))
            val db = graph.databaseOpener.requireDatabase()
            group?.let { db.groupDao().deleteGroup(it) }
            podcast?.let { db.podcastDao().deleteCascade(it, System.currentTimeMillis()) }
            server.close()
        }

    @Test @ReleaseSmoke
    fun legacyBrowserListsFourRootsAndQueuedGroupTapKeepsGroupContext() =
        runBlocking {
            graph.queueRepository().addLast(listOf(episode))
            val browser = connect()
            assertEquals(listOf("upnext", "groups", "downloads", "podcasts"), children(browser.root).map { it.mediaId })
            assertTrue(children("downloads").isEmpty())
            val episodes = children("group:$group")
            assertTrue(episodes.any { it.mediaId == "episode:$episode@group:$group" })
            val db = graph.databaseOpener.requireDatabase()
            val generation = db.playSessionDao().get()?.generation ?: 0
            withContext(Dispatchers.Main) {
                MediaController(
                    app,
                    sessionToken(),
                ).transportControls.playFromMediaId("episode:$episode@group:$group", null)
            }
            awaitCurrent()
            assertEquals(ContextType.GROUP, db.playSessionDao().get()?.contextType)
            assertEquals(group, db.playSessionDao().get()?.contextId)
            assertEquals(generation + 1, db.playSessionDao().get()?.generation)
        }

    @Test @ReleaseSmoke
    fun legacyAssistantSearchStartsExactGroupWithoutExplicitIndexOrTime() =
        runBlocking {
            connect()
            withContext(Dispatchers.Main) {
                MediaController(app, sessionToken()).transportControls.playFromSearch(groupName, null)
            }
            awaitCurrent()
            val session =
                graph.databaseOpener
                    .requireDatabase()
                    .playSessionDao()
                    .get()!!
            assertEquals(ContextType.GROUP, session.contextType)
            assertEquals(group, session.contextId)
        }

    @Test @ReleaseSmoke
    fun legacyHardwareSkipIgnoresStoredEpisodeMode() =
        runBlocking {
            val db = graph.databaseOpener.requireDatabase()
            val next =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = checkNotNull(podcast)) {
                        copy(enclosureUrl = server.url("/next.mp3").toString(), durationMs = 40_000)
                    },
                )
            graph.queueRepository().addLast(listOf(next))
            connect()
            val transport =
                withContext(Dispatchers.Main) { MediaController(app, sessionToken()).transportControls }
            try {
                graph.settingsRepository.set(PlaybackSettingKeys.HARDWARE_BUTTONS, HardwareButtons.SKIP)
                graph.settingsRepository.set(PlaybackSettingKeys.SKIP_FORWARD_MS, SKIP_MS)
                // The service-side prefs collector trails the durable write; confirm
                // the value is observable before the transport call consumes it.
                graph.settingsRepository
                    .observe(PlaybackSettingKeys.HARDWARE_BUTTONS)
                    .first { it == HardwareButtons.SKIP }
                withContext(Dispatchers.Main) { transport.playFromMediaId("episode:$episode@upnext", null) }
                awaitCurrent()
                awaitPlaying(true)

                // Paused, so ordinary playback cannot stand in for the skip: a no-op stays put.
                withContext(Dispatchers.Main) { transport.pause() }
                awaitPlaying(false)
                val before = checkNotNull(currentPosition())
                withContext(Dispatchers.Main) { transport.skipToNext() }
                val expected = (before + SKIP_MS - SKIP_TOLERANCE_MS)..(before + SKIP_MS + SKIP_TOLERANCE_MS)
                withTimeout(20_000) {
                    while (currentPosition()?.let { it in expected } != true) delay(50)
                }
                assertEquals(
                    false,
                    graph.playbackStateSource.nowPlaying.value
                        ?.isPlaying,
                )

                graph.settingsRepository.set(PlaybackSettingKeys.HARDWARE_BUTTONS, HardwareButtons.EPISODE)
                withContext(Dispatchers.Main) { transport.seekTo(0) }
                withTimeout(20_000) {
                    while ((currentPosition() ?: Long.MAX_VALUE) > SKIP_TOLERANCE_MS) delay(50)
                }
                withContext(Dispatchers.Main) { transport.skipToNext() }
                val fromStart = (SKIP_MS - SKIP_TOLERANCE_MS)..(SKIP_MS + SKIP_TOLERANCE_MS)
                withTimeout(20_000) {
                    while (currentPosition()?.let { it in fromStart } != true) delay(50)
                }
                assertEquals(
                    episode,
                    graph.playbackStateSource.nowPlaying.value
                        ?.episodeId,
                )
            } finally {
                graph.settingsRepository.set(PlaybackSettingKeys.HARDWARE_BUTTONS, HardwareButtons.EPISODE)
                graph.settingsRepository.set(
                    PlaybackSettingKeys.SKIP_FORWARD_MS,
                    PlaybackSettingKeys.SKIP_FORWARD_MS.default,
                )
            }
        }

    private fun sessionToken() = checkNotNull(browser).sessionToken.token as MediaSession.Token

    private suspend fun connect(): MediaBrowserCompat {
        val connected = CompletableDeferred<Unit>()
        val created =
            withContext(Dispatchers.Main) {
                MediaBrowserCompat(
                    app,
                    ComponentName(app, NeutrodynePlaybackService::class.java),
                    object : MediaBrowserCompat.ConnectionCallback() {
                        override fun onConnected() {
                            connected.complete(Unit)
                        }

                        override fun onConnectionFailed() {
                            connected.completeExceptionally(IllegalStateException("Legacy browser connection failed"))
                        }
                    },
                    Bundle(),
                ).also {
                    browser = it
                    it.connect()
                }
            }
        withTimeout(20_000) { connected.await() }
        return created
    }

    private suspend fun children(parent: String): List<MediaBrowserCompat.MediaItem> {
        val result = CompletableDeferred<List<MediaBrowserCompat.MediaItem>>()
        withContext(Dispatchers.Main) {
            browser!!.subscribe(
                parent,
                // Non-null options route results to the three-argument
                // onChildrenLoaded/onError overloads; both forward below.
                Bundle(),
                object : MediaBrowserCompat.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                        parentId: String?,
                        children: MutableList<MediaBrowserCompat.MediaItem>?,
                    ) {
                        children?.let(result::complete)
                            ?: result.completeExceptionally(
                                IllegalArgumentException("Legacy browse failed: $parentId"),
                            )
                    }

                    override fun onChildrenLoaded(
                        parentId: String?,
                        children: MutableList<MediaBrowserCompat.MediaItem>?,
                        options: Bundle?,
                    ) {
                        onChildrenLoaded(parentId, children)
                    }

                    override fun onError(parentId: String?) {
                        result.completeExceptionally(IllegalArgumentException("Legacy browse failed: $parentId"))
                    }

                    override fun onError(
                        parentId: String?,
                        options: Bundle?,
                    ) {
                        onError(parentId)
                    }
                },
            )
        }
        return withTimeout(20_000) { result.await() }
    }

    // A start still settling can supersede teardown's dismiss; the newer intent is right to win.
    private suspend fun dismissAfterStartSettles() {
        repeat(DISMISS_RETRIES) {
            try {
                graph.playbackController.dismiss()
                return
            } catch (_: CancellationException) {
                delay(200)
            }
        }
        graph.playbackController.dismiss()
    }

    private suspend fun awaitPlaying(playing: Boolean) =
        withTimeout(20_000) {
            while (graph.playbackStateSource.nowPlaying.value?.let {
                    it.episodeId == episode && it.isPlaying == playing
                } != true
            ) {
                delay(50)
            }
        }

    private fun currentPosition(): Long? =
        graph.playbackStateSource.nowPlaying.value
            ?.takeIf { it.episodeId == episode }
            ?.position
            ?.positionAt(SystemClock.elapsedRealtime())

    private suspend fun awaitCurrent() =
        withTimeout(20_000) {
            while (graph.databaseOpener
                    .requireDatabase()
                    .playSessionDao()
                    .get()
                    ?.currentEpisodeId != episode
            ) {
                delay(50)
            }
        }
}

private const val DISMISS_RETRIES = 3
private const val SKIP_MS = 10_000L
private const val SKIP_TOLERANCE_MS = 1_000L
