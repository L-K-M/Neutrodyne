// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Path
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The ndmedia ABI over its own build (11 ndmedia interface, MD0 W4). Runs in
 * `test` but skips on a host without `buildNdmedia` output; the desktop matrix
 * builds ndmedia first, so the null-backend checks below are CI evidence on
 * all four targets.
 */
class NdOutputTest {
    @Test
    fun nullBackendPlaysWrittenFramesAndReportsItsShape() {
        val directory = ndmediaDir() ?: run {
            assumeTrue(false) { "no ndmedia build for this host" }
            return
        }
        NdOutput.open(directory, "null", RATE, CHANNELS, RING_MS).use { out ->
            val describe = out.describe()
            assertTrue("\"rate\":$RATE" in describe, describe)
            assertTrue("\"backend\":\"null\"" in describe, describe)

            // 100 ms of a sine — inside the 200 ms ring — drained through the
            // nonblocking write.
            Arena.ofConfined().use { arena ->
                val frames = RATE / 10
                val pcm = arena.allocate(frames.toLong() * CHANNELS * 4)
                for (i in 0 until frames) {
                    val s = sin(i * 0.05).toFloat() * 0.25f
                    pcm.set(ValueLayout.JAVA_FLOAT, i * 8L, s)
                    pcm.set(ValueLayout.JAVA_FLOAT, i * 8L + 4, s)
                }
                val accepted = out.write(pcm, frames)
                assertEquals(frames, accepted)
            }

            out.start()
            val deadline = System.nanoTime() + PLAY_WAIT_NS
            while (out.framesPlayed < RATE / 10 && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            out.stop()
            assertTrue(
                out.framesPlayed >= RATE / 10,
                "framesPlayed=${out.framesPlayed} never reached ${RATE / 10}",
            )
            // The counter is readable; underruns are allowed on a loaded runner.
            assertTrue(out.underrunFrames >= 0)
        }
    }

    @Test
    fun clearDropsQueuedFrames() {
        val directory = ndmediaDir() ?: run {
            assumeTrue(false) { "no ndmedia build for this host" }
            return
        }
        NdOutput.open(directory, "null", RATE, CHANNELS, RING_MS).use { out ->
            Arena.ofConfined().use { arena ->
                val pcm = arena.allocate(RATE / 10L * CHANNELS * 4)
                assertEquals(RATE / 10, out.write(pcm, RATE / 10))
            }
            assertEquals(RATE / 10, out.clear())
            assertEquals(RATE * RING_MS / 1_000, out.freeFrames())
        }
    }

    @Test
    fun unknownBackendIsRejected() {
        val directory = ndmediaDir() ?: run {
            assumeTrue(false) { "no ndmedia build for this host" }
            return
        }
        try {
            NdOutput.open(directory, "definitely-not-a-backend", RATE, CHANNELS, RING_MS).close()
            throw AssertionError("an unknown backend must not open")
        } catch (expected: NdOutputException) {
            // ND_E_BACKEND
        }
    }

    private fun ndmediaDir(): Path? {
        val override = System.getProperty("neutrodyne.ndmediaDir")
        if (override != null) return Path.of(override)
        val root = System.getProperty(ROOT_DIR) ?: return null
        val base = Path.of(root).resolve("playback/native/build/ndmedia")
        val target =
            when {
                isWindows() -> "windows-x64"
                isMac() -> "macos-arm64"
                else -> if (isArm()) "linux-arm64" else "linux-x64"
            }
        val dir = base.resolve(target)
        return dir.takeIf { it.toFile().isDirectory }
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")

    private fun isMac() = System.getProperty("os.name") == "Mac OS X"

    private fun isArm() = System.getProperty("os.arch") in setOf("aarch64", "arm64")

    private companion object {
        const val ROOT_DIR = "neutrodyne.rootDir"
        const val RATE = 48_000
        const val CHANNELS = 2
        const val RING_MS = 200
        const val PLAY_WAIT_NS = 5_000_000_000L
    }
}
