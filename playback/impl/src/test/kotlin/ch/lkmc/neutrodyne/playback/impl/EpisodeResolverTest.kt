// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.net.Uri
import androidx.media3.datasource.DataSpec
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nonCanonicalUrisPassThroughAndMalformedIdentitiesRefuse() {
        SourceHarness(temporary.newFolder()).use { h ->
            val spec = DataSpec(Uri.parse("https://example.com/a"))
            assertSame(spec, h.resolver.resolveDataSpec(spec))
            for (uri in listOf(
                "neutrodyne://evil/1",
                "neutrodyne://episode/0",
                "neutrodyne://episode/01",
                "neutrodyne://episode/1?x=y",
                "neutrodyne://episode/a",
            )) {
                assertThrows(NoMediaException::class.java) { h.resolver.resolveDataSpec(DataSpec(Uri.parse(uri))) }
            }
        }
    }

    @Test fun localWinsEvenForYouTubeAndRemainsPinnedAfterRemoval() {
        SourceHarness(temporary.newFolder()).use { h ->
            h.index.putAll(listOf(media(type = SourceType.YOUTUBE_CHANNEL)))
            h.local.files[1] = "file:///download/episode.mp3"
            val local = h.resolve(position = 7, length = 9)
            assertEquals("file:///download/episode.mp3", local.uri.toString())
            assertNull(local.key)
            assertEquals(7, local.position)
            assertEquals(9, local.length)
            assertEquals(StreamKind.LOCAL, h.resolver.streamKind(1))
            h.local.files.clear()
            assertEquals(local.uri, h.resolve().uri)
            h.resolver.unpin(1)
            assertEquals(
                ResolveResult.Unsupported,
                assertThrows(YouTubeResolveException::class.java) { h.resolve() }.result,
            )
        }
    }

    @Test fun remotePinSurvivesDownloadAndFeedEditUntilUnpin() {
        SourceHarness(temporary.newFolder()).use { h ->
            h.index.putAll(listOf(media()))
            val first = h.resolve()
            h.local.files[1] = "content://downloads/1"
            h.index.putAll(listOf(media(url = "https://other.example/new")))
            assertEquals(first.uri, h.resolve().uri)
            assertEquals(first.key, h.resolve().key)
            assertEquals(StreamKind.STREAM, h.resolver.streamKind(1))
            h.resolver.unpin(1)
            assertEquals("content://downloads/1", h.resolve().uri.toString())
        }
    }

    @Test fun newRemotePinResetsBytesLengthAndRedirectMetadata() {
        SourceHarness(temporary.newFolder()).use { h ->
            h.index.putAll(listOf(media()))
            val key = checkNotNull(h.resolve().key)
            h.owner.cache().write(key, "old bytes")
            val mutations =
                androidx.media3.datasource.cache
                    .ContentMetadataMutations()
            androidx.media3.datasource.cache.ContentMetadataMutations
                .setContentLength(mutations, 99)
            androidx.media3.datasource.cache.ContentMetadataMutations.setRedirectedUri(
                mutations,
                Uri.parse("https://stale.example/old"),
            )
            h.owner.cache().applyContentMetadataMutations(key, mutations)
            assertTrue(
                h.owner
                    .cache()
                    .getCachedSpans(key)
                    .isNotEmpty(),
            )
            h.resolver.unpin(1)
            h.resolve()
            assertTrue(
                h.owner
                    .cache()
                    .getCachedSpans(key)
                    .isEmpty(),
            )
            assertEquals(
                -1,
                androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                    h.owner.cache().getContentMetadata(key),
                ),
            )
            assertNull(
                androidx.media3.datasource.cache.ContentMetadata.getRedirectedUri(
                    h.owner.cache().getContentMetadata(key),
                ),
            )
        }
    }

    @Test fun missingUnsupportedSourcesAndUnsafeLocalUrisRefuse() {
        SourceHarness(temporary.newFolder()).use { h ->
            val empty = EpisodeSourceIndex { null }
            val resolver = EpisodeResolver(empty, LocalMediaIndex { null }, h.owner)
            assertThrows(EpisodeGoneException::class.java) { resolver.resolveDataSpec(DataSpec(episodeUri(99))) }
            for (source in listOf(media(url = null), media(url = "file:///untrusted/feed"))) {
                h.index.putAll(listOf(source))
                assertThrows(NoMediaException::class.java) { h.resolve() }
            }
            for (type in listOf(SourceType.YOUTUBE_CHANNEL, SourceType.YOUTUBE_PLAYLIST)) {
                h.index.putAll(listOf(media(type = type)))
                assertEquals(
                    ResolveResult.Unsupported,
                    assertThrows(YouTubeResolveException::class.java) { h.resolve() }.result,
                )
            }
            h.local.files[1] = "https://not-local.example/1"
            assertThrows(NoMediaException::class.java) { h.resolve() }
        }
    }

    @Test fun unpinWhileLookupIsPendingDoesNotLeaveAnActiveResource() {
        SourceHarness(temporary.newFolder()).use { h ->
            val entered = CountDownLatch(1)
            val finish = CompletableDeferred<Unit>()
            val index =
                EpisodeSourceIndex {
                    entered.countDown()
                    finish.await()
                    media(it)
                }
            val resolver = EpisodeResolver(index, h.local, h.owner)
            val failure = AtomicReference<Throwable>()
            val loader =
                Thread {
                    try {
                        resolver.resolveDataSpec(DataSpec(episodeUri(1)))
                    } catch (e: Throwable) {
                        failure.set(e)
                    }
                }.apply { start() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            resolver.unpin(1)
            finish.complete(Unit)
            // Hang detector: cache index initialization can exceed 2 s under build contention.
            loader.join(10_000)
            assertFalse(loader.isAlive)
            assertTrue(failure.get() is InterruptedIOException)
            assertNull(resolver.streamKind(1))
            val key = EnclosureFingerprint.cacheKey(1, media().enclosureUrl!!, null)
            h.owner.cache().write(key, "abandoned")
            kotlinx.coroutines.runBlocking { h.owner.clearStreamingCache() }
            assertEquals(0L, h.owner.cache().cacheSpace)
        }
    }

    @Test fun cacheKeyMatchesRawUrlAndLengthFingerprint() {
        assertEquals("9a13f8366316b90c", EnclosureFingerprint.of("https://example.com/one", null))
        assertNotEquals(EnclosureFingerprint.of("url?token=one", 100), EnclosureFingerprint.of("url?token=two", 100))
        assertNotEquals(EnclosureFingerprint.of("url", 100), EnclosureFingerprint.of("url", 101))
    }

    @Test fun fallbackAwaitsOpenBeforeDereferencingProviderAndCachesItsResult() {
        val entered = CountDownLatch(1)
        val open = CompletableDeferred<Unit>()
        val lookup = FakePlaybackMediaLookup().apply { items.value = mapOf(1L to media()) }
        var providers = 0
        val index =
            EpisodeSourceIndex(
                {
                    entered.countDown()
                    open.await()
                },
                Provider<PlaybackMediaLookup> {
                    providers++
                    lookup
                },
                Dispatchers.Default,
            )
        val result = AtomicReference<Any>()
        val loader = Thread { result.set(index.getOrLoad(1)) }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertEquals(0, providers)
        assertTrue(lookup.reads.isEmpty())
        assertNull(result.get())
        open.complete(Unit)
        loader.join(2_000)
        assertFalse(loader.isAlive)
        assertEquals(media(), result.get())
        index.getOrLoad(1)
        assertEquals(1, providers)
        assertEquals(listOf(1L), lookup.reads)
        index.remove(1)
        assertNull(index[1])
    }

    @Test fun interruptedFallbackSurfacesAsInterruptedIoAndDoesNotPin() {
        val entered = CountDownLatch(1)
        val index =
            EpisodeSourceIndex {
                entered.countDown()
                delay(Long.MAX_VALUE)
                null
            }
        val failure = AtomicReference<Throwable>()
        val loader =
            Thread {
                try {
                    index.getOrLoad(1)
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        loader.interrupt()
        loader.join(2_000)
        assertFalse(loader.isAlive)
        assertTrue(failure.get() is InterruptedIOException)
        assertNull(index[1])
    }
}
