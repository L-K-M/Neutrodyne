// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeLocalMediaIndex
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemFactoryTest {
    private val pinned = mutableSetOf<String>()
    private val art =
        object : ArtworkStore {
            override fun pinnedFile(key: String): Path? = if (key in pinned) "/art/$key".toPath() else null

            override fun pin(
                ref: ArtworkRef,
                reason: PinReason,
                ownerId: Long,
            ) = Unit

            override fun unpin(
                key: String,
                reason: PinReason,
                ownerId: Long,
            ) = Unit

            override fun contentUri(
                key: String,
                version: Int,
            ) = "content://ch.lkmc.neutrodyne.debug.artwork/$key?v=$version"

            override fun isPinned(key: String): Boolean = key in pinned

            override fun pinnedPath(key: String): Path? = pinnedFile(key)

            override suspend fun collectGarbage(): Int = 0
        }
    private val local = FakeLocalMediaIndex()
    private val factory = MediaItemFactory(art, local)

    @Test fun canonicalIdentityNoMimeAndFingerprintAgreeWithResolver() {
        val row = media()
        val item = factory.build(row)
        assertEquals("episode:1", item.mediaId)
        assertEquals("neutrodyne://episode/1", item.localConfiguration?.uri.toString())
        assertEquals(item.localConfiguration?.uri, item.requestMetadata.mediaUri)
        assertNull(item.localConfiguration?.mimeType)
        assertEquals(
            EnclosureFingerprint.cacheKey(1, row.enclosureUrl!!, null),
            item.localConfiguration?.customCacheKey,
        )
        assertEquals("Episode", item.mediaMetadata.title)
        assertEquals("Podcast", item.mediaMetadata.artist)
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, item.mediaMetadata.mediaType)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
    }

    @Test fun artworkUsesPinnedRssEpisodeAndAlwaysSquareChannelCoverForYouTube() {
        assertTrue(
            factory
                .build(media())
                .mediaMetadata.artworkUri
                .toString()
                .contains("podcast-art"),
        )
        pinned.add("episode-art")
        assertTrue(
            factory
                .build(media())
                .mediaMetadata.artworkUri
                .toString()
                .contains("episode-art"),
        )
        val yt = factory.build(media(type = SourceType.YOUTUBE_CHANNEL))
        assertTrue(
            yt.mediaMetadata.artworkUri
                .toString()
                .contains("podcast-art"),
        )
        assertEquals("yt:abcdefghijk", yt.localConfiguration?.customCacheKey)
    }

    @Test fun authorAndPublicationDateReachSystemMetadataInUtc() {
        val row =
            media().copy(
                podcastAuthor = "Host",
                pubDate =
                    java.time.Instant
                        .parse("2026-01-01T00:30:00Z")
                        .toEpochMilli(),
            )
        val metadata = factory.build(row).mediaMetadata
        assertEquals("Host", metadata.albumArtist)
        assertEquals(2026, metadata.releaseYear)
        assertEquals(1, metadata.releaseMonth)
        assertEquals(1, metadata.releaseDay)
        assertNull(factory.build(media()).mediaMetadata.releaseYear)
    }

    @Test fun completionUsesOneShotPositionAndMeasuredDurationAndLocalHint() {
        local.files[1] = "file:///download/1"
        val item = factory.build(media().copy(measuredDurationMs = 100_000), 25_000)
        assertEquals(100_000L, item.mediaMetadata.durationMs)
        assertTrue(item.requestMetadata.extras!!.getBoolean(MediaItemFactory.LOCAL_HINT))
        val extras = item.mediaMetadata.extras!!
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
            extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        assertEquals(0.25, extras.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.0001)
        val played = factory.build(media().copy(playedAt = 1), 25_000)
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
            played.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        assertFalse(played.mediaMetadata.extras!!.containsKey(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE))
        val unknown = factory.build(media().copy(durationMs = 0), 1)
        assertNull(unknown.mediaMetadata.durationMs)
        assertFalse(unknown.mediaMetadata.extras!!.containsKey(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE))
    }
}
