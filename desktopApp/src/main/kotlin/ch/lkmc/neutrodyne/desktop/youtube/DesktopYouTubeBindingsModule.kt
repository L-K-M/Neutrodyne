// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.impl.ExternalOnlyYouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.impl.NoExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.impl.NoOpYouTubeEnricher
import ch.lkmc.neutrodyne.youtube.impl.StaticYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.impl.UnsupportedYouTubeChannelSearch
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides

/** Shell-owned bindings: all M8 builds remain external until M9a / MD3. */
@BindingContainer
object DesktopYouTubeBindingsModule {
    private val external = StaticYouTubeCapabilitiesSource()
    private val streams = ExternalOnlyYouTubeStreamResolver()
    private val enrichment = NoOpYouTubeEnricher()
    private val search = UnsupportedYouTubeChannelSearch()
    private val lookup = NoExtractorChannelLookup()

    @Provides fun capabilities(): YouTubeCapabilitiesSource = external

    @Provides fun streamResolver(): YouTubeStreamResolver = streams

    @Provides fun enricher(): YouTubeEnricher = enrichment

    @Provides fun channelSearch(): YouTubeChannelSearch = search

    @Provides fun channelLookup(): ExtractorChannelLookup = lookup
}
