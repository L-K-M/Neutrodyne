// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.os

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

/**
 * Band 250: start the OS media session wiring and the power policy after the database open
 * (their settings observations and the controller's runtime follow the gate) and before
 * band 300 restores playback — so a restored item publishes to the OS session at once.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class DesktopOsSurfacesInitializer(
    private val surfaces: DesktopOsSurfaces,
) : AppInitializer {
    override val order: Int = 250

    override suspend fun run() {
        surfaces.start()
    }
}
