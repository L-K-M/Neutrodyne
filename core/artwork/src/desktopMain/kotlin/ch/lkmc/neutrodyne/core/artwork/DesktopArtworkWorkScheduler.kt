// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** MD1 supplies the desktop lane and codec; in-app Coil remains available before then. */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopArtworkWorkScheduler : ArtworkWorkScheduler {
    override suspend fun enqueue() = Unit
}
