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

    @Test
    fun packagedImageReadsNativesFromTheMergedResources() {
        // The packaging pipeline stages buildFfmpeg and buildNdmedia into
        // native/ffmpeg and native/ndmedia under the image's resources dir (11
        // Resources layout); the engine paths are those directories.
        val properties = listOf("neutrodyne.rootDir", "compose.application.resources.dir")
        val saved = properties.associateWith(System::getProperty)
        try {
            System.clearProperty("neutrodyne.rootDir")
            System.setProperty("compose.application.resources.dir", "/image/resources")
            val paths = DesktopEnginePaths.current()
            assertEquals(Path.of("/image/resources/native/ffmpeg"), paths.ffmpeg)
            assertEquals(Path.of("/image/resources/native/ndmedia"), paths.ndmedia)
        } finally {
            for ((name, value) in saved) {
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
    }
}
