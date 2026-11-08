// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class DesktopCrashDialogStateTest {
    private fun buildInfo(
        mailto: String = "neutrodyne@example.com",
        installKind: InstallKind = InstallKind.MSI,
    ): BuildInfo =
        BuildInfo(
            versionName = "1.0.0",
            versionCode = 1,
            debug = installKind == InstallKind.DEV,
            platform = BuildInfo.Platform.DESKTOP,
            repoUrl = "https://github.com/L-K-M/Neutrodyne",
            updateManifestUrl = "https://github.com/L-K-M/Neutrodyne/releases/latest/download/neutrodyne-update.json",
            engineManifestUrl = "https://example.com/engine.json",
            youTubeEngineBundled = true,
            apkAbi = null,
            desktop =
                BuildInfo.Desktop(
                    os = DesktopOs.LINUX,
                    arch = DesktopArch.X64,
                    installKind = installKind,
                    runtime = "Temurin 25",
                ),
            shippedLocales = persistentListOf("en", "de"),
            acraMailto = mailto,
            podcastIndexKey = "",
            podcastIndexSecret = "",
        )

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

        val state = DesktopCrashDialogState(dirs, buildInfo(), clock)
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
        assertThat(
            DesktopCrashDialogState(dirs, buildInfo(installKind = InstallKind.DEV), clock).pendingCrashes(),
        ).isEmpty()
        assertThat(DesktopCrashDialogState(dirs, buildInfo(mailto = ""), clock).pendingCrashes()).isEmpty()
    }

    @Test
    fun `mailto is built and bounded`() {
        val dirs = tempAppDirs("nd-crash-dialog3").also { Files.createDirectories(it.state) }
        val clock = TestClock()
        val f = dirs.state.resolve("crash-20260101-010101.txt")
        Files.writeString(f, "x".repeat(3000))
        var opened: String? = null
        val state =
            DesktopCrashDialogState(dirs, buildInfo(), clock, openMailto = {
                opened = it
                true
            })
        val pending = state.pendingCrashes()
        state.sendByEmail(pending[0])
        assertThat(opened).isNotNull()
        assertThat(opened).startsWith("mailto:")
        assertThat(opened).contains("Neutrodyne")
        assertThat(opened).contains("truncated")
        assertThat(Files.exists(dirs.state.resolve("crash-20260101-010101.sent"))).isTrue()
    }
}

@OptIn(ExperimentalTestApi::class)
class DesktopCrashDialogUITest {
    @Test
    fun dialogRenders() {
        runComposeUiTest {
            setContent {
                DesktopCrashDialog(
                    onShowReport = {},
                    onSendEmail = {},
                    onDismiss = {},
                )
            }
            waitForIdle()
        }
    }
}
