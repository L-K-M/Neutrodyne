// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.discovery.DirectoryHttp
import ch.lkmc.neutrodyne.core.data.discovery.DirectorySettings
import ch.lkmc.neutrodyne.core.data.discovery.PodcastIndexDirectoryClient
import ch.lkmc.neutrodyne.core.data.discovery.PodcastIndexSearchProvider
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import okio.FileSystem

/** `:core:data`'s platform-agnostic bindings (03 fetch pipeline). */
@BindingContainer
@ContributesTo(AppScope::class)
object DataBindings {
    // Provider bindings keep the concrete search service internal while sharing its lookup instance.
    @Provides
    @SingleIn(AppScope::class)
    internal fun podcastIndexProvider(
        http: DirectoryHttp,
        settings: DirectorySettings,
        clock: Clock,
    ): PodcastIndexSearchProvider = PodcastIndexSearchProvider(http, settings, clock)

    @Provides
    internal fun podcastIndexClient(provider: PodcastIndexSearchProvider): PodcastIndexDirectoryClient = provider

    /** The real file system for feed temp files; tests substitute fakes. */
    @Provides
    fun fileSystem(): FileSystem = FileSystem.SYSTEM
}
