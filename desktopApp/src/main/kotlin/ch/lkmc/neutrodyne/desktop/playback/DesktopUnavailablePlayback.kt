// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.playback

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.UnavailablePlaybackController
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** MD1 replaces the desktop unavailable binding. Resolving it never touches a database. */
@SingleIn(AppScope::class)
@Inject
internal class DesktopUnavailablePlayback : UnavailablePlaybackController()

/** Public for Metro aggregation. Each shell contributes exactly one binding per playback port. */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopPlaybackBindings {
    @Provides internal fun controller(stub: DesktopUnavailablePlayback): PlaybackController = stub

    @Provides internal fun state(stub: DesktopUnavailablePlayback): PlaybackStateSource = stub

    @Provides internal fun maintenance(stub: DesktopUnavailablePlayback): PlaybackMaintenance = stub

    @Provides fun localMediaIndex(): LocalMediaIndex = LocalMediaIndex { null }
}
