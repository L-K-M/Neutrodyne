// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.artwork.ArtworkKeys
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.data.youtube.DefaultYouTubeChannelMetadata
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataReason
import ch.lkmc.neutrodyne.core.domain.ChannelMetadataResult
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeChannelResolver
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YtRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeChannelMetadataTest {
    @Test fun newAndChangedAvatarsAreExplicitlyRepinnedAndPageFieldsPreserved() =
        runTest {
            Fixture(this).use { f ->
                val id = f.channel()
                assertEquals(ChannelMetadataResult.UPDATED, f.refresh(id))
                val row = f.db.podcastDao().byId(id)!!
                assertEquals("https://example.com/avatar=s900-c", row.artworkUrl)
                assertEquals(ArtworkKeys.forUrl(row.artworkUrl!!), row.artworkKey)
                assertEquals(NOW, row.channelMetadataAt)
                assertEquals("Description", row.descriptionHtml)
                assertEquals(1, f.art.pins.size)
                assertEquals(
                    Triple(ArtworkRef(row.artworkKey, row.artworkUrl, 0), PinReason.SUBSCRIPTION, id),
                    f.art.pins.single(),
                )
                assertEquals(ChannelMetadataResult.FRESH, f.refresh(id))
                assertEquals(1, f.resolver.calls.size)
                f.clock.nowMs += 31 * DAY
                f.resolver.resolve =
                    {
                        ref,
                        _,
                        ->
                        f.resolved((ref as YtRef.Channel).id, "https://example.com/new=s48-c").copy(description = null)
                    }
                assertEquals(ChannelMetadataResult.UPDATED, f.refresh(id))
                assertEquals(
                    "Description",
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .descriptionHtml,
                )
                assertEquals(2, f.art.pins.size)
            }
        }

    @Test fun sameAvatarDoesNotRepinAndNotFoundAdvancesTimestampOnly() =
        runTest {
            Fixture(this).use { f ->
                val id = f.channel()
                f.refresh(id)
                f.clock.nowMs += 31 * DAY
                f.refresh(id)
                assertEquals(1, f.art.pins.size)
                f.clock.nowMs += 31 * DAY
                f.resolver.resolve = { _, _ -> ChannelResolution.NotFound }
                f.refresh(id)
                val row = f.db.podcastDao().byId(id)!!
                assertEquals(f.clock.now(), row.channelMetadataAt)
                assertEquals("https://example.com/avatar=s900-c", row.artworkUrl)
                assertEquals(1, f.art.pins.size)
            }
        }

    @Test fun failedOrEmptyStaticLookupLeavesMetadataDueForRetry() =
        runTest {
            Fixture(this).use { f ->
                val id = f.channel()
                f.resolver.resolve = { _, _ -> ChannelResolution.Failed(true, FailReason.NETWORK, null) }
                assertEquals(ChannelMetadataResult.FAILED, f.refresh(id))
                assertNull(
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .channelMetadataAt,
                )
                f.resolver.resolve =
                    { ref, _ -> f.resolved((ref as YtRef.Channel).id).copy(avatarUrl = null, description = null) }
                assertEquals(ChannelMetadataResult.FAILED, f.refresh(id))
                assertNull(
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .channelMetadataAt,
                )
                f.resolver.resolve = { ref, _ -> f.resolved((ref as YtRef.Channel).id) }
                assertEquals(ChannelMetadataResult.UPDATED, f.refresh(id))
            }
        }

    @Test fun detailCoalescesAndUpgradesAnInflightAvatarLookupToFull() =
        runTest {
            Fixture(this).use { f ->
                val id = f.channel()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.resolver.resolve = { ref, depth ->
                    if (depth == MetadataDepth.AVATAR) {
                        entered.complete(Unit)
                        release.await()
                    }
                    f.resolved((ref as YtRef.Channel).id).copy(
                        bannerUrl =
                            if (depth ==
                                MetadataDepth.FULL
                            ) {
                                "https://example.com/banner"
                            } else {
                                null
                            },
                    )
                }
                val first = async { f.refresh(id) }
                entered.await()
                val second = async { f.metadata.refresh(id, ChannelMetadataReason.DETAIL) }
                kotlinx.coroutines.yield()
                release.complete(Unit)
                assertEquals(ChannelMetadataResult.UPDATED, first.await())
                assertEquals(ChannelMetadataResult.UPDATED, second.await())
                assertEquals(listOf(MetadataDepth.AVATAR, MetadataDepth.FULL), f.resolver.calls.map { it.second })
                assertEquals(
                    "https://example.com/banner",
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .bannerUrl,
                )
                assertEquals(1, f.art.pins.size)
            }
        }

    @Test fun oneBudgetAllowsExactly100NewAnd30StalePerWindow() =
        runTest {
            Fixture(this).use { f ->
                val new = (1..101).map { f.channel(it) }
                val stale = (200..230).map { f.channel(it, NOW - 31 * DAY) }
                for (id in new.take(100) + stale.take(30)) assertEquals(ChannelMetadataResult.UPDATED, f.refresh(id))
                assertEquals(ChannelMetadataResult.DEFERRED, f.refresh(new.last()))
                assertEquals(ChannelMetadataResult.DEFERRED, f.refresh(stale.last()))
                assertEquals(130, f.resolver.calls.size)
                f.clock.nowMs += 10 * 60_000
                assertEquals(ChannelMetadataResult.UPDATED, f.refresh(new.last()))
                assertEquals(ChannelMetadataResult.UPDATED, f.refresh(stale.last()))
            }
        }

    @Test fun concurrentChannelsHaveAtMostTwoLookupsAndJitter() =
        runTest {
            Fixture(this).use { f ->
                val ids = (1..6).map { f.channel(it) }
                var active = 0
                var peak = 0
                val starts = mutableListOf<Long>()
                f.resolver.resolve = { ref, _ ->
                    starts += testScheduler.currentTime
                    active++
                    peak = maxOf(peak, active)
                    delay(2000)
                    active--
                    f.resolved((ref as YtRef.Channel).id)
                }
                val jobs = ids.map { async { f.refresh(it) } }
                jobs.forEach { assertEquals(ChannelMetadataResult.UPDATED, it.await()) }
                assertEquals(2, peak)
                assertTrue(starts.all { it >= 500 })
                assertTrue(starts[2] >= starts[0] + 2500)
            }
        }

    @Test fun lookupTimeoutAndCancellationReleaseCoalescingWithoutTimestampWrite() =
        runTest {
            Fixture(this).use { f ->
                val id = f.channel()
                f.resolver.resolve = { _, _ -> awaitCancellation() }
                assertEquals(ChannelMetadataResult.FAILED, f.refresh(id))
                assertNull(
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .channelMetadataAt,
                )
                val entered = CompletableDeferred<Unit>()
                f.resolver.resolve = { _, _ ->
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val job = async { f.refresh(id) }
                entered.await()
                job.cancelAndJoin()
                assertNull(
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .channelMetadataAt,
                )
                f.resolver.resolve = { ref, _ -> f.resolved((ref as YtRef.Channel).id) }
                assertEquals(ChannelMetadataResult.UPDATED, f.refresh(id))
            }
        }

    @Test fun concurrentDetailCallsCoalesceAndRefreshAtThirtyDays() =
        runTest {
            Fixture(this).use { f ->
                val id = f.channel()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val channel =
                    f.resolved(YouTubeFeedUrls.YOUTUBE_CONTROL_CHANNEL_ID).copy(
                        avatarUrl = "https://yt3.ggpht.com/avatar=s900",
                        bannerUrl = "https://yt3.ggpht.com/banner",
                        description = "Plain <text>",
                    )
                f.resolver.resolve = { ref, depth ->
                    assertEquals(MetadataDepth.FULL, depth)
                    entered.complete(Unit)
                    release.await()
                    channel.copy(channelId = (ref as YtRef.Channel).id)
                }
                val a = async { f.metadata.refresh(id, ChannelMetadataReason.DETAIL) }
                entered.await()
                val b =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        f.metadata.refresh(id, ChannelMetadataReason.DETAIL)
                    }
                release.complete(Unit)
                assertEquals(ChannelMetadataResult.UPDATED, a.await())
                assertEquals(ChannelMetadataResult.UPDATED, b.await())
                val row = f.db.podcastDao().byId(id)!!
                assertEquals(channel.bannerUrl, row.bannerUrl)
                assertEquals("Seeded", row.title)
                assertEquals("Plain &lt;text&gt;", row.descriptionHtml)
                assertEquals(ArtworkKeys.forUrl(channel.avatarUrl!!), row.artworkKey)
                assertEquals(1, f.art.pins.size)
                assertEquals(ChannelMetadataResult.FRESH, f.metadata.refresh(id, ChannelMetadataReason.DETAIL))
                f.clock.nowMs += 30 * DAY
                assertEquals(ChannelMetadataResult.UPDATED, f.metadata.refresh(id, ChannelMetadataReason.DETAIL))
                assertEquals(2, f.resolver.calls.size)
                assertEquals(1, f.art.pins.size)
            }
        }

    @Test fun failedMetadataRemainsRetryableAndNotFoundIsStamped() =
        runTest {
            Fixture(this).use { f ->
                val id = f.channel()
                f.resolver.resolve = { ref, _ ->
                    f.resolved((ref as YtRef.Channel).id).copy(
                        title = null,
                        avatarUrl = null,
                        bannerUrl = null,
                        description = null,
                    )
                }
                assertEquals(ChannelMetadataResult.FAILED, f.metadata.refresh(id, ChannelMetadataReason.DETAIL))
                assertNull(
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .channelMetadataAt,
                )
                f.resolver.resolve = { _, _ -> ChannelResolution.NotFound }
                assertEquals(ChannelMetadataResult.UPDATED, f.metadata.refresh(id, ChannelMetadataReason.DETAIL))
                assertEquals(
                    f.clock.now(),
                    f.db
                        .podcastDao()
                        .byId(id)!!
                        .channelMetadataAt,
                )
            }
        }

    @Test fun detailOpeningDuringAvatarFetchUpgradesSharedRequest() =
        runTest {
            Fixture(this).use { f ->
                val id = f.channel()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.resolver.resolve = { ref, depth ->
                    if (depth == MetadataDepth.AVATAR) {
                        entered.complete(Unit)
                        release.await()
                    }
                    f.resolved((ref as YtRef.Channel).id)
                }
                val ingest = async { f.refresh(id) }
                entered.await()
                val detail =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        f.metadata.refresh(id, ChannelMetadataReason.DETAIL)
                    }
                release.complete(Unit)
                assertEquals(ChannelMetadataResult.UPDATED, ingest.await())
                assertEquals(ChannelMetadataResult.UPDATED, detail.await())
                assertEquals(listOf(MetadataDepth.AVATAR, MetadataDepth.FULL), f.resolver.calls.map { it.second })
            }
        }

    private class Fixture(
        scope: TestScope,
    ) : AutoCloseable {
        val clock = TestClock(NOW)
        val db = newDb(clock, queryContext = StandardTestDispatcher(scope.testScheduler))
        val resolver = FakeYouTubeChannelResolver()
        val art = RecordingArtwork()
        val metadata = DefaultYouTubeChannelMetadata(db, resolver, art, clock, Random(1))

        init {
            resolver.resolve = { ref, _ -> resolved((ref as YtRef.Channel).id) }
        }

        fun resolved(
            id: String,
            avatar: String = "https://example.com/avatar=s88-c",
        ) = ChannelResolution.Resolved(id, "Page title", avatar, null, "Description", ResolvedVia.HTML)

        suspend fun channel(
            number: Int = 0,
            metadataAt: Long? = null,
        ): Long {
            val id = "UC" + number.toString().padStart(21, 'a') + "A"
            return seedPodcast(db, YouTubeFeedUrls.canonical(id), block = {
                copy(sourceType = SourceType.YOUTUBE_CHANNEL, youtubeChannelId = id, channelMetadataAt = metadataAt)
            })
        }

        suspend fun refresh(id: Long) = metadata.refresh(id, ChannelMetadataReason.AFTER_INGEST)

        override fun close() {
            db.close()
        }
    }

    private class RecordingArtwork : ArtworkStore {
        val pins = mutableListOf<Triple<ArtworkRef, PinReason, Long>>()

        override fun pin(
            ref: ArtworkRef,
            reason: PinReason,
            ownerId: Long,
        ) {
            pins += Triple(ref, reason, ownerId)
        }

        override fun pinnedFile(key: String): Path? = null

        override fun pinnedPath(key: String): Path? = null

        override fun isPinned(key: String) = false

        override fun contentUri(
            key: String,
            version: Int,
        ) = "unused"

        override fun unpin(
            key: String,
            reason: PinReason,
            ownerId: Long,
        ) = Unit

        override suspend fun collectGarbage() = 0
    }

    private companion object {
        const val NOW = 1_792_411_200_000L
        const val DAY = 86_400_000L
    }
}
