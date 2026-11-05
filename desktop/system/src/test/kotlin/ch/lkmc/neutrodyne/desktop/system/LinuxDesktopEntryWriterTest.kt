// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The Exec/TryExec escaping of 11's desktop-entry paragraph — the exact rules the menu entry
 * and the autostart entry share: always-quoted Exec, the four inner characters
 * backslash-escaped, `%%` in Exec only, then the string escape doubling every `\`.
 */
class LinuxDesktopEntryWriterTest {
    @Test
    fun `a plain path is quoted and gains the verbatim argument`() {
        assertThat(LinuxDesktopEntryWriter.execValue("/opt/neutrodyne/bin/Neutrodyne", listOf("%U")))
            .isEqualTo("\"/opt/neutrodyne/bin/Neutrodyne\" %U")
        assertThat(LinuxDesktopEntryWriter.execValue("/opt/neutrodyne/bin/Neutrodyne", listOf("--background")))
            .isEqualTo("\"/opt/neutrodyne/bin/Neutrodyne\" --background")
    }

    @Test
    fun `spaces and parentheses inside the quotes pass through`() {
        val launcher = "/home/alex/apps/My Apps/Neutrodyne (2)/bin/Neutrodyne"
        assertThat(LinuxDesktopEntryWriter.execValue(launcher, listOf("%U")))
            .isEqualTo("\"/home/alex/apps/My Apps/Neutrodyne (2)/bin/Neutrodyne\" %U")
    }

    @Test
    fun `the four quote-escape characters are doubled by the string escape`() {
        // " -> \" -> \\" ; ` -> \` -> \\` ; $ -> \$ -> \\$ ; \ -> \\ -> \\\\
        assertThat(LinuxDesktopEntryWriter.execValue("/opt/we\"ird", emptyList()))
            .isEqualTo("\"/opt/we\\\\\"ird\"")
        assertThat(LinuxDesktopEntryWriter.execValue("/opt/tick`tock", emptyList()))
            .isEqualTo("\"/opt/tick\\\\`tock\"")
        assertThat(LinuxDesktopEntryWriter.execValue("/opt/do${'$'}lar", emptyList()))
            .isEqualTo("\"/opt/do\\\\${'$'}lar\"")
        assertThat(LinuxDesktopEntryWriter.execValue("/opt/back\\slash", emptyList()))
            .isEqualTo("\"/opt/back\\\\\\\\slash\"")
    }

    @Test
    fun `a literal percent is doubled in Exec only`() {
        assertThat(LinuxDesktopEntryWriter.execValue("/opt/per%cent", listOf("%U")))
            .isEqualTo("\"/opt/per%%cent\" %U")
        assertThat(LinuxDesktopEntryWriter.tryExecValue("/opt/per%cent"))
            .isEqualTo("/opt/per%cent")
    }

    @Test
    fun `TryExec takes only the string escape`() {
        assertThat(LinuxDesktopEntryWriter.tryExecValue("/opt/back\\slash"))
            .isEqualTo("/opt/back\\\\slash")
        assertThat(LinuxDesktopEntryWriter.tryExecValue("/home/alex/My Apps/bin/Neutrodyne"))
            .isEqualTo("/home/alex/My Apps/bin/Neutrodyne")
    }

    @Test
    fun `a path with an equals sign or a control character is refused`() {
        assertThat(LinuxDesktopEntryWriter.executablePathAllowed("/opt/a=b")).isFalse()
        assertThat(LinuxDesktopEntryWriter.executablePathAllowed("/opt/line\nbreak")).isFalse()
        assertThat(LinuxDesktopEntryWriter.executablePathAllowed("/opt/tab\tpath")).isFalse()
        assertThat(LinuxDesktopEntryWriter.executablePathAllowed("/opt/del\u007F")).isFalse()
        assertThat(LinuxDesktopEntryWriter.executablePathAllowed("")).isFalse()
        assertFailsWith<IllegalArgumentException> {
            LinuxDesktopEntryWriter.execValue("/opt/a=b", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            LinuxDesktopEntryWriter.tryExecValue("/opt/a=b")
        }
    }

    @Test
    fun `the autostart entry carries the background flag and the GNOME key`() {
        val entry = LinuxDesktopEntryWriter.autostartEntry("/home/alex/apps/Neutrodyne/bin/Neutrodyne")
        assertThat(entry).contains("[Desktop Entry]")
        assertThat(entry).contains("Type=Application")
        assertThat(entry).contains("Exec=\"/home/alex/apps/Neutrodyne/bin/Neutrodyne\" --background")
        assertThat(entry).contains("TryExec=/home/alex/apps/Neutrodyne/bin/Neutrodyne")
        assertThat(entry).contains("X-GNOME-Autostart-enabled=true")
        assertThat(entry).doesNotContain("MimeType")
    }
}
