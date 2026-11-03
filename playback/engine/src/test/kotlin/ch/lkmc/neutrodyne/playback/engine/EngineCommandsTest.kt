// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Native null-device proofs with a controllable blocked AVIO read. */
class EngineCommandsTest {
    @Test
    fun pauseDuringOpenDoesNotStartTheDevice() {
        val source = blockedSource()
        engine(source).use { engine ->
            engine.play(media(), 1f, false)
            assertTrue(source.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            engine.pause()
            source.release.countDown()
            awaitOutput(engine)
            Thread.sleep(250)
            assertEquals(0L, engine.underrunFrames, "a paused device must not request frames")
        }
    }

    @Test
    fun closeAbortsAReadBlockedDuringOpen() {
        val source = blockedSource()
        val engine = engine(source)
        engine.play(media(), 1f, false)
        assertTrue(source.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val finished = CountDownLatch(1)
        val closer =
            Thread {
                try {
                    engine.close()
                } finally {
                    finished.countDown()
                }
            }
        closer.start()
        try {
            assertTrue(finished.await(1_500, TimeUnit.MILLISECONDS), "close did not abort the blocked read")
            assertTrue(source.aborted.get(), "the source never received abort")
        } finally {
            source.release.countDown()
            closer.join(6_000)
        }
    }

    private fun engine(source: ByteSource): FfAudioEngine =
        FfAudioEngine(nativeDirectory("ffmpeg"), nativeDirectory("ndmedia"), "null", 44_100) { source }

    private fun nativeDirectory(kind: String): Path {
        val root = Path.of(requireNotNull(System.getProperty("neutrodyne.rootDir")))
        val target =
            when {
                System.getProperty("os.name").startsWith("Windows") -> "windows-x64"
                System.getProperty("os.name") == "Mac OS X" -> "macos-arm64"
                System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "linux-arm64"
                else -> "linux-x64"
            }
        return root.resolve("playback/native/build/$kind/$target")
    }

    private fun media(): Path =
        Path.of(requireNotNull(System.getProperty("neutrodyne.moduleDir")), "src/test/resources/media/wav_2s.wav")

    private fun blockedSource() = BlockedSource(Files.readAllBytes(media()))

    private fun awaitOutput(engine: FfAudioEngine) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (engine.outputDescription == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(engine.outputDescription != null, "output was never opened")
    }

    private class BlockedSource(
        bytes: ByteArray,
    ) : ByteSource {
        private val delegate = MemoryByteSource(bytes)
        private val first = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        override val length: Long? get() = delegate.length

        override fun read(
            position: Long,
            dst: MemorySegment,
            maxBytes: Int,
        ): Int {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "test read was never released" }
            }
            return if (aborted.get()) -1 else delegate.read(position, dst, maxBytes)
        }

        override fun abort() {
            aborted.set(true)
            release.countDown()
        }

        override fun close() = delegate.close()
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
