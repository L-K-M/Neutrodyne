// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.core.model.update.DesktopAssetSelection
import ch.lkmc.neutrodyne.core.model.update.UpdateManifest
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import dev.zacsweers.metro.Inject

@Inject
internal class DesktopAssetSelector : UpdateAssetSelector {
    override fun select(
        manifest: UpdateManifest,
        build: BuildInfo.Desktop,
        runtime: UpdateRuntimeTarget.Desktop,
    ): DesktopAssetSelection {
        if (build.installKind == InstallKind.DEV) return DesktopAssetSelection.DevelopmentBuild

        val arch = if (build.os == DesktopOs.WINDOWS) DesktopArch.X64 else build.arch
        val kinds =
            when (build.installKind) {
                InstallKind.MSI -> listOf(InstallKind.MSI, InstallKind.ZIP)
                InstallKind.ZIP -> listOf(InstallKind.ZIP, InstallKind.MSI)
                InstallKind.DMG -> listOf(InstallKind.DMG, InstallKind.MAC_ZIP)
                InstallKind.MAC_ZIP -> listOf(InstallKind.MAC_ZIP, InstallKind.DMG)
                InstallKind.DEB -> listOf(InstallKind.DEB, InstallKind.TAR_GZ)
                InstallKind.RPM -> listOf(InstallKind.RPM, InstallKind.TAR_GZ)
                InstallKind.TAR_GZ -> listOf(InstallKind.TAR_GZ)
                InstallKind.DEV -> emptyList()
            }
        val candidates = manifest.desktop.filter { it.os == build.os && it.arch == arch }
        val asset =
            kinds.firstNotNullOfOrNull { kind -> candidates.find { it.kind == kind } }
                ?: return DesktopAssetSelection.Missing
        val minimum = asset.minOs ?: return DesktopAssetSelection.Selected(asset)
        val current = numericOsVersion(runtime.osVersion)
        val required = numericOsVersion(minimum)
        if (current == null || required == null || compareOsVersions(current, required) < 0) {
            return DesktopAssetSelection.RequiresNewerOs(build.os, minimum)
        }

        return DesktopAssetSelection.Selected(asset)
    }
}

internal fun numericOsVersion(value: String): List<Long>? {
    if (!Regex("[0-9]+(?:\\.[0-9]+)*").matches(value)) return null
    return value.split('.').map { it.toLongOrNull() ?: return null }
}

private fun compareOsVersions(
    left: List<Long>,
    right: List<Long>,
): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val compared = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
        if (compared != 0) return compared
    }
    return 0
}
