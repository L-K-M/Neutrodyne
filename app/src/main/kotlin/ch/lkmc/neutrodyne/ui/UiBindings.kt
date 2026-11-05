// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.ui

import android.app.Application
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.DesktopIntegration
import ch.lkmc.neutrodyne.feature.settings.LicencesSource
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/** The shared UI's Android-side bindings. */
@ContributesTo(AppScope::class)
@BindingContainer
object UiBindings {
    @Provides
    fun licencesSource(application: Application): LicencesSource? = AndroidLicencesSource(application)

    /** Desktop-only services (08 Settings › Desktop): the Android graph binds `null`. */
    @Provides
    fun desktopIntegration(): DesktopIntegration? = null
}
