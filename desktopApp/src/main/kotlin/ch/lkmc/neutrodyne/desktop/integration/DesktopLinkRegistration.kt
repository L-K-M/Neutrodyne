// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.integration

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import ch.lkmc.neutrodyne.desktop.links.UrlSchemeRegistrar
import ch.lkmc.neutrodyne.desktop.system.JnaWindowsRegistry
import ch.lkmc.neutrodyne.desktop.system.LinuxMenuEntryInstaller
import ch.lkmc.neutrodyne.desktop.system.LinuxMenuEntryState
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The OS half of Settings › Desktop's LINKS rows (11 Links and files from the OS, 08's
 * wireframe) behind one seam, so `DesktopIntegrationImpl` stays free of per-OS detail:
 * Windows registers the URL schemes in `HKCU` at every start and can take over the four
 * podcast schemes; a Linux tar.gz writes the per-user desktop entry (whose `MimeType`
 * carries the schemes); every other install kind registers at install time and has no
 * row at all.
 */
interface DesktopLinkRegistration {
    /** Whether the LINKS rows exist on this install kind. */
    val applicable: Boolean

    /** The OS state: do we handle the four podcast schemes now? */
    fun handled(): Boolean

    /** "Open podcast links with Neutrodyne" — the explicit takeover. */
    fun register(): Boolean

    /** Whether the "Add to applications menu" row exists (Linux tar.gz only). */
    val canAddToApplicationsMenu: Boolean

    /** Writes the per-user entry and icons (11's tar.gz row); `false` on failure. */
    fun addToMenu(): Boolean
}

internal object NoLinkRegistration : DesktopLinkRegistration {
    override val applicable: Boolean = false
    override val canAddToApplicationsMenu: Boolean = false

    override fun handled(): Boolean = false

    override fun register(): Boolean = false

    override fun addToMenu(): Boolean = false
}

/** Windows: the HKCU URL-scheme registration of 11's run-time row. */
internal class WindowsLinkRegistration(
    private val registrar: UrlSchemeRegistrar,
) : DesktopLinkRegistration {
    override val applicable: Boolean = true
    override val canAddToApplicationsMenu: Boolean = false

    override fun handled(): Boolean = registrar.ownsPodcastSchemes()

    override fun register(): Boolean {
        registrar.takeOverPodcastSchemes()
        return handled()
    }

    override fun addToMenu(): Boolean = false
}

/** Linux tar.gz: the per-user desktop entry carries both rows' schemes (11's table). */
internal class LinuxTarGzLinkRegistration(
    private val installer: LinuxMenuEntryInstaller,
) : DesktopLinkRegistration {
    override val applicable: Boolean = true
    override val canAddToApplicationsMenu: Boolean = true

    override fun handled(): Boolean = installer.status() == LinuxMenuEntryState.INSTALLED

    override fun register(): Boolean = addToMenu()

    override fun addToMenu(): Boolean = installer.install()
}

@dev.zacsweers.metro.ContributesTo(AppScope::class)
@dev.zacsweers.metro.BindingContainer
object DesktopLinkBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun desktopLinkRegistration(
        buildInfo: BuildInfo,
        menuInstaller: LinuxMenuEntryInstaller,
    ): DesktopLinkRegistration {
        val launcher = System.getProperty(JPACKAGE_APP_PATH)
        return when (val desktop = buildInfo.desktop) {
            is BuildInfo.Desktop ->
                when (desktop.os) {
                    DesktopOs.WINDOWS -> WindowsLinkRegistration(UrlSchemeRegistrar(JnaWindowsRegistry, launcher))

                    DesktopOs.LINUX ->
                        if (desktop.installKind == InstallKind.TAR_GZ) {
                            LinuxTarGzLinkRegistration(menuInstaller)
                        } else {
                            NoLinkRegistration // DEB and RPM install the entry
                        }

                    DesktopOs.MACOS -> NoLinkRegistration // CFBundleURLTypes at install time
                }

            null -> NoLinkRegistration
        }
    }

    private const val JPACKAGE_APP_PATH = "jpackage.app-path"
}
