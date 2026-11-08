// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.session.MediaController
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.YouTubeAudioQuality
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.testing.PortableYouTubePlayback
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [34])
class YouTubeServiceIntegrationTest {
    @Test fun injectedServiceUsesRealRoomPortsAndSharedOwnersThroughMedia403Retry() = exerciseService(false)

    @Test fun userPlayAfterEngineFailurePreparesAFreshResolveAtTheCurrentPosition() = exerciseService(true)

    private fun exerciseService(engineFails: Boolean) {
        val app = RuntimeEnvironment.getApplication() as PlaybackTestApplication
        LifecycleHarness(app).use { h ->
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val port =
                    PortableYouTubePlayback(h.clock, ownerScope) { invalidate ->
                        PlaybackTestStores.health(h.settings, h.clock, ownerScope, invalidate)
                    }
                h.run {
                    h.settings.set(YouTubeSettingKeys.AUDIO_QUALITY, YouTubeAudioQuality.DATA_SAVER)
                    h.settings.set(YouTubeSettingKeys.VOLUME_LEVELLING, true)
                }
                val access = h.run { h.realYouTubeAccess(port) }
                assertEquals(YouTubeAudioQuality.DATA_SAVER, access.prefs.current.value.youtubeQuality)
                assertTrue(access.prefs.current.value.youtubeLevelling)
                val db = h.opener.requireDatabase()
                val id =
                    h.run {
                        val channel =
                            db.podcastDao().insertPodcast(
                                podcastEntity {
                                    copy(
                                        sourceType = SourceType.YOUTUBE_CHANNEL,
                                        youtubeChannelId = "UC" + "0".repeat(21) + "A",
                                    )
                                },
                            )
                        db.episodeDao().insertStub(
                            episodeEntity(podcastId = channel) {
                                copy(externalMediaId = "abcdefghijk", enclosureUrl = null, durationMs = 60_000)
                            },
                        )
                    }
                val before = h.run { db.episodeDao().byId(id) }
                val bytes = checkNotNull(javaClass.getResource("/media/chapters_both.m4b")).readBytes()
                MockWebServer().use { server ->
                    server.start()
                    server.enqueue(MockResponse.Builder().code(403).build())
                    server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
                    val url = server.url("/audio?itag=140&clen=${bytes.size}&lmt=123&expire=4102444800")
                    val success: suspend () -> String = {
                        """{"videoId":"abcdefghijk","duration":60,"liveStatus":"not_live","availability":null,
                            "releaseTs":null,"ageLimit":0,"formats":[{"formatId":"140","url":"$url","ext":"m4a",
                            "acodec":"mp4a.40.2","abr":128.0,"tbr":null,"filesize":${bytes.size},"language":null,
                            "languagePreference":-1,"formatNote":"medium","protocol":"http",
                            "availableAt":null,"xtags":null}]}"""
                    }
                    if (engineFails) port.failWith("ENGINE_UNAVAILABLE") else port.reply(success)
                    runBlocking { port.health.awaitLoaded() }
                    app.graph =
                        object : PlaybackServiceInjector {
                            override fun inject(service: NeutrodynePlaybackService) {
                                service.access = access
                            }
                        }
                    val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
                    var controller: MediaController? = null
                    try {
                        val session = service.get().onGetSession(controllerInfo())!!
                        val active = h.run { MediaController.Builder(app, session.token).buildAsync().await() }
                        controller = active
                        h.run {
                            active
                                .sendCustomCommand(
                                    CustomCommands.command(CustomCommands.PLAY_CONTEXT),
                                    CustomCommands.encode(StartRequest.Episode(id)),
                                ).await()
                        }
                        active.play()
                        var failedCalls = 0
                        if (engineFails) {
                            pumpUntil {
                                org.robolectric.Shadows
                                    .shadowOf(android.os.Looper.getMainLooper())
                                    .idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
                                session.player.playerError != null && !session.player.playWhenReady
                            }
                            failedCalls = port.nativeCalls()
                            val position = session.player.currentPosition
                            port.reply(success)
                            active.play()
                            pumpUntil { server.requestCount >= 2 }
                            assertEquals(position, session.player.currentPosition)
                        } else {
                            pumpUntil { server.requestCount >= 2 }
                        }
                        h.run {
                            port.streams.resolveAudio(
                                "abcdefghijk",
                                ch.lkmc.neutrodyne.youtube.api.AudioPref(
                                    YouTubeAudioQuality.DATA_SAVER,
                                    true,
                                    preferredLanguage =
                                        app.resources.configuration.locales[0]
                                            .language,
                                ),
                            )
                        }
                        // A cache hit proves the service supplied the durable quality/DRC/language tuple.
                        assertEquals(failedCalls + 2, port.nativeCalls())
                        assertEquals(StreamKind.YOUTUBE, access.resolver.streamKind(id))
                        assertEquals(before, h.run { db.episodeDao().byId(id) })
                        assertNull(h.run { db.episodeStateDao().byEpisode(id)?.playedAt })
                        assertEquals("episode:$id", session.player.currentMediaItem?.mediaId)
                        assertEquals(2, server.requestCount)
                    } finally {
                        controller?.release()
                        service.destroy()
                    }
                }
            } finally {
                ownerScope.cancel()
            }
        }
    }
}
