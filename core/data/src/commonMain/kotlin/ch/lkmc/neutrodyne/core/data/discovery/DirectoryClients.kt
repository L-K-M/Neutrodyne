// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.discovery

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryAccess
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import kotlinx.collections.immutable.ImmutableList

/** Shared Apple search/lookup/chart limiter/cache owner (03); parsers map in core:data. */
internal interface AppleDirectoryClient {
    suspend fun search(
        query: String,
        country: String,
    ): Outcome<ImmutableList<DirectoryHit>, ProviderStatus>

    /** At most 100 decimal IDs, requested order; ONE_TIME_LOOKUP requires explicit consent (03). */
    suspend fun lookup(
        ids: ImmutableList<String>,
        access: DirectoryAccess,
    ): Outcome<ImmutableList<DirectoryHit>, ProviderStatus>

    suspend fun charts(
        genre: ChartGenre?,
        country: String,
    ): Outcome<ImmutableList<DirectoryHit>, ProviderStatus>
}

/** Add's PI feed-ID lookup; uses the same effective key/auth/cache lifecycle as search. */
internal interface PodcastIndexDirectoryClient {
    /** Disabled or keyless means Disabled with no request. No lookup by feed URL in M7. */
    suspend fun lookup(feedId: String): Outcome<DirectoryHit?, ProviderStatus>
}
