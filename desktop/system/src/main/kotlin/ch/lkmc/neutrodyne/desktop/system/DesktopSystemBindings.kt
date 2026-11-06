// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.LinuxDesktopPortal
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopOs
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.nio.file.Path

/** The `:desktop:system` bindings `DesktopAppGraph` aggregates (11 DesktopAppGraph). */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopSystemBindings {
    /** Where the Compose launcher points at the merged resources of a packaged image. */
    private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

    /**
     * The OS's start-at-login registrar (11 Start at login). `dirs.config`'s parent is the
     * Linux config home whose `autostart/` holds the entry; Windows and macOS ignore it.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun loginItemRegistrar(
        buildInfo: BuildInfo,
        dirs: AppDirs,
    ): LoginItemRegistrar =
        LoginItemRegistrars.create(
            os = buildInfo.desktop?.os,
            launcher = System.getProperty(LoginItemRegistrars.JPACKAGE_APP_PATH),
            configHome = dirs.config.parent,
        )

    /**
     * The tar.gz "Add to applications menu" installer (11 Links and files from the OS):
     * the XDG data home from [AppDirs], the launcher from `jpackage.app-path` and the
     * packaged icons from the image's merged resources directory.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun linuxMenuEntryInstaller(dirs: AppDirs): LinuxMenuEntryInstaller =
        LinuxMenuEntryInstaller(
            dataHome = dirs.data.parent,
            launcher = { System.getProperty(LoginItemRegistrars.JPACKAGE_APP_PATH) },
            iconsSource = {
                System.getProperty(RESOURCES_DIR_PROPERTY)?.let(java.nio.file.Path::of)
            },
        )

    /**
     * The D-Bus portal of 11's Runner contract: bound only on Linux — a nullable type is
     * its own binding, so every other OS (and dev run of another OS) resolves `null` and
     * the callers use their documented fallbacks.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun linuxDesktopPortal(buildInfo: BuildInfo): LinuxDesktopPortal? =
        when (buildInfo.desktop?.os) {
            DesktopOs.LINUX -> DbusDesktopPortal()
            else -> null
        }
}
