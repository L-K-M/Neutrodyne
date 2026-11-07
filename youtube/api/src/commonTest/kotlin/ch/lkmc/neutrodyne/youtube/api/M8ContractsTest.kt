// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M8ContractsTest {
    private val classifier = YouTubeUrlClassifier()

    @Test fun unicodeHandlesNormalizeToNfcAfterDecoding() {
        for (input in listOf(
            "@cafe\u0301.name",
            "https://youtube.com/@cafe%CC%81.name/videos",
            "feed:view-source:youtube.com/@caf%C3%A9.name",
        )) {
            assertEquals(YtRef.Handle("@café.name"), classifier.classify(input), input)
        }
        assertEquals(YtRef.Handle("@日本語.音楽"), classifier.classify("@日本語.音楽"))
        assertNull(classifier.classify("@a%20b"))
        assertNull(classifier.classify("@a%2Fb"))
    }

    @Test fun uploadsPrefixesUseLengthBeforeText() {
        for ((prefix, hint) in listOf(
            "UU" to 7,
            "UULF" to 1,
            "UUSH" to 2,
            "UULV" to 4,
            "UUMO" to null,
            "UUPS" to null,
            "UULP" to null,
            "UUPV" to null,
        )) {
            for (url in listOf(
                "https://youtube.com/playlist?list=",
                "https://youtube.com/feeds/videos.xml?playlist_id=",
            )) {
                assertEquals(YtRef.Channel(ID, hint), classifier.classify(url + prefix + ID.drop(2)))
            }
        }
        for (prefix in listOf("UULF", "UUSH", "UULV", "UUMO", "UUPS", "UULP", "UUPV")) {
            val playlist = prefix + ID.drop(4)
            assertEquals(
                YtRef.Channel("UC" + playlist.drop(2), 7),
                classifier.classify("https://youtube.com/playlist?list=$playlist"),
            )
        }
    }

    @Test fun invalidChannelIdsNeverBecomeChannelIdentity() {
        for (id in listOf(ID.dropLast(1), ID + "A", ID.dropLast(1) + "B", "uc" + ID.drop(2), ID.replace('J', '!'))) {
            assertNull(classifier.classify(id), id)
            assertNull(classifier.classify("https://youtube.com/channel/$id"), id)
            assertNull(classifier.classify("https://youtube.com/feeds/videos.xml?channel_id=$id"), id)
            assertFailsWith<IllegalArgumentException> { YouTubeFeedUrls.canonical(id) }
        }
    }

    @Test fun everyValidMaskProducesOrderedEphemeralUrls() {
        for (mask in 1..YouTubeVariantBits.ALL) {
            val urls = YouTubeFeedUrls.pollUrls(ID, mask)
            assertEquals(listOf(1, 2, 4).filter { mask and it != 0 }, urls.map { it.bit })
            urls.forEach { assertEquals(YtRef.Channel(ID, it.bit), classifier.classify(it.url)) }
        }
        for (mask in listOf(
            0,
            -1,
            8,
            9,
        )) {
            assertFailsWith<IllegalArgumentException> { YouTubeFeedUrls.pollUrls(ID, mask) }
        }
        assertEquals(YtRef.Channel(ID), classifier.classify(YouTubeFeedUrls.canonical(ID)))
        assertEquals(YtRef.Channel(ID), classifier.classify(YouTubeFeedUrls.channelPage(ID)))
        assertEquals(YtRef.Video("dQw4w9WgXcQ"), classifier.classify(YouTubeFeedUrls.watch("dQw4w9WgXcQ")))
        assertFailsWith<IllegalArgumentException> { YouTubeFeedUrls.watch("bad") }
        assertEquals(2, YouTubeFeedUrls.CONTROL_CHANNEL_IDS.distinct().size)
        YouTubeFeedUrls.CONTROL_CHANNEL_IDS.forEach { assertTrue(YouTubeIds.CHANNEL.matches(it)) }
    }

    private companion object {
        const val ID = "UCBJycsmduvYEL83R_U4JriQ"
    }
}
