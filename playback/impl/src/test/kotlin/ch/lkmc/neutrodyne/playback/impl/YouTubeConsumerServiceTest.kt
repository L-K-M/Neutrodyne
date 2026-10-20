// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Looper
import androidx.media3.session.MediaController
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.model.settings.YouTubeAudioQuality
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.PortableYouTubePlayback
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.core.StartRequest
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [34])
class YouTubeConsumerServiceTest {
    @Test fun playingTickRetainsTheSharedFlightAfterDetachAndPlaybackReusesIt() = exercise(StreamOnMetered.ALLOW)

    @Test fun meteredTickDoesNotRetainTheLoadersFlightAfterDetach() = exercise(StreamOnMetered.NEVER)

    private fun exercise(policy: StreamOnMetered) {
        val app = RuntimeEnvironment.getApplication() as PlaybackTestApplication
        LifecycleHarness(app).use { h ->
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val reply = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                val port =
                    PortableYouTubePlayback(h.clock, ownerScope) { invalidate ->
                        PlaybackTestStores.health(h.settings, h.clock, ownerScope, invalidate)
                    }
                h.run {
                    h.settings.set(YouTubeSettingKeys.AUDIO_QUALITY, YouTubeAudioQuality.DATA_SAVER)
                    h.settings.set(YouTubeSettingKeys.VOLUME_LEVELLING, true)
                    h.settings.set(PlaybackSettingKeys.STREAM_ON_METERED, policy)
                }
                port.compiledStore()
                pumpUntil { port.control.status.value.availability == EngineAvailability.STOPPED }
                port.control.prewarm(PrewarmReason.SCREEN)
                val access = h.run { h.realYouTubeAccess(port) }
                assertSame(port.control, access.engine)
                assertSame(port.streams, access.streams)
                val db = h.opener.requireDatabase()
                val nextBytes = checkNotNull(javaClass.getResource("/media/chapters_both.m4b")).readBytes()
                MockWebServer().use { server ->
                    server.start()
                    server.enqueue(MockResponse.Builder().body(Buffer().write(wave())).build())
                    server.enqueue(MockResponse.Builder().body(Buffer().write(nextBytes)).build())
                    val ids =
                        h.run {
                            val rss = db.podcastDao().insertPodcast(podcastEntity())
                            val current =
                                db.episodeDao().insertStub(
                                    episodeEntity(podcastId = rss) {
                                        copy(enclosureUrl = server.url("/current.wav").toString(), durationMs = 50_000)
                                    },
                                )
                            val channel =
                                db.podcastDao().insertPodcast(
                                    podcastEntity {
                                        copy(
                                            sourceType = SourceType.YOUTUBE_CHANNEL,
                                            youtubeChannelId = "UC" + "0".repeat(21) + "A",
                                        )
                                    },
                                )
                            val next =
                                db.episodeDao().insertStub(
                                    episodeEntity(podcastId = channel) {
                                        copy(externalMediaId = "abcdefghijk", enclosureUrl = null, durationMs = 120_000)
                                    },
                                )
                            current to next
                        }
                    val url = server.url("/next?itag=140&clen=${nextBytes.size}&lmt=123&expire=4102444800")
                    port.reply {
                        reply.await()
                        """{"videoId":"abcdefghijk","duration":120,"liveStatus":"not_live","availability":null,
                            "releaseTs":null,"ageLimit":0,"formats":[{"formatId":"140","url":"$url","ext":"m4a",
                            "acodec":"mp4a.40.2","abr":128.0,"tbr":null,"filesize":${nextBytes.size},"language":null,
                            "languagePreference":-1,"formatNote":"medium","protocol":"http",
                            "availableAt":null,"xtags":null}]}"""
                    }
                    h.run { port.health.awaitLoaded() }
                    app.graph =
                        object : PlaybackServiceInjector {
                            override fun inject(service: NeutrodynePlaybackService) {
                                service.access = access
                            }
                        }
                    val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
                    var controller: MediaController? = null
                    var destroyed = false
                    try {
                        val session = service.get().onGetSession(controllerInfo())!!
                        val active = h.run { MediaController.Builder(app, session.token).buildAsync().await() }
                        controller = active
                        h.run {
                            active
                                .sendCustomCommand(
                                    CustomCommands.command(CustomCommands.PLAY_CONTEXT),
                                    CustomCommands.encode(StartRequest.Episode(ids.first)),
                                ).await()
                        }
                        // Use Media3's real standalone clock, not Robolectric's draining AudioTrack.
                        // The real source, extractors, HTTP, cache and service remain installed.
                        session.player.trackSelectionParameters =
                            session.player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                                .build()
                        active.play()
                        pumpUntil { session.player.isPlaying }
                        h.network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
                        h.run { access.open().queues.addLast(listOf(ids.second)) }
                        pumpUntil { session.player.mediaItemCount == 2 }
                        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
                        if (policy == StreamOnMetered.ALLOW) {
                            pumpUntil {
                                shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
                                port.nativeCalls() == 1
                            }
                        }
                        controller.release()
                        controller = null
                        service.destroy()
                        destroyed = true
                        // Wait for the actual blocking loader to release its W5 waiter.
                        pumpUntil {
                            Thread.getAllStackTraces().values.none { frames ->
                                frames.any {
                                    it.className == EpisodeResolver::class.java.name &&
                                        it.methodName == "blocking"
                                }
                            }
                        }
                        assertNull(access.resolver.streamKind(ids.second))
                        assertEquals(1, server.requestCount)
                        if (policy == StreamOnMetered.NEVER) {
                            assertTrue(port.nativeCalls() <= 1)
                            assertEquals(port.nativeCalls(), port.cancellations())
                            return
                        }
                        assertEquals(0, port.cancellations())
                        reply.complete(Unit)
                        h.run { port.streams.resolveAudio("abcdefghijk", access.audioPref()) }
                        assertEquals(1, port.nativeCalls())
                        assertNull(access.resolver.streamKind(ids.second))
                        assertEquals(listOf(PrewarmReason.SCREEN), port.prewarms())
                        assertEquals(0, port.preparations())
                        assertNull(h.run { db.episodeStateDao().byEpisode(ids.second) })
                        assertEquals(
                            ids.first,
                            h.run {
                                access
                                    .open()
                                    .sessions
                                    .virtualQueue(20)
                                    .current!!
                                    .episodeId
                            },
                        )
                        val replay = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
                        var resumed: MediaController? = null
                        try {
                            val newSession = replay.get().onGetSession(controllerInfo())!!
                            val next = h.run { MediaController.Builder(app, newSession.token).buildAsync().await() }
                            resumed = next
                            h.run {
                                next
                                    .sendCustomCommand(
                                        CustomCommands.command(CustomCommands.PLAY_CONTEXT),
                                        CustomCommands.encode(StartRequest.Episode(ids.second)),
                                    ).await()
                            }
                            next.play()
                            pumpUntil { server.requestCount == 2 }
                            assertEquals(1, port.nativeCalls())
                            assertEquals(StreamKind.YOUTUBE, access.resolver.streamKind(ids.second))
                        } finally {
                            resumed?.release()
                            replay.destroy()
                        }
                        assertNull(h.run { db.episodeStateDao().byEpisode(ids.second)?.playedAt })
                    } finally {
                        controller?.release()
                        if (!destroyed) service.destroy()
                    }
                    val calls = port.nativeCalls()
                    port.streams.invalidateAll()
                    shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
                    assertEquals(calls, port.nativeCalls())
                }
            } finally {
                reply.complete(Unit)
                ownerScope.cancel()
            }
        }
    }

    private fun wave(): ByteArray {
        val pcmSize = 50 * 8_000 * 2
        return ByteBuffer
            .allocate(44 + pcmSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(36 + pcmSize)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(8_000)
                putInt(16_000)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(pcmSize)
            }.array()
    }
}
