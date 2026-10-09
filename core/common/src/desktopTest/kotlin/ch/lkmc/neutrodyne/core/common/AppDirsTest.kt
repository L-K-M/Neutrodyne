// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `AppDirs.resolve` is OS/env/home-injected precisely so all three OS layouts are covered on a
 * Linux test host (11 AppDirs). Windows paths stay raw strings under the Linux test FS, so
 * assertions compare the separator-normalized string form.
 */
class AppDirsTest {
    private val home = Path.of("/home/tester")

    private fun norm(path: Path) = path.toString().replace('\\', '/')

    @Test
    fun `windows layout lives under LOCALAPPDATA`() {
        val dirs =
            AppDirs.resolve(
                AppDirs.DesktopOs.WINDOWS,
                env = mapOf("LOCALAPPDATA" to "C:\\Users\\T\\AppData\\Local"),
                home = home,
            )
        assertEquals("C:/Users/T/AppData/Local/Neutrodyne", norm(dirs.data))
        assertEquals(dirs.data, dirs.config)
        assertEquals("C:/Users/T/AppData/Local/Neutrodyne/Cache", norm(dirs.cache))
        assertEquals("C:/Users/T/AppData/Local/Neutrodyne/Logs", norm(dirs.state))
        assertEquals("C:/Users/T/AppData/Local/Neutrodyne/Logs/logs", norm(dirs.logs))
        assertEquals("C:/Users/T/AppData/Local/Neutrodyne/Downloads", norm(dirs.downloadsDefault))
    }

    @Test
    fun `windows never reads APPDATA`() {
        val dirs =
            AppDirs.resolve(
                AppDirs.DesktopOs.WINDOWS,
                env =
                    mapOf(
                        "APPDATA" to "C:\\Roaming",
                        "LOCALAPPDATA" to "C:\\Users\\T\\AppData\\Local",
                    ),
                home = home,
            )
        assertTrue(!norm(dirs.data).contains("Roaming"))
    }

    @Test
    fun `windows falls back to the known-folder lookup when LOCALAPPDATA is missing`() {
        val dirs =
            AppDirs.resolve(
                AppDirs.DesktopOs.WINDOWS,
                env = emptyMap(),
                home = home,
                windowsLocalAppData = { Path.of("D:\\Known\\Local") },
            )
        assertEquals("D:/Known/Local/Neutrodyne", norm(dirs.data))
    }

    @Test
    fun `windows falls back when LOCALAPPDATA is relative`() {
        val dirs =
            AppDirs.resolve(
                AppDirs.DesktopOs.WINDOWS,
                env = mapOf("LOCALAPPDATA" to "not\\absolute"),
                home = home,
                windowsLocalAppData = { Path.of("D:\\Known\\Local") },
            )
        assertEquals("D:/Known/Local/Neutrodyne", norm(dirs.data))
    }

    @Test
    fun `windows fails loudly when no local app data can be found`() {
        assertFailsWith<IllegalStateException> {
            AppDirs.resolve(
                AppDirs.DesktopOs.WINDOWS,
                env = emptyMap(),
                home = home,
                windowsLocalAppData = { null },
            )
        }
    }

    @Test
    fun `macos layout follows the Library conventions`() {
        val dirs = AppDirs.resolve(AppDirs.DesktopOs.MACOS, env = emptyMap(), home = home)
        assertEquals(
            "/home/tester/Library/Application Support/ch.lkmc.neutrodyne",
            norm(dirs.data),
        )
        assertEquals(dirs.data, dirs.config)
        assertEquals("/home/tester/Library/Caches/ch.lkmc.neutrodyne", norm(dirs.cache))
        assertEquals("/home/tester/Library/Logs/Neutrodyne", norm(dirs.state))
        assertEquals("/home/tester/Library/Logs/Neutrodyne/logs", norm(dirs.logs))
        assertEquals(
            "/home/tester/Library/Application Support/ch.lkmc.neutrodyne/Downloads",
            norm(dirs.downloadsDefault),
        )
    }

    @Test
    fun `linux uses absolute XDG overrides`() {
        val dirs =
            AppDirs.resolve(
                AppDirs.DesktopOs.LINUX,
                env =
                    mapOf(
                        "XDG_DATA_HOME" to "/xdg/data",
                        "XDG_CONFIG_HOME" to "/xdg/config",
                        "XDG_CACHE_HOME" to "/xdg/cache",
                        "XDG_STATE_HOME" to "/xdg/state",
                    ),
                home = home,
            )
        assertEquals("/xdg/data/neutrodyne", norm(dirs.data))
        assertEquals("/xdg/config/neutrodyne", norm(dirs.config))
        assertEquals("/xdg/cache/neutrodyne", norm(dirs.cache))
        assertEquals("/xdg/state/neutrodyne", norm(dirs.state))
        assertEquals("/xdg/state/neutrodyne/logs", norm(dirs.logs))
        assertEquals("/xdg/data/neutrodyne/Downloads", norm(dirs.downloadsDefault))
    }

    @Test
    fun `linux falls back to home defaults`() {
        val dirs = AppDirs.resolve(AppDirs.DesktopOs.LINUX, env = emptyMap(), home = home)
        assertEquals("/home/tester/.local/share/neutrodyne", norm(dirs.data))
        assertEquals("/home/tester/.config/neutrodyne", norm(dirs.config))
        assertEquals("/home/tester/.cache/neutrodyne", norm(dirs.cache))
        assertEquals("/home/tester/.local/state/neutrodyne", norm(dirs.state))
        assertEquals("/home/tester/.local/state/neutrodyne/logs", norm(dirs.logs))
    }

    @Test
    fun `linux ignores relative XDG values`() {
        val dirs =
            AppDirs.resolve(
                AppDirs.DesktopOs.LINUX,
                env = mapOf("XDG_DATA_HOME" to "relative/path", "XDG_CACHE_HOME" to "also/relative"),
                home = home,
            )
        assertEquals("/home/tester/.local/share/neutrodyne", norm(dirs.data))
        assertEquals("/home/tester/.cache/neutrodyne", norm(dirs.cache))
    }

    @Test
    fun `ensureCreated creates every directory and is idempotent`() {
        val root = Files.createTempDirectory("neutrodyne-test")
        val dirs = testDirs(root)
        dirs.ensureCreated()
        dirs.ensureCreated()

        for (dir in setOf(dirs.data, dirs.config, dirs.cache, dirs.state, dirs.logs)) {
            assertTrue(Files.isDirectory(dir), "$dir missing")
        }
    }

    @Test
    fun `ensureCreated makes every directory user-only on posix`() {
        val root = Files.createTempDirectory("neutrodyne-test")
        assumeTrue(
            "POSIX permissions require a POSIX file store",
            Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView::class.java),
        )
        val dirs = testDirs(root)
        dirs.ensureCreated()
        for (dir in setOf(dirs.data, dirs.cache, dirs.state, dirs.logs)) {
            assertTrue(Files.isDirectory(dir), "$dir missing")
            val perms = Files.getPosixFilePermissions(dir)
            assertEquals(
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwx------"),
                perms,
                "$dir permissions",
            )
        }
        // idempotent
        dirs.ensureCreated()
    }

    private fun testDirs(root: Path) =
        AppDirs(
            data = root.resolve("data"),
            config = root.resolve("data"),
            cache = root.resolve("cache"),
            state = root.resolve("state"),
            logs = root.resolve("state").resolve("logs"),
            downloadsDefault = root.resolve("data").resolve("Downloads"),
        )

    @Test
    fun `detect maps os name strings`() {
        assertEquals(AppDirs.DesktopOs.WINDOWS, AppDirs.DesktopOs.current("Windows 11"))
        assertEquals(AppDirs.DesktopOs.MACOS, AppDirs.DesktopOs.current("Mac OS X"))
        assertEquals(AppDirs.DesktopOs.LINUX, AppDirs.DesktopOs.current("Linux"))
        assertEquals(AppDirs.DesktopOs.LINUX, AppDirs.DesktopOs.current("FreeBSD"))
    }
}
