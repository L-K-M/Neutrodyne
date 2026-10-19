// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeChannelMetadataImpl
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataResult
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeChannelResolver
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeChannelMetadataTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val resolver = FakeYouTubeChannelResolver()
    private val pins = mutableListOf<ArtworkRef>()
    private val art =
        object : ArtworkStore {
            override fun pinnedFile(key: String): Path? = null

            override fun pin(
                ref: ArtworkRef,
                reason: PinReason,
                ownerId: Long,
            ) {
                pins += ref
            }

            override fun unpin(
                key: String,
                reason: PinReason,
                ownerId: Long,
            ) = Unit

            override fun contentUri(
                key: String,
                version: Int,
            ): String = key

            override fun isPinned(key: String): Boolean = false

            override fun pinnedPath(key: String): Path? = null

            override suspend fun collectGarbage(): Int = 0
        }
    private val metadata = YouTubeChannelMetadataImpl(db, resolver, art, clock)
    private val channel =
        ChannelResolution.Resolved(
            YouTubeFeedUrls.YOUTUBE_CONTROL_CHANNEL_ID,
            "Page title",
            "https://yt3.ggpht.com/avatar=s900",
            "https://yt3.ggpht.com/banner",
            "Plain <text>",
            ResolvedVia.HTML,
        )

    @AfterTest fun close() {
        db.close()
    }

    private suspend fun seed(at: Long? = null) =
        seedPodcast(db, YouTubeFeedUrls.canonical(channel.channelId), block = {
            copy(sourceType = SourceType.YOUTUBE_CHANNEL, youtubeChannelId = channel.channelId, channelMetadataAt = at)
        })

    @Test fun concurrentDetailCallsCoalesceAndRefreshAtThirtyDays() =
        runBlocking {
            val id = seed()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            resolver.resolve = { _, depth ->
                assertEquals(MetadataDepth.FULL, depth)
                entered.complete(Unit)
                release.await()
                channel
            }
            val a = async { metadata.refresh(id, ChannelMetadataReason.DETAIL) }
            withTimeout(5_000) { entered.await() }
            val b = async(start = CoroutineStart.UNDISPATCHED) { metadata.refresh(id, ChannelMetadataReason.DETAIL) }
            release.complete(Unit)
            assertEquals(ChannelMetadataResult.UPDATED, a.await())
            assertEquals(ChannelMetadataResult.UPDATED, b.await())
            val row = db.podcastDao().byId(id)!!
            assertEquals(channel.bannerUrl, row.bannerUrl)
            assertEquals("Seeded", row.title)
            assertEquals("Plain &lt;text&gt;", row.descriptionHtml)
            assertEquals(ArtworkKeys.forUrl(channel.avatarUrl!!), row.artworkKey)
            assertEquals(1, pins.size)
            assertEquals(ChannelMetadataResult.FRESH, metadata.refresh(id, ChannelMetadataReason.DETAIL))
            clock.nowMs += 30L * 24 * 60 * 60 * 1000
            assertEquals(ChannelMetadataResult.UPDATED, metadata.refresh(id, ChannelMetadataReason.DETAIL))
            assertEquals(2, resolver.calls.size)
            assertEquals(1, pins.size)
        }

    @Test fun failedMetadataRemainsRetryableAndNotFoundIsStamped() =
        runBlocking {
            val id = seed()
            resolver.resolve =
                { _, _ -> channel.copy(title = null, avatarUrl = null, bannerUrl = null, description = null) }
            assertEquals(ChannelMetadataResult.FAILED, metadata.refresh(id, ChannelMetadataReason.DETAIL))
            assertNull(db.podcastDao().byId(id)!!.channelMetadataAt)
            resolver.resolve = { _, _ -> ChannelResolution.NotFound }
            assertEquals(ChannelMetadataResult.UPDATED, metadata.refresh(id, ChannelMetadataReason.DETAIL))
            assertEquals(clock.now(), db.podcastDao().byId(id)!!.channelMetadataAt)
        }

    @Test fun detailOpeningDuringAvatarFetchUpgradesSharedRequest() =
        runBlocking {
            val id = seed()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            resolver.resolve = { _, depth ->
                if (depth == MetadataDepth.AVATAR) {
                    entered.complete(Unit)
                    release.await()
                }
                channel
            }
            val ingest = async { metadata.refresh(id, ChannelMetadataReason.AFTER_INGEST) }
            withTimeout(5_000) { entered.await() }
            val detail =
                async(start = CoroutineStart.UNDISPATCHED) { metadata.refresh(id, ChannelMetadataReason.DETAIL) }
            release.complete(Unit)
            assertEquals(ChannelMetadataResult.UPDATED, ingest.await())
            assertEquals(ChannelMetadataResult.UPDATED, detail.await())
            assertEquals(listOf(MetadataDepth.AVATAR, MetadataDepth.FULL), resolver.calls.map { it.second })
        }
}
