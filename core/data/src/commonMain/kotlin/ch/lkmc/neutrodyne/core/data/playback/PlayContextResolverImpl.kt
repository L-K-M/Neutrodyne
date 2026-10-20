// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.playback

import androidx.room3.withReadTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ContextEligibility
import ch.lkmc.neutrodyne.core.domain.PlayContextResolver
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FilterFlagBits
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * 05's context membership and start rules over [FeedQueryBuilder.contextTail] (02 Play context).
 * [spec] is pure, the caller supplies the resolved view filters, the effective play order and the
 * already-computed age bound; contexts only fix them. [startItem] reads the singleton and the live
 * YouTube capability inside one read transaction per attempt.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class PlayContextResolverImpl(
    private val db: NeutrodyneDatabase,
    private val capabilities: YouTubeCapabilitiesSource,
) : PlayContextResolver {
    override suspend fun spec(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): PlayContextSpec {
        val type =
            when (source) {
                FeedSource.All -> ContextType.ALL
                FeedSource.Ungrouped -> ContextType.UNGROUPED
                is FeedSource.Group -> ContextType.GROUP
                is FeedSource.Podcast -> ContextType.PODCAST
            }
        val contextId =
            when (source) {
                is FeedSource.Group -> source.groupId
                is FeedSource.Podcast -> source.podcastId
                else -> null
            }
        // The virtual feeds have no stored play order: they always play newest first (05).
        val resolvedOrder =
            if (type == ContextType.ALL || type == ContextType.UNGROUPED) FeedOrder.NEWEST_FIRST else order
        return PlayContextSpec(
            type = type,
            contextId = contextId,
            order = resolvedOrder,
            filterFlags = filters.toFlags() or FilterFlagBits.UNPLAYED,
            mediaFilter = filters.media,
            // Podcast screens carry no age bound (05's context table: transient filters only).
            minSortDate = if (type == ContextType.PODCAST) null else filters.minSortDate,
            boundStartBySubscription = type == ContextType.GROUP && resolvedOrder == FeedOrder.OLDEST_FIRST,
        )
    }

    override suspend fun startItem(
        spec: PlayContextSpec,
        eligibility: ContextEligibility,
    ): Long? {
        if (spec.type == ContextType.EXTERNAL) return null
        val youtubePlayable =
            when (eligibility) {
                ContextEligibility.PLAYABLE -> capabilities.capabilities.value.inAppPlayback
                ContextEligibility.INCLUDE_EXTERNAL_YOUTUBE -> true
            }
        return db.withReadTransaction {
            val current = db.playSessionDao().get()?.currentEpisodeId

            // LIMIT 2, not 1: the current item stays tail-eligible until the start commit removes
            // it from Up next, so it can head the result and would hide the real candidate (06's
            // k + 1 rule applied to k = 1).
            suspend fun head(boundBySubscription: Boolean): Long? =
                db
                    .feedDao()
                    .contextTail(
                        FeedQueryBuilder.contextTail(spec, null, null, 2, youtubePlayable, boundBySubscription),
                    ).firstOrNull { it.episodeId != current }
                    ?.episodeId

            // 05 start rule 4: oldest-first groups first try the subscription bound, then the
            // unbounded back catalogue when nothing post-subscription is left.
            if (spec.boundStartBySubscription) head(true) ?: head(false) else head(false)
        }
    }
}
