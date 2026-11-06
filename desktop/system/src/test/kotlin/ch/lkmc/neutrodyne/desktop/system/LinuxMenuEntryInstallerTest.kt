// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Add to applications menu" (11's tar.gz row) against temporary directories: the entry
 * and the per-user icons land strictly under the XDG data home, the entry's Icon points
 * at the copied 256×256 PNG, a moved archive re-runs into a refreshed (not duplicated)
 * entry, and a dev run without `jpackage.app-path` reports unsupported and writes
 * nothing. No process is started — these tests would notice: nothing here executes.
 * The runs are OS-wide on purpose: the desktop-entry string escape doubles the icon
 * path's backslashes, which only a Windows path exercises.
 */
class LinuxMenuEntryInstallerTest {
    private val root = Files.createTempDirectory("menu-entry")

    /** The desktop-entry string escape over an icon path: only `\` is rewritten. */
    private fun escapedIcon(icon: Path): String = icon.toString().replace("\\", "\\\\")

    private fun installer(
        launcher: String?,
        icons: Path?,
        dataHome: Path = Files.createTempDirectory(root, "home"),
    ) = dataHome to
        LinuxMenuEntryInstaller(
            dataHome = dataHome,
            launcher = { launcher },
            iconsSource = { icons },
        )

    private fun packagedIcons(vararg sizes: Int): Path {
        val root = Files.createTempDirectory(this.root, "source").resolve("desktop-integration")
        for (size in sizes) {
            val png =
                root
                    .resolve("hicolor")
                    .resolve("${size}x$size")
                    .resolve("apps")
                    .resolve("neutrodyne.png")
            Files.createDirectories(png.parent)
            Files.write(png, ByteArray(size))
        }
        return root
    }

    @Test
    fun installWritesTheEntryAndThePerUserIcons() {
        val icons = packagedIcons(128, 256, 512)
        val (home, installer) = installer("/opt/Neutrodyne (2)/bin/Neutrodyne", icons)

        assertThat(installer.status()).isEqualTo(LinuxMenuEntryState.NOT_INSTALLED)
        assertThat(installer.install()).isTrue()

        val entry = home.resolve("applications").resolve(LinuxDesktopEntryWriter.ENTRY_FILE_NAME)
        assertThat(Files.isRegularFile(entry)).isTrue()
        val content = Files.readString(entry)
        assertThat(content).contains("Exec=\"/opt/Neutrodyne (2)/bin/Neutrodyne\" %U")
        assertThat(content)
            .contains("MimeType=${LinuxDesktopEntryWriter.MIME_TYPES}")

        val icon256 = home.resolve("icons/hicolor/256x256/apps/neutrodyne.png")
        assertThat(Files.isRegularFile(icon256)).isTrue()
        assertThat(content).contains("Icon=${escapedIcon(icon256)}")
        assertThat(Files.isRegularFile(home.resolve("icons/hicolor/128x128/apps/neutrodyne.png"))).isTrue()
        assertThat(Files.isRegularFile(home.resolve("icons/hicolor/512x512/apps/neutrodyne.png"))).isTrue()
        assertThat(installer.status()).isEqualTo(LinuxMenuEntryState.INSTALLED)
    }

    @Test
    fun theLargestIconIsUsedWhen256IsAbsent() {
        val icons = packagedIcons(48, 128)
        val (home, installer) = installer("/opt/n/bin/Neutrodyne", icons)

        assertThat(installer.install()).isTrue()

        val entry = home.resolve("applications").resolve(LinuxDesktopEntryWriter.ENTRY_FILE_NAME)
        assertThat(Files.readString(entry))
            .contains("Icon=${escapedIcon(home.resolve("icons/hicolor/128x128/apps/neutrodyne.png"))}")
    }

    @Test
    fun reRunningAfterTheArchiveMovedRefreshesWithoutDuplicating() {
        val icons = packagedIcons(256)
        val (home, installer) = installer("/home/alex/apps/a/bin/Neutrodyne", icons)
        installer.install()

        val moved = "/home/alex/apps/b/bin/Neutrodyne"
        val (_, after) = installer(moved, icons, home)
        assertThat(after.install()).isTrue()

        val entries =
            Files.list(home.resolve("applications")).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".desktop") }.count()
            }
        assertThat(entries).isEqualTo(1)
        val content = Files.readString(home.resolve("applications").resolve(LinuxDesktopEntryWriter.ENTRY_FILE_NAME))
        assertThat(content).contains("Exec=\"$moved\" %U")
        assertThat(content).contains("TryExec=$moved")
    }

    @Test
    fun aDevRunWithoutALauncherIsUnsupportedAndWritesNothing() {
        val icons = packagedIcons(256)
        val (home, installer) = installer(launcher = null, icons = icons)

        assertThat(installer.status()).isEqualTo(LinuxMenuEntryState.UNSUPPORTED)
        assertThat(installer.install()).isFalse()
        assertThat(Files.notExists(home.resolve("applications"))).isTrue()
        assertThat(Files.notExists(home.resolve("icons"))).isTrue()
    }

    @Test
    fun aLauncherPathTheSpecForbidsFailsWithoutWriting() {
        val icons = packagedIcons(256)
        val (home, installer) = installer("/opt/e=ql/bin/Neutrodyne", icons)

        assertThat(installer.install()).isFalse()
        assertThat(Files.notExists(home.resolve("applications"))).isTrue()
    }

    @Test
    fun missingPackagedIconsFailWithoutAnEntry() {
        val (home, installer) = installer("/opt/n/bin/Neutrodyne", icons = null)

        assertThat(installer.install()).isFalse()
        assertThat(Files.notExists(home.resolve("applications"))).isTrue()
    }
}
