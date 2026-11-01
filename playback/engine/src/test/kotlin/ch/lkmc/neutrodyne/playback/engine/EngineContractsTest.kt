// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files

class EngineWindowDiffTest {
    private val first = EngineItem(episodeId = 1L, cacheKey = "ep:1:fp", localHint = true)
    private val second = EngineItem(episodeId = 2L, cacheKey = "ep:2:fp", localHint = false)
    private val third = EngineItem(episodeId = 3L, cacheKey = "ep:3:fp", localHint = false)
    private val window = listOf(first, second, third)

    @Test
    fun `insert places an item at its index`() {
        val diff = EngineWindowDiff(inserted = listOf(PlacedItem(1, EngineItem(9L, "ep:9:fp", false))))

        val applied = diff.applyTo(window, currentEpisodeId = 1L)

        assertEquals(listOf(1L, 9L, 2L, 3L), applied.map { it.episodeId })
    }

    @Test
    fun `remove drops only the named items`() {
        val diff = EngineWindowDiff(removed = setOf(2L))

        val applied = diff.applyTo(window, currentEpisodeId = 1L)

        assertEquals(listOf(1L, 3L), applied.map { it.episodeId })
    }

    @Test
    fun `replace keeps the window position and the new identity`() {
        val diff = EngineWindowDiff(replaced = listOf(second.copy(cacheKey = "ep:2:fp2")))

        val applied = diff.applyTo(window, currentEpisodeId = 1L)

        assertEquals(3, applied.size)
        assertEquals("ep:2:fp2", applied[1].cacheKey)
    }

    @Test
    fun `move relocates a non-current item`() {
        val diff = EngineWindowDiff(moved = listOf(MovedItem(3L, toIndex = 1)))

        val applied = diff.applyTo(window, currentEpisodeId = 1L)

        assertEquals(listOf(1L, 3L, 2L), applied.map { it.episodeId })
    }

    @Test
    fun `operations apply in a fixed order regardless of diff order`() {
        val diff =
            EngineWindowDiff(
                replaced = listOf(third.copy(cacheKey = "ep:3:fp2")),
                removed = setOf(2L),
                inserted = listOf(PlacedItem(0, EngineItem(8L, "ep:8:fp", false))),
                moved = listOf(MovedItem(3L, toIndex = 1)),
            )

        val applied = diff.applyTo(window, currentEpisodeId = 1L)

        assertEquals(listOf(1L, 3L, 8L), applied.map { it.episodeId })
        assertEquals("ep:3:fp2", applied[1].cacheKey)
    }

    @Test
    fun `a diff touching the current item is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineWindowDiff(removed = setOf(1L)).applyTo(window, currentEpisodeId = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineWindowDiff(replaced = listOf(first.copy(cacheKey = "ep:1:fp2")))
                .applyTo(window, currentEpisodeId = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineWindowDiff(moved = listOf(MovedItem(1L, toIndex = 2)))
                .applyTo(window, currentEpisodeId = 1L)
        }
    }

    @Test
    fun `an out-of-range index is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineWindowDiff(inserted = listOf(PlacedItem(9, EngineItem(9L, "ep:9:fp", false))))
                .applyTo(window, currentEpisodeId = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineWindowDiff(moved = listOf(MovedItem(3L, toIndex = 3)))
                .applyTo(window, currentEpisodeId = 1L)
        }
    }

    @Test
    fun `an unknown item is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineWindowDiff(removed = setOf(99L)).applyTo(window, currentEpisodeId = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineWindowDiff(moved = listOf(MovedItem(99L, toIndex = 0)))
                .applyTo(window, currentEpisodeId = 1L)
        }
    }

    @Test
    fun `an empty diff is the identity`() {
        assertEquals(window, EngineWindowDiff().applyTo(window, currentEpisodeId = 2L))
    }
}

class ByteSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `memory source reads positionally and reports the end`() {
        val source = MemoryByteSource(byteArrayOf(1, 2, 3, 4, 5))
        Arena.ofConfined().use { arena ->
            val dst = arena.allocate(8)

            assertEquals(2, source.read(position = 1L, dst = dst, maxBytes = 2))
            assertEquals(2L, dst.get(ValueLayout.JAVA_BYTE, 0L))
            assertEquals(3L, dst.get(ValueLayout.JAVA_BYTE, 1L))

            assertEquals(3, source.read(position = 2L, dst = dst, maxBytes = 8))
            assertEquals(0, source.read(position = 5L, dst = dst, maxBytes = 8))
            assertEquals(5L, source.length)
        }
    }

    @Test
    fun `file source reads the whole file and the length matches`() {
        val path = folder.newFile("episode.mp3").toPath()
        Files.write(path, ByteArray(300) { it.toByte() })
        FileChannelByteSource(path).use { source ->
            Arena.ofConfined().use { arena ->
                val dst = arena.allocate(300)
                assertEquals(300, source.read(position = 0L, dst = dst, maxBytes = 300))
                assertEquals(300L, source.length)
                assertEquals(0, source.read(position = 300L, dst = dst, maxBytes = 300))
                assertEquals(7L, dst.get(ValueLayout.JAVA_BYTE, 7L))
            }
        }
    }

    @Test
    fun `a read past a short read fills from the next position`() {
        val path = folder.newFile("short.mp3").toPath()
        Files.write(path, ByteArray(64) { 9 })
        FileChannelByteSource(path).use { source ->
            Arena.ofConfined().use { arena ->
                val dst = arena.allocate(64)
                assertEquals(64, source.read(position = 16L, dst = dst, maxBytes = 64))
                assertEquals(0, source.read(position = 64L, dst = dst, maxBytes = 64))
            }
        }
    }

    @Test
    fun `a missing file fails at open, not at read`() {
        val missing = folder.root.toPath().resolve("nope.mp3")

        assertThrows(java.nio.file.NoSuchFileException::class.java) {
            FileChannelByteSource(missing)
        }
    }

    @Test
    fun `a read after close reports the abort instead of throwing`() {
        val path = folder.newFile("closed.mp3").toPath()
        Files.write(path, byteArrayOf(1, 2, 3))
        val source = FileChannelByteSource(path)
        source.close()
        Arena.ofConfined().use { arena ->
            assertEquals(-1, source.read(position = 0L, dst = arena.allocate(3), maxBytes = 3))
        }
    }
}

class AudioFrameTest {
    @Test
    fun `frames of the same audio are equal`() {
        val samples = shortArrayOf(1, 2, 3, 4)

        assertEquals(AudioFrame(samples, 2, 44100, 0L), AudioFrame(samples.copyOf(), 2, 44100, 0L))
        assertFalse(AudioFrame(samples, 2, 44100, 0L) == AudioFrame(samples, 2, 48000, 0L))
    }
}

class AudioCodecTest {
    @Test
    fun `every codec of the minimal build maps from its FFmpeg name`() {
        assertEquals(AudioCodec.AAC, AudioCodec.fromName("AAC"))
        assertEquals(AudioCodec.MP3, AudioCodec.fromName("MP3"))
        assertEquals(AudioCodec.OPUS, AudioCodec.fromName("OPUS"))
        assertEquals(AudioCodec.VORBIS, AudioCodec.fromName("VORBIS"))
        assertEquals(AudioCodec.FLAC, AudioCodec.fromName("FLAC"))
        assertEquals(AudioCodec.ALAC, AudioCodec.fromName("ALAC"))
        assertEquals(AudioCodec.PCM_S16LE, AudioCodec.fromName("PCM_S16LE"))
    }

    @Test
    fun `a codec the build does not enable maps to null`() {
        assertNull(AudioCodec.fromName("AC3"))
        assertNull(AudioCodec.fromName("h264"))
        assertEquals(9, AudioCodec.entries.size)
    }
}
