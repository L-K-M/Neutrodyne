// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.EpisodeAltEnclosureEntity
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CarLibraryTest {
    @Test fun groupAndDownloadsTapsRetainContextAndRejectStaleParents() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.run {
                h.opener.awaitOpen()
                val db = h.opener.requireDatabase()
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val other = db.podcastDao().insertPodcast(podcastEntity())
                val group = db.groupDao().insertGroupOrIgnore(groupEntity(orderKey = "a0"))
                db.groupDao().insertMember(PodcastGroupMemberEntity(group, podcast, "a0", 0))
                val episode =
                    db.episodeDao().insertStub(episodeEntity(podcastId = podcast, sortDate = 10))
                val outside =
                    db.episodeDao().insertStub(episodeEntity(podcastId = other, sortDate = 20))
                h.lookup.items.value = mapOf(episode to media(id = episode), outside to media(id = outside))
                h.seedCurrent(episode)

                fun item(id: String) = MediaItem.Builder().setMediaId(id).build()
                val request =
                    assertIs<StartRequest.Feed>(
                        h.tree.startRequest(listOf(item("episode:$episode@group:$group")), C.INDEX_UNSET, C.TIME_UNSET),
                    )
                assertEquals(FeedSource.Group(group), request.source)
                assertEquals(episode, request.startEpisodeId)
                assertEquals(null, request.positionMs)
                assertEquals(
                    listOf("episode:$episode@group:$group"),
                    h.tree.children("group:$group", 0, 100).map { it.mediaId },
                )
                assertFailsWith<IllegalArgumentException> {
                    h.tree.startRequest(
                        listOf(item("episode:$outside@group:$group")),
                        0,
                        0,
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    h.tree.startRequest(
                        listOf(item("episode:$episode@podcast:$other")),
                        0,
                        0,
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    h.tree.startRequest(
                        listOf(item("episode:$episode@downloads")),
                        0,
                        0,
                    )
                }
                db.downloadDao().insert(
                    ch.lkmc.neutrodyne.core.testing.database
                        .downloadEntity(episode),
                )
                val downloads =
                    assertIs<StartRequest.Downloads>(
                        h.tree.startRequest(listOf(item("episode:$episode@downloads")), 0, 42_000),
                    )
                assertEquals(episode, downloads.startEpisodeId)
                assertEquals(42_000L, downloads.positionMs)
                val downloaded = h.tree.children("downloads", 0, 100).map { it.mediaId }
                assertEquals(listOf("episode:$episode@downloads"), downloaded)
                db.groupDao().deleteGroup(group)
                assertFailsWith<IllegalArgumentException> {
                    h.tree.startRequest(
                        listOf(item("episode:$episode@group:$group")),
                        0,
                        0,
                    )
                }
            }
        }
    }

    @Test fun assistantUsesGroupThenDisplayedPodcastThenNewestEpisodeAndEmptyResumes() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.run {
                h.opener.awaitOpen()
                val db = h.opener.requireDatabase()
                val p =
                    db.podcastDao().insertPodcast(
                        podcastEntity(title = "Original") { copy(customTitle = "Renamed 100%_\\日本語") },
                    )
                val group =
                    db.groupDao().insertGroupOrIgnore(groupEntity(name = "Renamed", orderKey = "a0"))
                val id =
                    db.episodeDao().insertStub(episodeEntity(podcastId = p, title = "Unique episode", sortDate = 10))
                val newer =
                    db.episodeDao().insertStub(episodeEntity(podcastId = p, title = "Unique episode", sortDate = 20))
                h.lookup.items.value = mapOf(id to media(id = id), newer to media(id = newer))

                suspend fun request(query: String): StartRequest {
                    val item =
                        MediaItem
                            .Builder()
                            .setRequestMetadata(
                                MediaItem.RequestMetadata
                                    .Builder()
                                    .setSearchQuery(query)
                                    .build(),
                            ).build()
                    return h.tree.startRequest(listOf(item), C.INDEX_UNSET, C.TIME_UNSET)
                }
                assertEquals(StartRequest.Resume, request(""))
                val groupRequest = assertIs<StartRequest.Feed>(request("RENAMED"))
                assertEquals(FeedSource.Group(group), groupRequest.source)
                val podcastRequest = assertIs<StartRequest.Feed>(request("100%_\\日本語"))
                assertEquals(FeedSource.Podcast(p), podcastRequest.source)
                assertEquals(newer, assertIs<StartRequest.Episode>(request("unique episode")).episodeId)
                assertFailsWith<UnsupportedOperationException> { request("missing") }
                val unicode = db.episodeDao().insertStub(episodeEntity(podcastId = p, title = "Ångström"))
                h.lookup.items.value += unicode to media(id = unicode)
                assertEquals(unicode, assertIs<StartRequest.Episode>(request("ångström")).episodeId)
                assertEquals(listOf("podcast:$p"), h.tree.search("100%_\\日本語").map { it.mediaId })
                assertEquals(
                    listOf("episode:$newer", "episode:$id"),
                    h.tree.search("Unique episode").map { it.mediaId },
                )
            }
        }
    }

    @Test fun externalListsValidateIndexTimeAndParentsBeforeAnyStart() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.run {
                val items = (1L..3L).map { MediaItem.Builder().setMediaId("episode:$it").build() }
                val request =
                    assertIs<StartRequest.Episode>(h.tree.startRequest(items, C.INDEX_UNSET, C.TIME_UNSET))
                assertEquals(1L, request.episodeId)
                assertEquals(listOf(2L, 3L), request.externalQueue)
                assertFailsWith<IllegalArgumentException> { h.tree.startRequest(items, 3, 0) }
                assertFailsWith<IllegalArgumentException> { h.tree.startRequest(items, -2, 0) }
                assertFailsWith<IllegalArgumentException> { h.tree.startRequest(items, 0, -1) }
                assertFailsWith<IllegalArgumentException> {
                    h.tree.startRequest(
                        listOf(items[0].buildUpon().setMediaId("episode:1@upnext").build(), items[1]),
                        0,
                        0,
                    )
                }
                for (bad in listOf(
                    "episode:01",
                    "episode:1@group:0",
                    "episode:1@group:999999999999999999999",
                    "episode:1@podcast:01",
                    "episode:1@upnext/x",
                    "https://evil.test/a",
                )) {
                    assertFailsWith<IllegalArgumentException> {
                        h.tree.startRequest(listOf(items[0].buildUpon().setMediaId(bad).build()), 0, 0)
                    }
                }
                assertTrue(h.store.calls.isEmpty())
            }
        }
    }

    @Test fun libraryInvalidationIgnoresPositionWritesButIncludesCommittedUserState() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.run {
                h.opener.awaitOpen()
                val db = h.opener.requireDatabase()
                val podcast = db.podcastDao().insertPodcast(podcastEntity())
                val id = db.episodeDao().insertStub(episodeEntity(podcastId = podcast))
                val signals = Channel<Unit>(Channel.UNLIMITED)
                val observation =
                    h.scope.launch(Dispatchers.IO) { h.tree.changes().collect { signals.send(Unit) } }
                try {
                    withTimeout(5_000) { signals.receive() }
                    db.positionDao().upsert(
                        EpisodePositionEntity(id, 123, positionSource = PositionSource.STREAM, updatedAt = 1),
                    )
                    assertEquals(
                        null,
                        withContext(Dispatchers.Default) { withTimeoutOrNull(150) { signals.receive() } },
                    )
                    db.episodeStateDao().upsert(EpisodeStateEntity(id, playedAt = 1, updatedAt = 1))
                    withTimeout(5_000) { signals.receive() }
                    db.ingestDao().replaceChildren(
                        id,
                        null,
                        emptyList(),
                        listOf(EpisodeAltEnclosureEntity(id, 0, "audio/mpeg", sourcesJson = "[]")),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                    )
                    val alternateChanged =
                        withContext(Dispatchers.Default) {
                            withTimeoutOrNull(5_000) {
                                signals.receive()
                                true
                            }
                        }
                    assertEquals(true, alternateChanged)
                } finally {
                    observation.cancel()
                }
            }
        }
    }

    @Test fun mediaBrowserExercisesRootLimitChildrenSearchAndReadOnlyBrowse() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            val exo = h.playerFactory.create()
            val callback =
                SessionCallback(
                    {
                        error("Browse activated playback")
                    },
                    ResumptionProvider {
                        error(
                            "Unexpected playback",
                        )
                    },
                    h.tree,
                    MediaButtons(h.prefs),
                    h.scope,
                    Dispatchers.IO,
                )
            val session = MediaLibrarySession.Builder(h.app, exo, callback).build()
            val notifications = Channel<String>(Channel.UNLIMITED)
            val browser =
                h.run {
                    MediaBrowser
                        .Builder(h.app, session.token)
                        .setListener(
                            object : MediaBrowser.Listener {
                                override fun onChildrenChanged(
                                    browser: MediaBrowser,
                                    parentId: String,
                                    itemCount: Int,
                                    params: LibraryParams?,
                                ) {
                                    notifications.trySend(parentId)
                                }
                            },
                        ).buildAsync()
                        .await()
                }
            try {
                h.run {
                    val params =
                        LibraryParams
                            .Builder()
                            .setExtras(
                                Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 1) },
                            ).build()
                    assertEquals(
                        "root",
                        browser
                            .getLibraryRoot(params)
                            .await()
                            .value
                            ?.mediaId,
                    )
                    assertEquals(
                        listOf("upnext"),
                        browser
                            .getChildren("root", 0, 100, null)
                            .await()
                            .value!!
                            .map { it.mediaId },
                    )
                    assertEquals(
                        "episode:1@upnext",
                        browser
                            .getChildren("upnext", 0, 100, null)
                            .await()
                            .value!!
                            .single()
                            .mediaId,
                    )
                    assertEquals(0, browser.subscribe("upnext", null).await().resultCode)
                    assertEquals(0, browser.unsubscribe("upnext").await().resultCode)
                    assertEquals(0, browser.search("missing", null).await().resultCode)
                    assertTrue(
                        browser
                            .getSearchResult("missing", 0, 100, null)
                            .await()
                            .value!!
                            .isEmpty(),
                    )
                    assertTrue(browser.getChildren("unknown", 0, 100, null).await().resultCode < 0)
                    val db = h.opener.requireDatabase()
                    val group = groupEntity(name = "Auto mosaic", orderKey = "a0")
                    val groupId = db.groupDao().insert(group)
                    val key = "g-${group.uuid}"
                    db.artworkDao().upsert(ArtworkEntity(key, version = 1))
                    assertEquals(0, browser.subscribe("groups", null).await().resultCode)
                    withTimeout(5_000) { while (notifications.receive() != "groups") Unit }
                    db.artworkDao().upsert(ArtworkEntity(key, version = 2))
                    withTimeout(5_000) { while (notifications.receive() != "groups") Unit }
                    val folder =
                        browser
                            .getChildren("groups", 0, 100, null)
                            .await()
                            .value!!
                            .single()
                    assertEquals("content://synthetic.artwork/$key?v=2", folder.mediaMetadata.artworkUri.toString())
                    val uri = folder.mediaMetadata.artworkUri
                    assertEquals(
                        uri,
                        h.tree
                            .item("group:$groupId")!!
                            .mediaMetadata.artworkUri,
                    )
                    assertEquals(
                        uri,
                        h.tree
                            .search("Auto mosaic")
                            .single()
                            .mediaMetadata.artworkUri,
                    )
                    assertEquals(0, browser.unsubscribe("groups").await().resultCode)
                    assertEquals(0, exo.mediaItemCount)
                }
            } finally {
                browser.release()
                session.release()
                exo.release()
            }
        }
    }
}
