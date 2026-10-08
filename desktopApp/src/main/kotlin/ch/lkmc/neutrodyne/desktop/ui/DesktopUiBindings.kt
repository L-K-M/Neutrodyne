// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.ui

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.feature.settings.LicencesSource
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/** The shared UI's desktop-side bindings (the twin of `:app`'s `UiBindings`). */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopUiBindings {
    @Provides
    fun licencesSource(): LicencesSource? = DesktopLicencesSource()
}
