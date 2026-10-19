// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.content.ComponentName
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.MainActivity
import ch.lkmc.neutrodyne.NeutrodyneApplication
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** Platform MediaBrowser/transport bridge, including legacy unset start index/time. DHU is W9. */
@RunWith(AndroidJUnit4::class)
class CarLibraryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NeutrodyneApplication
    private val graph get() = app.graph
    private val server = MockWebServer()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var browser: MediaBrowser? = null
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
            db.groupDao().insertMember(PodcastGroupMemberEntity(g, p, "a0", System.currentTimeMillis()))
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
            graph.playbackController.dismiss()
            scenario?.close()
            app.stopService(Intent(app, NeutrodynePlaybackService::class.java))
            val db = graph.databaseOpener.requireDatabase()
            group?.let { db.groupDao().deleteGroup(it) }
            podcast?.let { db.podcastDao().deleteCascade(it, System.currentTimeMillis()) }
            server.close()
        }

    @Test fun legacyBrowserListsFourRootsAndQueuedGroupTapKeepsGroupContext() =
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
                    browser.sessionToken,
                ).transportControls.playFromMediaId("episode:$episode@group:$group", null)
            }
            awaitCurrent()
            assertEquals(ContextType.GROUP, db.playSessionDao().get()?.contextType)
            assertEquals(group, db.playSessionDao().get()?.contextId)
            assertEquals(generation + 1, db.playSessionDao().get()?.generation)
        }

    @Test fun legacyAssistantSearchStartsExactGroupWithoutExplicitIndexOrTime() =
        runBlocking {
            val browser = connect()
            withContext(Dispatchers.Main) {
                MediaController(app, browser.sessionToken).transportControls.playFromSearch(groupName, null)
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

    private suspend fun connect(): MediaBrowser {
        val connected = CompletableDeferred<Unit>()
        val created =
            withContext(Dispatchers.Main) {
                MediaBrowser(
                    app,
                    ComponentName(app, NeutrodynePlaybackService::class.java),
                    object : MediaBrowser.ConnectionCallback() {
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

    private suspend fun children(parent: String): List<MediaBrowser.MediaItem> {
        val result = CompletableDeferred<List<MediaBrowser.MediaItem>>()
        withContext(Dispatchers.Main) {
            browser!!.subscribe(
                parent,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                        parentId: String,
                        children: MutableList<MediaBrowser.MediaItem>,
                    ) {
                        result.complete(children)
                    }

                    override fun onError(parentId: String) {
                        result.completeExceptionally(IllegalArgumentException("Legacy browse failed: $parentId"))
                    }
                },
            )
        }
        return withTimeout(20_000) { result.await() }
    }

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
