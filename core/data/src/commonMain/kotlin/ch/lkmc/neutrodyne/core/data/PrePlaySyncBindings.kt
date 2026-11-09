// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.PrePlaySync
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The MS3 pull-before-play default (10 pull before play): without a sync graph nothing ever
 * pulls, so `:core:data`'s standalone graphs bind [PrePlaySync.None]. The app composition roots
 * replace this container with `PrePlaySyncImpl`.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object PrePlaySyncBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun prePlaySync(): PrePlaySync = PrePlaySync.None
}
