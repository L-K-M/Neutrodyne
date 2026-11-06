// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.model.AudioAlternate
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeLocalMediaIndex
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeStreamResolver
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.core.WindowEntry
import ch.lkmc.neutrodyne.playback.engine.ResolvedSource
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopEpisodeSourceResolverTest {
    private val local = FakeLocalMediaIndex()
    private val youtube = FakeYouTubeStreamResolver()
    private val resolver =
        DesktopEpisodeSourceResolver(
            local,
            FakeYouTubeCapabilitiesSource(),
            youtube,
            TestClock(),
        )

    @Test
    fun completedFileWinsWithoutYoutubeOrStreamingCache() =
        runTest {
            val file = Files.createTempFile("md1a2", ".mp3")
            try {
                local.files[1] = file.toUri().toString()
                val item =
                    resolver.project(
                        entry(media().copy(sourceType = SourceType.YOUTUBE_CHANNEL), local.files[1]),
                    )
                assertEquals(ResolvedSource.File(file), resolver.resolve(item, 0))
                assertEquals(StreamKind.LOCAL, resolver.streamKind(1))
                assertTrue(youtube.requests.isEmpty())
            } finally {
                Files.delete(file)
            }
        }

    @Test
    fun sourceSnapshotKeepsOriginalEnclosureAcrossFeedEdits() =
        runTest {
            val original = resolver.project(entry(media()))
            val changed = resolver.project(entry(media().copy(enclosureUrl = "https://media.example/new.mp3")))
            val pinned = resolver.resolve(original, 0) as ResolvedSource.Http
            assertEquals("https://media.example/1.mp3", pinned.url)
            assertEquals(original.cacheKey, pinned.cacheKey)
            assertTrue(original.cacheKey != changed.cacheKey)
            assertEquals(pinned, resolver.resolve(original, 1))
        }

    @Test
    fun videoUsesUsableAudioAlternateAndItsLengthFingerprint() =
        runTest {
            val media =
                media().copy(
                    isVideo = true,
                    audioAlternate = AudioAlternate("https://media.example/audio.m4a", "audio/mp4", 123),
                )
            val item = resolver.project(entry(media))
            assertEquals("https://media.example/audio.m4a", (resolver.resolve(item, 0) as ResolvedSource.Http).url)
            val changed = media.copy(audioAlternate = media.audioAlternate?.copy(length = 456))
            assertTrue(item.cacheKey != resolver.project(entry(changed)).cacheKey)
        }

    @Test
    fun deletedFileFallsBackToPinnedEnclosure() =
        runTest {
            val file = Files.createTempFile("md1a2-missing", ".mp3")
            val item = resolver.project(entry(media(), file.toUri().toString()))
            Files.delete(file)
            assertEquals("https://media.example/1.mp3", (resolver.resolve(item, 0) as ResolvedSource.Http).url)
            assertEquals(StreamKind.STREAM, resolver.streamKind(1))
        }

    @Test
    fun externalYoutubeIsRejectedBeforeCallingResolver() =
        runTest {
            val item =
                resolver.project(
                    entry(media().copy(sourceType = SourceType.YOUTUBE_CHANNEL, externalMediaId = "video")),
                )
            assertFailsWith<IOException> { resolver.resolve(item, 0) }
            assertTrue(youtube.requests.isEmpty())
        }

    @Test
    fun invalidAlternateFallsBackAndInvalidEnclosureFailsExplicitly() =
        runTest {
            val row =
                media().copy(
                    isVideo = true,
                    audioAlternate = AudioAlternate("file:///private", "audio/mpeg", null),
                )
            assertEquals(
                "https://media.example/1.mp3",
                (resolver.resolve(resolver.project(entry(row)), 0) as ResolvedSource.Http).url,
            )
            val invalid = resolver.project(entry(row.copy(enclosureUrl = "https://example.com:99999/invalid")))
            assertFailsWith<IOException> { resolver.resolve(invalid, 0) }
        }

    private fun entry(
        media: PlaybackMediaInfo,
        local: String? = null,
    ) = WindowEntry(QueueItem(1, QueueOrigin.CURRENT), media, local)

    private fun media(): PlaybackMediaInfo {
        val row = testEpisodeRow(1)
        return PlaybackMediaInfo(
            1,
            row.podcastId,
            row.title,
            row.podcastTitle,
            row.artwork,
            SourceType.RSS,
            row.availability,
            false,
            "https://media.example/1.mp3",
            1_000,
            null,
            120_000,
            null,
            null,
        )
    }
}
