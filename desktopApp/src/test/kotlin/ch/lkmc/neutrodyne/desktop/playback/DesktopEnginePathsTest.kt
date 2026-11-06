// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.playback

import org.junit.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class DesktopEnginePathsTest {
    @Test
    fun gradleRunUsesBuiltLibrariesEvenWhenComposeSetsResources() {
        val properties = listOf("neutrodyne.rootDir", "compose.application.resources.dir", "os.name")
        val saved = properties.associateWith(System::getProperty)
        try {
            System.setProperty("neutrodyne.rootDir", "/checkout")
            System.setProperty("compose.application.resources.dir", "/compose/resources")
            System.setProperty("os.name", "Mac OS X")
            val paths = DesktopEnginePaths.current()
            assertEquals(Path.of("/checkout/playback/native/build/ffmpeg/macos-arm64"), paths.ffmpeg)
            assertEquals(Path.of("/checkout/playback/native/build/ndmedia/macos-arm64"), paths.ndmedia)
        } finally {
            for ((name, value) in saved) {
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
    }
}
