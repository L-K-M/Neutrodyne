// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.playback.api.NoPlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/** Public for cross-module Metro aggregation; M4 replaces this pre-playback binding. */
@ContributesTo(AppScope::class)
@BindingContainer
object PlaybackBindings {
    @Provides
    fun playbackController(): PlaybackController = NoPlaybackController
}
