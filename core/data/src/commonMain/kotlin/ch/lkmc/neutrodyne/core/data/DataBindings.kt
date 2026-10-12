// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import okio.FileSystem

/** `:core:data`'s platform-agnostic bindings (03 fetch pipeline). */
@BindingContainer
@ContributesTo(AppScope::class)
object DataBindings {
    /** The real file system for feed temp files; tests substitute fakes. */
    @Provides
    fun fileSystem(): FileSystem = FileSystem.SYSTEM
}
