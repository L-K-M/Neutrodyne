// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.ArtworkRepository
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Metadata-only fake. Missing keys model absent/unavailable files; no download is simulated. */
class FakeArtworkRepository : ArtworkRepository {
    val colors = MutableStateFlow<Map<String, ArtworkColors>>(emptyMap())
    val podcastArtworkKeys = MutableStateFlow<Map<Long, String>>(emptyMap())

    override fun observeColors(
        key: String,
        fallbackPodcastId: Long?,
    ): Flow<ArtworkColors?> =
        combine(colors, podcastArtworkKeys) { rows, podcastKeys ->
            val primary = rows[key]?.takeIf { it.avgArgb != null }
            val fallbackKey = podcastKeys[fallbackPodcastId]
            primary ?: rows[fallbackKey]?.takeIf { it.avgArgb != null }
        }.distinctUntilChanged()
}
