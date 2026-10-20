// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.testing.FakeYouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.AudioQuality
import ch.lkmc.neutrodyne.youtube.api.ResolvePurpose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreResolverTest {
    @Test fun remainingBoundsUnknownDurationAndMissingNextDoNotAdmitWork() =
        runTest {
            val streams = FakeYouTubeStreamResolver()
            val consumer = PreResolver(streams, backgroundScope)
            val next = PreResolveTarget(2, "abcdefghijk")
            val window = PreResolveWindow(next, setOf(next))
            val excluded =
                listOf(
                    0L to null,
                    0L to 0L,
                    59_999L to 120_000L,
                    120_001L to 120_000L,
                    -1L to 60_000L,
                )
            for ((position, duration) in excluded) {
                consumer.onTick(PlaybackTick(1, 1, position, duration), window, AudioPref())
            }
            consumer.onTick(PlaybackTick(1, 1, 60_000, 120_000), PreResolveWindow(null, setOf(next)), AudioPref())
            assertTrue(streams.requests.isEmpty())
            consumer.onTick(PlaybackTick(1, 1, 60_000, 120_000), window, AudioPref())
            assertEquals(1, streams.requests.size)
            val atEnd = PreResolveTarget(3, "lmnopqrstuv")
            consumer.onTick(
                PlaybackTick(1, 1, 120_000, 120_000),
                PreResolveWindow(atEnd, setOf(next, atEnd)),
                AudioPref(),
            )
            assertEquals(2, streams.requests.size)
        }

    @Test fun seeksAndReordersRetainAttemptsButNewOccurrencesCanResolve() =
        runTest {
            val streams = FakeYouTubeStreamResolver()
            val consumer = PreResolver(streams, backgroundScope)
            val first = PreResolveTarget(2, "abcdefghijk")
            val second = PreResolveTarget(3, "lmnopqrstuv")
            val tick = PlaybackTick(1, 1, 60_000, 120_000)
            val installed = setOf(first, second)
            consumer.onTick(tick, PreResolveWindow(first, installed), AudioPref())
            consumer.onTick(tick.copy(positionMs = 0), PreResolveWindow(first, installed), AudioPref())
            consumer.onTick(tick, PreResolveWindow(second, installed), AudioPref())
            consumer.onTick(tick, PreResolveWindow(first, installed), AudioPref())
            assertEquals(listOf(first.videoId, second.videoId), streams.requests.map { it.first })
            val reentered = PreResolveTarget(2, first.videoId)
            consumer.onTick(tick, PreResolveWindow(reentered, setOf(reentered)), AudioPref())
            consumer.onTick(tick.copy(occurrence = 2), PreResolveWindow(reentered, setOf(reentered)), AudioPref())
            assertEquals(4, streams.requests.size)
        }

    @Test fun preferenceSnapshotAndDefaultPlaybackPurposeReachTheExistingPort() =
        runTest {
            val streams = FakeYouTubeStreamResolver()
            val consumer = PreResolver(streams, backgroundScope)
            val next = PreResolveTarget(2, "abcdefghijk")
            val pref = AudioPref(AudioQuality.DATA_SAVER, true, preferredLanguage = "de")
            consumer.onTick(PlaybackTick(1, 1, 0, 60_000), PreResolveWindow(next, setOf(next)), pref)
            assertEquals(listOf(next.videoId to pref), streams.requests)
            assertEquals(listOf(ResolvePurpose.PLAYBACK), streams.purposes)
        }

    @Test fun detachPreventsNewCallbacksWhileAnAdmittedAppWaiterMayFinish() =
        runTest {
            val streams = FakeYouTubeStreamResolver()
            val finish = CompletableDeferred<Unit>()
            var completed = false
            streams.resolve = { _, _, _ ->
                finish.await()
                completed = true
                ch.lkmc.neutrodyne.youtube.api.ResolveResult.Unsupported
            }
            val consumer = PreResolver(streams, backgroundScope)
            val next = PreResolveTarget(2, "abcdefghijk")
            val tick = PlaybackTick(1, 1, 0, 60_000)
            consumer.onTick(tick, PreResolveWindow(next, setOf(next)), AudioPref())
            assertEquals(1, streams.requests.size)
            assertFalse(completed)
            consumer.detach()
            consumer.onTick(tick.copy(occurrence = 2), PreResolveWindow(next, setOf(next)), AudioPref())
            finish.complete(Unit)
            runCurrent()
            assertTrue(completed)
            assertEquals(1, streams.requests.size)
        }
}
