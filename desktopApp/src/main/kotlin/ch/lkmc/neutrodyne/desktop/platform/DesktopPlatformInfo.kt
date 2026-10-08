// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.platform

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import java.util.Locale

/**
 * [PlatformInfo] from the JVM's system properties and the default locale (01 Networking baseline):
 * `kind = DESKTOP`, `androidSdkInt = null`, the region from the locale, and the User-Agent
 * segment shown in [userAgentPlatform] — for example `Windows 11; x64`, `macOS 15.1; arm64`,
 * `Linux; x64`.
 */
internal class DesktopPlatformInfo(
    private val osName: String = System.getProperty("os.name"),
    private val osVersion: String = System.getProperty("os.version"),
    private val osArch: String = System.getProperty("os.arch"),
) : PlatformInfo {
    override val kind: PlatformKind = PlatformKind.DESKTOP

    override val userAgentPlatform: String =
        when (AppDirs.DesktopOs.current(osName)) {
            // os.name already reads "Windows 11"
            AppDirs.DesktopOs.WINDOWS -> "$osName; ${archLabel()}"

            AppDirs.DesktopOs.MACOS -> "macOS $osVersion; ${archLabel()}"

            AppDirs.DesktopOs.LINUX -> "Linux; ${archLabel()}"
        }

    override val androidSdkInt: Int? = null

    override val regionCode: String
        get() = Locale.getDefault().country.uppercase(Locale.ROOT)

    private fun archLabel(): String =
        when (osArch.lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> osArch
        }
}
