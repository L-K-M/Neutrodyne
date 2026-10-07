// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * How the desktop build was installed (11 Release layout); `wire` is the value stored in the
 * desktop `BuildInfo` resource that the launcher reads (`launcher.buildinfo.json`, D91).
 */
enum class InstallKind(
    val wire: String,
) {
    MSI("msi"),
    ZIP("zip"),
    DMG("dmg"),
    MAC_ZIP("mac-zip"),
    DEB("deb"),
    RPM("rpm"),
    TAR_GZ("tar.gz"),
    DEV("dev"),
}
