// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeThumbnailsTest {
    @Test fun avatarNormalizesSizeWithoutLosingModifiers() {
        for (host in listOf("yt3.googleusercontent.com", "yt3.ggpht.com")) {
            assertEquals(
                "https://$host/token=s900-c-k-no-rj",
                YouTubeThumbnails.avatar("https://$host/token=s88-c-k-no-rj"),
            )
        }
        assertEquals("https://images.test/no-token", YouTubeThumbnails.avatar("https://images.test/no-token"))
        assertFailsWith<IllegalArgumentException> { YouTubeThumbnails.avatar("url", 0) }
    }

    @Test fun bannerChoosesSmallestSufficientThenWidest() {
        val sources = listOf(BannerSource("wide", 1707), BannerSource("small", 1060), BannerSource("medium", 1280))
        assertEquals("medium", YouTubeThumbnails.pickBanner(sources))
        assertEquals("small", YouTubeThumbnails.pickBanner(sources.filter { it.width < 1280 }))
        assertNull(YouTubeThumbnails.pickBanner(emptyList()))
        assertNull(YouTubeThumbnails.pickBanner(listOf(BannerSource("", 1707), BannerSource("bad", -1))))
    }

    @Test fun rowBoundaryAndStoredUrlUseMq() {
        assertEquals(listOf(ThumbVariant.MQ), YouTubeThumbnails.chainFor(320))
        assertEquals(listOf(ThumbVariant.MAXRES, ThumbVariant.HQ720, ThumbVariant.MQ), YouTubeThumbnails.chainFor(321))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", YouTubeThumbnails.video("abcdefghijk"))
        assertFailsWith<IllegalArgumentException> { YouTubeThumbnails.video("../bad") }
    }

    @Test fun thumbnailMatcherAcceptsOnlyRecognizedHostsAndIdShape() {
        for (url in listOf(
            "https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg",
            "http://i9.ytimg.com/vi_webp/abcdefghijk/maxresdefault.webp",
        )) {
            assertTrue(YouTubeThumbnails.THUMB.matches(url))
        }
        for (url in listOf(
            "https://i.ytimg.com.evil/vi/abcdefghijk/mqdefault.jpg",
            "https://i.ytimg.com/vi/short/hqdefault.jpg",
            "https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg?extra",
        )) {
            assertFalse(YouTubeThumbnails.THUMB.matches(url))
        }
    }
}
