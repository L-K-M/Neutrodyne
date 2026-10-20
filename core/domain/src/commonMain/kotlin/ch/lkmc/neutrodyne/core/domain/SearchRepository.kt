// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow

/** Podcast directories only; no YouTube requests, database writes or persisted results. */
interface SearchRepository {
    /**
     * Cold: initial Pending/Disabled, per-answer partials, one final without Pending.
     * Blank/all-disabled emits one empty final without I/O. Cancellation propagates;
     * provider/country/key changes cancel the run. Retry collects afresh. The caller routes
     * URL-like input to Add. See 03 Search and discovery for deadlines, fusion and caches.
     */
    fun search(query: String): Flow<SearchResults>

    /** Apple only; null genre = overall top 100, ordered by chart position. No I/O when off. */
    suspend fun charts(genre: ChartGenre?): Outcome<ImmutableList<DirectoryHit>, ProviderStatus>

    /** NFC, case-insensitive group-name match; unknown names return null. */
    fun genreForGroupName(name: String): ChartGenre?

    /**
     * Stable DIRECTORY_PROVIDER_ORDER, including disabled/keyless PI. Configuration versions
     * change on country/switch/key changes; status-only updates do not. UI restarts the current
     * query when configuration changes, independently of per-operation status rendering.
     */
    fun observeProviders(): Flow<ImmutableList<ProviderInfo>>

    /** Verify before replacing SecretStore's user pair; caller clears secret in finally (03). */
    suspend fun setPodcastIndexKey(
        key: String,
        secret: CharArray,
    ): Outcome<Unit, ProviderStatus>

    /** Remove only the user pair; fall back to build key. No verification request. */
    suspend fun clearPodcastIndexKey()
}
