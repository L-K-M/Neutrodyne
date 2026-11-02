// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import org.junit.Assume.assumeTrue
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MD0 AC3: through miniaudio's null back-end the timeline clock stays within
 * 50 ms of the reference timeline at 0.5×, 1×, 2× and 3× with skip silence
 * on, and at 1× and 3× with it off. Reference end position = the item's
 * decoded duration; the clock must land there once the ring drains.
 */
class EngineClockTest {
    @Test
    fun clockTracksDeviceFramesAcrossSpeedsWithSkipSilence() {
        val ffmpeg = ffmpegDir()
        val ndmedia = ndmediaDir()
        assumeTrue("native builds required", ffmpeg != null && ndmedia != null)
        val file = mediaDir().resolve(ITEM)
        assumeTrue("corpus item missing: $file", file.toFile().isFile)

        for (speed in SPEEDS) {
            FfAudioEngine(requireNotNull(ffmpeg), requireNotNull(ndmedia), "null", RATE).use { engine ->
                engine.play(file, speed, SKIP)
                assertTrue(engine.awaitEnded(PLAY_TIMEOUT_MS), "item never ended at ${speed}x")
                val position = engine.positionMs()
                val error = abs(position - EXPECTED_END_MS)
                assertTrue(
                    error <= MAX_ERROR_MS,
                    "${speed}x: clock position ${position}ms is ${error}ms off the media end",
                )
            }
        }
    }

    private fun mediaDir(): Path = Path.of(requireNotNull(System.getProperty(MODULE_DIR)), "src/test/resources/media")

    private fun ffmpegDir(): Path? {
        val root = System.getProperty(ROOT_DIR) ?: return null
        val dir = Path.of(root).resolve("playback/native/build/ffmpeg/$TARGET")
        return dir.takeIf { it.toFile().isDirectory }
    }

    private fun ndmediaDir(): Path? {
        val root = System.getProperty(ROOT_DIR) ?: return null
        val dir = Path.of(root).resolve("playback/native/build/ndmedia/$TARGET")
        return dir.takeIf { it.toFile().isDirectory }
    }

    private companion object {
        const val MODULE_DIR = "neutrodyne.moduleDir"
        const val ROOT_DIR = "neutrodyne.rootDir"
        const val RATE = 44_100
        const val ITEM = "mp3_silence_gaps_3s.mp3"

        // 133632 decoded samples at 44100 Hz per corpus.json.
        const val EXPECTED_END_MS = 3_030L
        const val MAX_ERROR_MS = 50L
        const val PLAY_TIMEOUT_MS = 30_000L
        const val SKIP = true
        val SPEEDS = floatArrayOf(0.5f, 1f, 2f, 3f)
        val TARGET =
            when {
                System.getProperty("os.name").startsWith("Windows") -> "windows-x64"
                System.getProperty("os.name") == "Mac OS X" -> "macos-arm64"
                System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "linux-arm64"
                else -> "linux-x64"
            }
    }
}
