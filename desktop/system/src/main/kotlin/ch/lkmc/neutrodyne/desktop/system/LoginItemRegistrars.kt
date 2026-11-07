// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.LoginItemState
import java.nio.file.Path

/** The `ndmedia` library directory the shell loads from (11 ndmedia interface). */
data class NdmediaDirectory(
    val path: Path,
)

/**
 * Picks the OS's [LoginItemRegistrar] (11 Start at login). [launcher] is the running jpackage
 * launcher's path (`jpackage.app-path`); a dev run has none, so the registrar reports
 * UNSUPPORTED and the Settings row hides. On macOS the binding is [MacLoginItemRegistrar]
 * (`SMAppService.mainApp` through the native shim, with its translocation refusal); a
 * missing [ndmedia] library (a dev run without `buildNdmedia`) falls back to the
 * unsupported registrar.
 */
object LoginItemRegistrars {
    const val JPACKAGE_APP_PATH = "jpackage.app-path"
    private const val AUTOSTART_DIR = "autostart"
    private const val TAG = "LoginItem"

    fun create(
        os: DesktopOs?,
        launcher: String?,
        configHome: Path?,
        ndmedia: NdmediaDirectory? = null,
        registry: WindowsRegistry = JnaWindowsRegistry,
    ): LoginItemRegistrar =
        when (os) {
            DesktopOs.WINDOWS -> {
                WindowsRunKeyRegistrar(registry, launcher)
            }

            DesktopOs.LINUX -> {
                if (configHome == null) {
                    UnsupportedLoginItemRegistrar
                } else {
                    XdgAutostartRegistrar(configHome.resolve(AUTOSTART_DIR), launcher)
                }
            }

            DesktopOs.MACOS -> {
                val directory = ndmedia
                if (directory == null) {
                    UnsupportedLoginItemRegistrar
                } else {
                    runCatching { MacLoginItemRegistrar(directory.path, launcher) }
                        .onFailure { Log.w(TAG, it) { "SMAppService registrar unavailable" } }
                        .getOrDefault(UnsupportedLoginItemRegistrar)
                }
            }

            null -> {
                UnsupportedLoginItemRegistrar
            }
        }
}

/** No mechanism on this OS or in this build; Settings › Desktop hides the row. */
object UnsupportedLoginItemRegistrar : LoginItemRegistrar {
    override fun status(): LoginItemState = LoginItemState.UNSUPPORTED

    override fun register(): Unit = throw LoginItemException("start at login is not supported on this platform")

    override fun unregister() = Unit
}
