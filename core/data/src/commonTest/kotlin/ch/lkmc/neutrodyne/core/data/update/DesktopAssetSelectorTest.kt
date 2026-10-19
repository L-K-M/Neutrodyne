// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ch.lkmc.neutrodyne.core.model.DesktopArch as Arch
import ch.lkmc.neutrodyne.core.model.DesktopOs as Os
import ch.lkmc.neutrodyne.core.model.InstallKind as Kind
import ch.lkmc.neutrodyne.core.model.update.DesktopAssetSelection as Selection

class DesktopAssetSelectorTest {
    private fun select(
        os: Os,
        preferred: Kind,
        available: List<Kind>,
        arch: Arch = Arch.X64,
        running: String = "99.0",
        minimum: String? = null,
        version: String = "1.0.0",
    ): Selection {
        val wireArch = if (os == Os.WINDOWS) "x64" else arch.wire
        val entries = available.joinToString(",") { desktopJson(os.wire, wireArch, it.wire, minimum, version) }
        return DesktopAssetSelector().select(
            manifest(version, ",\"desktop\":[$entries]"),
            BuildInfo.Desktop(os, arch, preferred, "bundled"),
            UpdateRuntimeTarget.Desktop(running),
        )
    }

    private fun selectedKind(selection: Selection): Kind = assertIs<Selection.Selected>(selection).asset.kind

    @Test fun everyShippedKindPrefersItselfAndUsesOnlyItsDocumentedFallback() {
        val cases =
            listOf(
                Triple(Os.WINDOWS, Kind.MSI, Kind.ZIP),
                Triple(Os.WINDOWS, Kind.ZIP, Kind.MSI),
                Triple(Os.MACOS, Kind.DMG, Kind.MAC_ZIP),
                Triple(Os.MACOS, Kind.MAC_ZIP, Kind.DMG),
                Triple(Os.LINUX, Kind.DEB, Kind.TAR_GZ),
                Triple(Os.LINUX, Kind.RPM, Kind.TAR_GZ),
            )
        cases.forEach { (os, preferred, fallback) ->
            assertEquals(preferred, selectedKind(select(os, preferred, listOf(fallback, preferred))))
            assertEquals(fallback, selectedKind(select(os, preferred, listOf(fallback))))
        }
        assertEquals(Kind.TAR_GZ, selectedKind(select(Os.LINUX, Kind.TAR_GZ, listOf(Kind.TAR_GZ))))
        listOf(Kind.DEB, Kind.RPM, Kind.TAR_GZ).forEach { preferred ->
            val incompatible = listOf(Kind.DEB, Kind.RPM).filter { it != preferred }
            assertEquals(Selection.Missing, select(Os.LINUX, preferred, incompatible))
        }
    }

    @Test fun windowsArmUsesX64WhileLinuxAndMacUseTheirNativeArchitecture() {
        val cases = listOf(Os.WINDOWS to Kind.MSI, Os.LINUX to Kind.DEB, Os.MACOS to Kind.DMG)
        cases.forEach { (os, kind) ->
            val asset = assertIs<Selection.Selected>(select(os, kind, listOf(kind), Arch.ARM64)).asset
            assertEquals(if (os == Os.WINDOWS) Arch.X64 else Arch.ARM64, asset.arch)
        }
        val input = manifest(extra = ",\"desktop\":[${desktopJson("linux", "x64", "deb")}]")
        listOf(Os.LINUX, Os.MACOS).forEach { os ->
            val identity = BuildInfo.Desktop(os, Arch.ARM64, Kind.DEB, "bundled")
            assertEquals(
                Selection.Missing,
                DesktopAssetSelector().select(input, identity, UpdateRuntimeTarget.Desktop("6.1")),
            )
        }
    }

    @Test fun missingAndDevelopmentReasonsAreDistinct() {
        assertEquals(Selection.Missing, select(Os.LINUX, Kind.DEB, emptyList()))
        assertEquals(Selection.DevelopmentBuild, select(Os.LINUX, Kind.DEV, listOf(Kind.DEB)))
    }

    @Test fun numericOsComparisonPadsComponentsAndAvoidsLexicographicOrder() {
        val versions = listOf("10.0" to "9.99", "13.10" to "13.9", "13.0.0" to "13", "10.0.22631" to "10.0")
        versions.forEach { (running, minimum) ->
            assertIs<Selection.Selected>(
                select(Os.MACOS, Kind.DMG, listOf(Kind.DMG), running = running, minimum = minimum),
            )
        }
        listOf("12.9", "unknown", "13.0-beta", "9223372036854775808.0").forEach { running ->
            val selected = select(Os.MACOS, Kind.DMG, listOf(Kind.DMG), running = running, minimum = "13.0")
            assertEquals(Selection.RequiresNewerOs(Os.MACOS, "13.0"), selected)
        }
        val unsupported = select(Os.WINDOWS, Kind.MSI, listOf(Kind.ZIP, Kind.MSI), running = "10.0", minimum = "11.0")
        assertEquals(Selection.RequiresNewerOs(Os.WINDOWS, "11.0"), unsupported)
    }

    @Test fun macTesterZipMovesToDmgAtOnePointZero() {
        val tester = select(Os.MACOS, Kind.MAC_ZIP, listOf(Kind.MAC_ZIP), Arch.ARM64, version = "0.9.0")
        assertEquals(Kind.MAC_ZIP, selectedKind(tester))
        assertEquals(Kind.DMG, selectedKind(select(Os.MACOS, Kind.MAC_ZIP, listOf(Kind.DMG), Arch.ARM64)))
    }
}
