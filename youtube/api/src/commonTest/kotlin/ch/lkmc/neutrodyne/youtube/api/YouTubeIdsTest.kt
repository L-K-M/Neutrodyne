// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 04's ID validators and the length-based uploads-playlist mapping. */
class YouTubeIdsTest {
    @Test
    fun channelValidator() {
        assertTrue(YouTubeIds.CHANNEL.matches(CHANNEL))
        assertFalse(YouTubeIds.CHANNEL.matches(CHANNEL.dropLast(1)))
        assertFalse(YouTubeIds.CHANNEL.matches(CHANNEL.dropLast(1) + "B"))
        assertFalse(YouTubeIds.CHANNEL.matches("UC" + "a".repeat(22)))
        assertFalse(YouTubeIds.CHANNEL.matches("uc$SUFFIX"))
    }

    @Test
    fun videoValidator() {
        assertTrue(YouTubeIds.VIDEO.matches(VIDEO))
        assertFalse(YouTubeIds.VIDEO.matches(VIDEO.dropLast(1)))
        assertFalse(YouTubeIds.VIDEO.matches(VIDEO + "x"))
    }

    @Test
    fun playlistValidator() {
        assertTrue(YouTubeIds.PLAYLIST.matches("PL6566A39B68523E18"))
        assertTrue(YouTubeIds.PLAYLIST.matches("OLAK5uy_" + "k".repeat(33)))
        assertTrue(YouTubeIds.PLAYLIST.matches("UU$SUFFIX"))

        assertFalse(YouTubeIds.PLAYLIST.matches("short"))
        assertFalse(YouTubeIds.PLAYLIST.matches("PL has space"))
        assertFalse(YouTubeIds.PLAYLIST.matches("PL" + "a".repeat(63)))
    }

    @Test
    fun uploadsToChannelByLength() {
        assertEquals(CHANNEL to 7, YouTubeIds.uploadsToChannel("UU$SUFFIX"))
        assertEquals(CHANNEL to 1, YouTubeIds.uploadsToChannel("UULF$SUFFIX"))
        assertEquals(CHANNEL to 2, YouTubeIds.uploadsToChannel("UUSH$SUFFIX"))
        assertEquals(CHANNEL to 4, YouTubeIds.uploadsToChannel("UULV$SUFFIX"))
        assertEquals(CHANNEL to null, YouTubeIds.uploadsToChannel("UUMO$SUFFIX"))
        assertEquals(CHANNEL to null, YouTubeIds.uploadsToChannel("UUPS$SUFFIX"))

        // 24 chars: "UULF…" is the plain uploads list of the "UCLF…" channel.
        val plainTwentyFour = "UULF" + "BJycsmduvYEL83R_U4JQ"
        assertEquals("UCLFBJycsmduvYEL83R_U4JQ" to 7, YouTubeIds.uploadsToChannel(plainTwentyFour))

        assertNull(YouTubeIds.uploadsToChannel("PL6566A39B68523E18"))
        assertNull(YouTubeIds.uploadsToChannel("UU" + "a".repeat(22)))

        // Only the "UU" uploads family converts; same-length IDs of other families stay playlists.
        assertNull(YouTubeIds.uploadsToChannel("PL$SUFFIX"))
        assertNull(YouTubeIds.uploadsToChannel("RD$SUFFIX"))
        assertNull(YouTubeIds.uploadsToChannel("PLxx$SUFFIX"))

        // An unrecognized 26-char "UU" variant still maps, with no hint (like UUMO/UUPS).
        assertEquals(CHANNEL to null, YouTubeIds.uploadsToChannel("UUXY$SUFFIX"))
    }

    @Test
    fun channelFromFeedLevel() {
        assertEquals(CHANNEL, YouTubeIds.channelFromFeedLevel(SUFFIX))
        assertEquals(CHANNEL, YouTubeIds.channelFromFeedLevel(CHANNEL))

        // Corrupt feed data fails fast instead of minting a bogus channel ID.
        assertFailsWith<IllegalStateException> { YouTubeIds.channelFromFeedLevel("a".repeat(22)) }
        assertFailsWith<IllegalStateException> { YouTubeIds.channelFromFeedLevel("UC" + "a".repeat(22)) }
        assertFailsWith<IllegalStateException> { YouTubeIds.channelFromFeedLevel("bad") }
    }

    private companion object {
        const val CHANNEL = "UCBJycsmduvYEL83R_U4JriQ"
        const val SUFFIX = "BJycsmduvYEL83R_U4JriQ"
        const val VIDEO = "dQw4w9WgXcQ"
    }
}
