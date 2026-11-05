// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.ArtworkColors
import kotlinx.coroutines.flow.Flow

/** Read-only colours; UI consumers never reach the file store or database. */
interface ArtworkRepository {
    /**
     * Observe available metadata for [key]. Missing/unprocessed primary art may use the current
     * artwork key of [fallbackPodcastId]; completed monochrome/transparent art never falls back.
     * Emit null if neither has completed metadata. Returned identity is always the selected row's.
     * Observe podcast key changes as well as colour/version updates; cancel obsolete collectors.
     * A failed refresh retaining a valid local file keeps its previous colours available.
     * This call neither pins nor downloads. Callers switch collectors when the current item changes.
     */
    fun observeColors(
        key: String,
        fallbackPodcastId: Long? = null,
    ): Flow<ArtworkColors?>
}
