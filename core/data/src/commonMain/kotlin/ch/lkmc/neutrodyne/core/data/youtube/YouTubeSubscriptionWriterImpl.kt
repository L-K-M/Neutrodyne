// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.youtube

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastGroupMemberEntity
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.YouTubeDuplicatePolicy
import ch.lkmc.neutrodyne.core.domain.YouTubeSubscription
import ch.lkmc.neutrodyne.core.domain.YouTubeSubscriptionDisposition
import ch.lkmc.neutrodyne.core.domain.YouTubeSubscriptionWriter
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeThumbnails
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/** The W1 transaction contract shared by Add and import; never performs network IO. */
@ContributesBinding(AppScope::class)
@Inject
internal class YouTubeSubscriptionWriterImpl(
    private val db: NeutrodyneDatabase,
    private val orderKeys: OrderKeys,
    private val clock: Clock,
) : YouTubeSubscriptionWriter {
    override suspend fun write(
        channel: ChannelResolution.Resolved,
        variants: Int,
        groupIds: Set<Long>,
        duplicatePolicy: YouTubeDuplicatePolicy,
        customTitle: String?,
    ): Outcome<YouTubeSubscription, SubscribeError> {
        if (!YouTubeVariantBits.isValid(variants)) return Outcome.Failure(SubscribeError.InvalidUrl)
        return suspendRunCatching {
            db.withWriteTransaction {
                val url = YouTubeFeedUrls.canonical(channel.channelId)
                val key = checkNotNull(UrlNormalizer.forIdentity(url))
                val dao = db.podcastDao()
                val existing = dao.byFeedKey(key)
                if (existing != null && duplicatePolicy == YouTubeDuplicatePolicy.REJECT) {
                    return@withWriteTransaction Outcome.Failure(SubscribeError.AlreadySubscribed(existing.id))
                }
                val now = clock.now()
                val title = channel.title?.takeIf(String::isNotBlank) ?: channel.channelId
                val avatar = channel.avatarUrl?.let(YouTubeThumbnails::avatar)
                val id =
                    existing?.id ?: dao.insertPodcast(
                        PodcastEntity(
                            syncId = Uuid.random().toString(),
                            sourceType = SourceType.YOUTUBE_CHANNEL,
                            feedUrl = url,
                            feedKey = key,
                            title = title,
                            author = title,
                            artworkUrl = avatar,
                            bannerUrl = channel.bannerUrl,
                            descriptionHtml = channel.description,
                            channelMetadataAt = now.takeIf { avatar != null },
                            customTitle = customTitle,
                            link = YouTubeFeedUrls.channelPage(channel.channelId),
                            youtubeChannelId = channel.channelId,
                            youtubeVariants = variants,
                            artworkKey = avatar?.let(ArtworkKeys::forUrl) ?: ArtworkKeys.monogram(key),
                            status = PodcastStatus.PENDING_FIRST_FETCH,
                            initialFetch = true,
                            subscribedAt = now,
                            nextRefreshAt = now,
                            guidCoverageSince = now,
                        ),
                    )
                for (groupId in groupIds) {
                    if (db.groupDao().groupById(groupId) == null) continue
                    val inserted =
                        db.groupDao().insertMember(
                            PodcastGroupMemberEntity(
                                groupId = groupId,
                                podcastId = id,
                                orderKey = orderKeys.after(db.groupDao().lastMemberOrderKey(groupId)),
                                addedAt = now,
                            ),
                        )
                    if (inserted != -1L) db.groupDao().touchUpdatedAt(listOf(groupId), now)
                }
                Outcome.Success(
                    YouTubeSubscription(
                        id,
                        if (existing ==
                            null
                        ) {
                            YouTubeSubscriptionDisposition.INSERTED
                        } else {
                            YouTubeSubscriptionDisposition.EXISTING
                        },
                    ),
                )
            }
        }.getOrElse { Outcome.Failure(SubscribeError.Storage) }
    }
}
