// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.identity

import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import java.nio.file.Path

/**
 * 11 Start-up step 4 off Windows: the process AppUserModelID comes from the real
 * [BuildInfo] — packaged builds get `ch.lkmc.neutrodyne`, a dev build gets
 * `ch.lkmc.neutrodyne.debug` — and every failure degrades without throwing (the
 * media flyout and toasts may fall back, the app must not).
 */
class WindowsAppIdentityTest {
    private val ndmedia = Path.of("ndmedia")

    private fun buildInfo(
        os: DesktopOs? = DesktopOs.WINDOWS,
        installKind: InstallKind = InstallKind.MSI,
    ): BuildInfo =
        BuildInfo(
            versionName = "1.0.0",
            versionCode = 1,
            debug = installKind == InstallKind.DEV,
            platform = BuildInfo.Platform.DESKTOP,
            repoUrl = "https://github.com/L-K-M/Neutrodyne",
            updateManifestUrl = "https://example.com/update.json",
            engineManifestUrl = "https://example.com/engine.json",
            youTubeEngineBundled = true,
            desktop =
                os?.let {
                    BuildInfo.Desktop(
                        os = it,
                        arch = DesktopArch.X64,
                        installKind = installKind,
                        runtime = "Temurin 25",
                    )
                },
            shippedLocales = persistentListOf("en"),
            podcastIndexKey = "",
            podcastIndexSecret = "",
        )

    @Test
    fun `release builds select the release AppUserModelID`() {
        assertThat(WindowsAppIdentity.appUserModelId(debug = false)).isEqualTo("ch.lkmc.neutrodyne")
    }

    @Test
    fun `debug builds select the debug AppUserModelID`() {
        assertThat(WindowsAppIdentity.appUserModelId(debug = true)).isEqualTo("ch.lkmc.neutrodyne.debug")
    }

    @Test
    fun `windows applies the release id`() {
        val calls = mutableListOf<String>()
        val outcome =
            WindowsAppIdentity.applyProcessIdentity(buildInfo(), ndmedia) {
                ProcessAumidSetter { aumid ->
                    calls += aumid
                    0
                }
            }

        assertThat(outcome).isEqualTo(WindowsAppIdentity.ProcessIdentityOutcome.APPLIED)
        assertThat(calls).containsExactly("ch.lkmc.neutrodyne")
    }

    @Test
    fun `a dev build applies the debug id`() {
        val calls = mutableListOf<String>()
        val outcome =
            WindowsAppIdentity.applyProcessIdentity(buildInfo(installKind = InstallKind.DEV), ndmedia) {
                ProcessAumidSetter { aumid ->
                    calls += aumid
                    0
                }
            }

        assertThat(outcome).isEqualTo(WindowsAppIdentity.ProcessIdentityOutcome.APPLIED)
        assertThat(calls).containsExactly("ch.lkmc.neutrodyne.debug")
    }

    @Test
    fun `non-windows skips without touching the native layer`() {
        var opened = false
        val outcome =
            WindowsAppIdentity.applyProcessIdentity(buildInfo(os = DesktopOs.LINUX), ndmedia) {
                opened = true
                ProcessAumidSetter { 0 }
            }

        assertThat(outcome).isEqualTo(WindowsAppIdentity.ProcessIdentityOutcome.SKIPPED)
        assertThat(opened).isFalse()
    }

    @Test
    fun `missing native support degrades without throwing`() {
        val outcome = WindowsAppIdentity.applyProcessIdentity(buildInfo(), ndmedia) { null }

        assertThat(outcome).isEqualTo(WindowsAppIdentity.ProcessIdentityOutcome.NATIVE_UNAVAILABLE)
    }

    @Test
    fun `a native failure is reported not thrown`() {
        val outcome =
            WindowsAppIdentity.applyProcessIdentity(buildInfo(), ndmedia) {
                ProcessAumidSetter { -1 }
            }

        assertThat(outcome).isEqualTo(WindowsAppIdentity.ProcessIdentityOutcome.FAILED)
    }

    @Test
    fun `a setter throwing is reported not thrown`() {
        val outcome =
            WindowsAppIdentity.applyProcessIdentity(buildInfo(), ndmedia) {
                ProcessAumidSetter { error("no ndmedia here") }
            }

        assertThat(outcome).isEqualTo(WindowsAppIdentity.ProcessIdentityOutcome.FAILED)
    }
}
