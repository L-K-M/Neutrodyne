// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.ChannelSearchResult
import ch.lkmc.neutrodyne.youtube.api.EnrichResult
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.SearchCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import ch.lkmc.neutrodyne.youtube.api.YtRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalYouTubeTest {
    @Test fun capabilitiesStayExternal() {
        val source = StaticYouTubeCapabilitiesSource()
        assertEquals(
            YouTubeCapabilities(false, false, false, false, false, ExternalReason.NOT_YET_AVAILABLE),
            source.capabilities.value,
        )
    }

    @Test fun unsupportedPortsNeedNoNetworkOrHostDependencies() =
        runTest {
            val streams = ExternalOnlyYouTubeStreamResolver()
            assertEquals(ResolveResult.Unsupported, streams.resolveAudio("invalid", AudioPref()))
            streams.invalidate("invalid")
            streams.invalidateAll()
            val enricher = NoOpYouTubeEnricher()
            assertEquals(EnrichResult.Unsupported, enricher.enrich("invalid", setOf("invalid"), 0))
            assertEquals(UploadsPageResult.Unsupported, enricher.uploadsPage("invalid", 0, UploadsCursor("expired")))
            assertEquals(
                ChannelSearchResult.Unsupported,
                UnsupportedYouTubeChannelSearch().search("anything", SearchCursor("expired")),
            )
            for (depth in MetadataDepth.entries) {
                assertNull(NoExtractorChannelLookup().lookup(YtRef.Handle("@x"), depth))
            }
        }
}
