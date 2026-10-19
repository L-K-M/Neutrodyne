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
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeThumbnails
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.uuid.Uuid

/** Canonical identity and memberships commit together, shared by Add and import. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class YouTubeSubscriptionWriterImpl(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val orderKeys: OrderKeys,
) : YouTubeSubscriptionWriter {
    override suspend fun write(
        channel: ChannelResolution.Resolved,
        variants: Int,
        groupIds: Set<Long>,
        duplicatePolicy: YouTubeDuplicatePolicy,
        customTitle: String?,
    ): Outcome<YouTubeSubscription, SubscribeError> {
        if (!YouTubeVariantBits.isValid(variants)) return Outcome.Failure(SubscribeError.InvalidUrl)
        val url = YouTubeFeedUrls.canonical(channel.channelId)
        val key = UrlNormalizer.forIdentity(url) ?: return Outcome.Failure(SubscribeError.InvalidUrl)
        return suspendRunCatching {
            db.withWriteTransaction {
                val dao = db.podcastDao()
                val existing = dao.byFeedKey(key)
                if (existing != null && duplicatePolicy == YouTubeDuplicatePolicy.REJECT) {
                    return@withWriteTransaction Outcome.Failure(SubscribeError.AlreadySubscribed(existing.id))
                }
                val now = clock.now()
                val avatar = channel.avatarUrl?.let { YouTubeThumbnails.avatar(it, 900) }
                val id =
                    existing?.id ?: dao.insertPodcast(
                        PodcastEntity(
                            syncId = Uuid.random().toString(),
                            sourceType = SourceType.YOUTUBE_CHANNEL,
                            feedUrl = url,
                            feedKey = key,
                            youtubeChannelId = channel.channelId,
                            youtubeVariants = variants,
                            title =
                                channel.title?.takeIf(String::isNotBlank) ?: channel.channelId,
                            customTitle = customTitle,
                            author = channel.title?.takeIf(String::isNotBlank) ?: channel.channelId,
                            link = YouTubeFeedUrls.channelPage(channel.channelId),
                            artworkUrl = avatar,
                            artworkKey = avatar?.let(ArtworkKeys::forUrl) ?: ArtworkKeys.monogram(key),
                            bannerUrl = channel.bannerUrl,
                            descriptionHtml = channel.description?.let(::escapeChannelDescription),
                            channelMetadataAt = if (avatar != null) now else null,
                            status = PodcastStatus.PENDING_FIRST_FETCH,
                            initialFetch = true,
                            subscribedAt = now,
                            nextRefreshAt = now,
                            guidCoverageSince = now,
                            pagingComplete = true,
                        ),
                    )
                val touched = mutableSetOf<Long>()
                for (groupId in groupIds) {
                    val inserted =
                        db.groupDao().insertMember(
                            PodcastGroupMemberEntity(
                                groupId = groupId,
                                podcastId = id,
                                orderKey = orderKeys.after(db.groupDao().lastMemberOrderKey(groupId)),
                                addedAt = now,
                                source = MemberSource.MANUAL,
                            ),
                        )
                    if (inserted != -1L) touched += groupId
                }
                if (touched.isNotEmpty()) db.groupDao().touchUpdatedAt(touched, now)
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
        }.getOrElse {
            val owner = db.podcastDao().byFeedKey(key)
            if (owner != null && duplicatePolicy == YouTubeDuplicatePolicy.REJECT) {
                Outcome.Failure(SubscribeError.AlreadySubscribed(owner.id))
            } else {
                Outcome.Failure(SubscribeError.Storage)
            }
        }
    }
}

/** The model stores HTML, whereas YouTube's page description is plain text. */
internal fun escapeChannelDescription(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")
