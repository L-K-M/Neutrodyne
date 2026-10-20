// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelSearch
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.api.YouTubeEnricher
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedMetadataReader
import ch.lkmc.neutrodyne.youtube.api.YouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.impl.DefaultYouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.impl.DeferredYouTubeEngine
import ch.lkmc.neutrodyne.youtube.impl.ExternalOnlyYouTubeStreamResolver
import ch.lkmc.neutrodyne.youtube.impl.NoExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.impl.NoOpYouTubeEnricher
import ch.lkmc.neutrodyne.youtube.impl.StaticYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.impl.UnsupportedYouTubeChannelSearch
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.util.Locale

/** Shell-owned bindings: all M8 builds remain external until M9a / MD3. */
@BindingContainer
object DesktopYouTubeBindingsModule {
    private val external = StaticYouTubeCapabilitiesSource()
    private val control = DeferredYouTubeEngine()
    private val streams = ExternalOnlyYouTubeStreamResolver()
    private val enrichment = NoOpYouTubeEnricher()
    private val search = UnsupportedYouTubeChannelSearch()
    private val lookup = NoExtractorChannelLookup()

    @Provides fun capabilities(): YouTubeCapabilitiesSource = external

    @Provides fun engine(): YouTubeEngine = control

    @Provides fun streamResolver(): YouTubeStreamResolver = streams

    @Provides fun enricher(): YouTubeEnricher = enrichment

    @Provides fun channelSearch(): YouTubeChannelSearch = search

    @Provides fun channelLookup(): ExtractorChannelLookup = lookup

    @Provides
    @SingleIn(AppScope::class)
    fun channelResolver(
        clients: NeutrodyneHttpClients,
        reader: YouTubeFeedMetadataReader,
        extractor: ExtractorChannelLookup,
    ): YouTubeChannelResolver =
        DefaultYouTubeChannelResolver(clients, reader, extractor) {
            Locale.getDefault().toLanguageTag()
        }
}
