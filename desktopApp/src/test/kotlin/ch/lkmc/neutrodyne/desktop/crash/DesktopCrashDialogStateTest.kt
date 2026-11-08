// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.testing.FakeCrashReporter
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.Locale

class DesktopCrashDialogStateTest {
    @Test
    fun `offers newest first and marks handled`() {
        val dirs = tempAppDirs("nd-crash-dialog").also { Files.createDirectories(it.state) }
        val clock = TestClock()
        val old = dirs.state.resolve("crash-20260101-010101.txt")
        val neu = dirs.state.resolve("crash-20260102-010101.txt")
        Files.writeString(old, "old")
        Files.writeString(neu, "new")
        Files.setLastModifiedTime(
            old,
            java.nio.file.attribute.FileTime
                .from(Instant.ofEpochMilli(clock.now() - 1000)),
        )
        Files.setLastModifiedTime(
            neu,
            java.nio.file.attribute.FileTime
                .from(Instant.ofEpochMilli(clock.now())),
        )

        val state = DesktopCrashDialogState(dirs, crashDialogBuildInfo(), clock, FakeCrashReporter(true))
        val pending = state.pendingCrashes()
        assertThat(
            pending.map {
                it.fileName
            },
        ).containsExactly("crash-20260102-010101.txt", "crash-20260101-010101.txt").inOrder()

        state.dismiss(pending[0])
        assertThat(Files.exists(dirs.state.resolve("crash-20260102-010101.dismissed"))).isTrue()
        assertThat(Files.exists(neu)).isFalse()
    }

    @Test
    fun `DEV and empty mailbox never ask`() {
        val dirs = tempAppDirs("nd-crash-dialog2").also { Files.createDirectories(it.state) }
        val clock = TestClock()
        Files.writeString(dirs.state.resolve("crash-20260101-010101.txt"), "x")

        // The gate is the reporter's own `isAvailable` (D62/PO-10): a real
        // DesktopCrashReporter derives it from the same BuildInfo the state scans with.
        val devInfo = crashDialogBuildInfo(installKind = InstallKind.DEV)
        val devReporter = DesktopCrashReporter(dirs, devInfo, clock, RecentLogBuffer())
        assertThat(devReporter.isAvailable).isFalse()
        assertThat(DesktopCrashDialogState(dirs, devInfo, clock, devReporter).pendingCrashes()).isEmpty()

        val noMailboxInfo = crashDialogBuildInfo(mailto = "")
        val noMailboxReporter = DesktopCrashReporter(dirs, noMailboxInfo, clock, RecentLogBuffer())
        assertThat(noMailboxReporter.isAvailable).isFalse()
        assertThat(DesktopCrashDialogState(dirs, noMailboxInfo, clock, noMailboxReporter).pendingCrashes()).isEmpty()

        assertThat(
            DesktopCrashDialogState(dirs, crashDialogBuildInfo(), clock, FakeCrashReporter(false)).pendingCrashes(),
        ).isEmpty()
    }

    @Test
    fun `mailto is built and bounded`() {
        val dirs = tempAppDirs("nd-crash-dialog3").also { Files.createDirectories(it.state) }
        val clock = TestClock()
        val f = dirs.state.resolve("crash-20260101-010101.txt")
        Files.writeString(f, "x".repeat(3000))
        var opened: String? = null
        val state =
            DesktopCrashDialogState(dirs, crashDialogBuildInfo(), clock, FakeCrashReporter(true), openMailto = {
                opened = it
                true
            })
        val pending = state.pendingCrashes()
        assertThat(state.sendByEmail(pending[0])).isTrue()
        assertThat(opened).isNotNull()
        assertThat(opened).startsWith("mailto:")
        assertThat(opened).contains("Neutrodyne")
        assertThat(opened).contains("truncated")
        assertThat(Files.exists(dirs.state.resolve("crash-20260101-010101.sent"))).isTrue()
    }

    @Test
    fun `sendByEmail keeps the offer when the sent rename fails`() {
        val dirs = tempAppDirs("nd-crash-dialog-rename").also { Files.createDirectories(it.state) }
        val clock = TestClock()
        val f = dirs.state.resolve("crash-20260101-010101.txt")
        Files.writeString(f, "x")
        // A directory at the target name makes Files.move fail without deleting the source.
        Files.createDirectories(dirs.state.resolve("crash-20260101-010101.sent"))
        var opened = 0
        val state =
            DesktopCrashDialogState(dirs, crashDialogBuildInfo(), clock, FakeCrashReporter(true), openMailto = {
                opened++
                true
            })
        val pending = state.pendingCrashes()
        assertThat(pending).hasSize(1)

        assertThat(state.sendByEmail(pending[0])).isFalse()
        assertThat(opened).isEqualTo(1)
        assertThat(Files.exists(f)).isTrue()
        // The file is still pending, so the dialog does not advance past it.
        assertThat(state.pendingCrashes().map { it.fileName }).containsExactly(f.fileName.toString())
    }

    @Test
    fun `an unreadable state directory does not crash the scan`() {
        val dirs = tempAppDirs("nd-crash-dialog-unreadable")
        // A regular file at the state path makes Files.list throw NotDirectoryException.
        Files.writeString(dirs.state, "not a directory")
        val state = DesktopCrashDialogState(dirs, crashDialogBuildInfo(), TestClock(), FakeCrashReporter(true))

        assertThat(state.pendingCrashes()).isEmpty()
    }
}

@OptIn(ExperimentalTestApi::class)
class DesktopCrashDialogUITest {
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        // The shared test-task config sets the JVM to de_DE; these assertions name English labels.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `show report invokes its action`() =
        runComposeUiTest {
            var shown = 0
            setContent {
                DesktopCrashDialog(onShowReport = { shown++ }, onSendEmail = {}, onDismiss = {})
            }
            onNodeWithText("Show report").performClick()
            waitForIdle()
            assertThat(shown).isEqualTo(1)
        }

    @Test
    fun `send by email invokes its action`() =
        runComposeUiTest {
            var sent = 0
            setContent {
                DesktopCrashDialog(onShowReport = {}, onSendEmail = { sent++ }, onDismiss = {})
            }
            onNodeWithText("Send by email").performClick()
            waitForIdle()
            assertThat(sent).isEqualTo(1)
        }

    @Test
    fun `dont send invokes its action`() =
        runComposeUiTest {
            var dismissed = 0
            setContent {
                DesktopCrashDialog(onShowReport = {}, onSendEmail = {}, onDismiss = { dismissed++ })
            }
            onNodeWithText("Don't send").performClick()
            waitForIdle()
            assertThat(dismissed).isEqualTo(1)
        }
}
