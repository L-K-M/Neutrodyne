// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
public object AndroidLocalMediaBindings {
    @Provides public fun localMediaIndex(): LocalMediaIndex = LocalMediaIndex { null }
}
