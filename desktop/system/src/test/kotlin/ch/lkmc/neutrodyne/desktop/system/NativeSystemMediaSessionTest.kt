// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import org.junit.Assume.assumeTrue
import java.nio.file.Path
import org.junit.Test
import kotlin.test.assertNull

/** API registration proof only. Physical keys and a signed Mac app remain hardware checks. */
class NativeSystemMediaSessionTest {
    @Test(timeout = 60_000)
    fun registersPublishesAndRevokesTheRealOsSession() {
        val os = System.getProperty("os.name")
        assumeTrue("Linux uses the private-bus MPRIS suite", os.startsWith("Windows") || os == "Mac OS X")
        val target = if (os.startsWith("Windows")) "windows-x64" else "macos-arm64"
        val root = Path.of(requireNotNull(System.getProperty("neutrodyne.rootDir")))
        val directory = root.resolve("playback/native/build/ndmedia/$target")
        repeat(3) {
            NativeSystemMediaSession.open(directory).use { session ->
                session.publish(testNowPlaying(), 10_000, 30_000, listOf(0.5f, 1f, 3f))
                Thread.sleep(100)
                assertNull(session.failure.value)
                session.publish(null, 10_000, 30_000, listOf(1f))
            }
        }
    }
}
