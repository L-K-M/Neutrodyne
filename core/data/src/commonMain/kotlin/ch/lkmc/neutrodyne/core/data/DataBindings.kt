// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.data.ingest.IngestionEventBus
import ch.lkmc.neutrodyne.core.domain.IngestionEvents
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import okio.FileSystem

/** `:core:data`'s platform-agnostic bindings (03 fetch pipeline). */
@BindingContainer
@ContributesTo(AppScope::class)
object DataBindings {
    // A container exposes the port across modules while the emitting bus stays internal.
    @Provides
    internal fun ingestionEvents(impl: IngestionEventBus): IngestionEvents = impl

    /** The real file system for feed temp files; tests substitute fakes. */
    @Provides
    fun fileSystem(): FileSystem = FileSystem.SYSTEM
}
