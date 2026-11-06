// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * The per-user menu entry of 11's tar.gz row: the package template's values with this
 * extraction's launcher and the absolute per-user icon — the same Exec/TryExec quoting
 * as the autostart entry, `MimeType` and `StartupWMClass` pinned, the icon path only
 * string-escaped. Quoting cases mirror `LinuxDesktopEntryWriterTest`'s autostart rows.
 */
class LinuxMenuEntryWriterTest {
    @Test
    fun `the menu entry matches the package template`() {
        val entry =
            LinuxDesktopEntryWriter.menuEntry(
                "/opt/neutrodyne/bin/Neutrodyne",
                "/home/alex/.local/share/icons/hicolor/256x256/apps/neutrodyne.png",
            )

        assertThat(entry)
            .contains("Exec=\"/opt/neutrodyne/bin/Neutrodyne\" %U")
        assertThat(entry).contains("TryExec=/opt/neutrodyne/bin/Neutrodyne")
        assertThat(entry).contains("Icon=/home/alex/.local/share/icons/hicolor/256x256/apps/neutrodyne.png")
        assertThat(entry).contains("MimeType=${LinuxDesktopEntryWriter.MIME_TYPES}")
        assertThat(entry).contains("StartupWMClass=${LinuxDesktopEntryWriter.STARTUP_WM_CLASS}")
        assertThat(entry).contains("Categories=AudioVideo;Audio;Player;")
        assertThat(entry).doesNotContain("X-GNOME-Autostart-enabled")
    }

    @Test
    fun `extraction paths get the same quoting as the autostart entry`() {
        val launcher = "/home/alex/apps/My Apps/Neutrodyne (2)/bin/Neutrodyne"
        val entry =
            LinuxDesktopEntryWriter.menuEntry(
                launcher,
                "/home/alex/.local/share/icons/hicolor/256x256/apps/neutrodyne.png",
            )

        assertThat(entry).contains("Exec=\"$launcher\" %U")
        assertThat(entry).contains("TryExec=$launcher")
    }

    @Test
    fun `an icon path with a backslash is string-escaped only`() {
        val entry =
            LinuxDesktopEntryWriter.menuEntry(
                "/opt/neutrodyne/bin/Neutrodyne",
                "/home/alex/dir\\name/neutrodyne.png",
            )

        assertThat(entry).contains("Icon=/home/alex/dir\\\\name/neutrodyne.png")
    }

    @Test
    fun `a forbidden launcher path is refused`() {
        assertFailsWith<IllegalArgumentException> {
            LinuxDesktopEntryWriter.menuEntry("/opt/e=ql/bin/Neutrodyne", "/icon.png")
        }
    }
}
