// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback

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

/** W8 replaces this temporary Android binding. Resolving it never touches a database. */
@SingleIn(AppScope::class)
@Inject
internal class AndroidPlaybackStub : UnavailablePlaybackController()

/** Public for Metro aggregation. Each shell contributes exactly one binding per playback port. */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidPlaybackBindings {
    @Provides internal fun controller(stub: AndroidPlaybackStub): PlaybackController = stub

    @Provides internal fun state(stub: AndroidPlaybackStub): PlaybackStateSource = stub

    @Provides internal fun maintenance(stub: AndroidPlaybackStub): PlaybackMaintenance = stub

    @Provides fun localMediaIndex(): LocalMediaIndex = LocalMediaIndex { null }
}
