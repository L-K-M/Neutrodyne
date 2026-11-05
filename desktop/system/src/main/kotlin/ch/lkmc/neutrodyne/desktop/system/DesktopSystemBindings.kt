// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.model.BuildInfo
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.nio.file.Path

/** The `:desktop:system` bindings `DesktopAppGraph` aggregates (11 DesktopAppGraph). */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopSystemBindings {
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
}
