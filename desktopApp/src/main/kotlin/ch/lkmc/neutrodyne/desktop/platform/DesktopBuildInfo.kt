// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.platform

import ch.lkmc.neutrodyne.core.model.InstallKind

/**
 * The packaging-pipeline facts `DatabaseOpener` needs before 11's full `BuildInfo` object lands
 * (M0b): `install-kind` is a one-line classpath resource the image copies carry; its absence —
 * `gradle run`, tests — means [InstallKind.DEV] (11 Release layout).
 */
internal object DesktopBuildInfo {
    private const val INSTALL_KIND_RESOURCE = "install-kind"

    val installKind: InstallKind by lazy {
        val wire =
            DesktopBuildInfo::class.java.classLoader
                .getResource(INSTALL_KIND_RESOURCE)
                ?.readText()
                ?.trim()
        InstallKind.entries.firstOrNull { it.wire == wire } ?: InstallKind.DEV
    }
}
