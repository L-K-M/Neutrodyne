// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Every row of 04's Input grammar, plus host spoofing, percent-encoding and a seeded fuzz pass. */
class YouTubeUrlClassifierTest {
    private val classifier = YouTubeUrlClassifier()

    @Test
    fun bareForms() {
        assertClassifies("@mkbhd", YtRef.Handle("@mkbhd"))
        assertClassifies("  @some.name  ", YtRef.Handle("@some.name"))
        assertClassifies("@under_score", YtRef.Handle("@under_score"))
        assertClassifies("@hy-phen", YtRef.Handle("@hy-phen"))
        assertClassifies(CHANNEL, YtRef.Channel(CHANNEL))

        assertClassifies("@", null)
        assertClassifies(CHANNEL.dropLast(1), null)
        assertClassifies(CHANNEL.dropLast(1) + "B", null)
        assertClassifies("plain text", null)
        assertClassifies("", null)
    }

    @Test
    fun acceptedHosts() {
        assertClassifies("https://youtube.com/@x", YtRef.Handle("@x"))
        assertClassifies("https://www.youtube.com/@x", YtRef.Handle("@x"))
        assertClassifies("https://m.youtube.com/@x", YtRef.Handle("@x"))
        assertClassifies("https://music.youtube.com/@x", YtRef.Handle("@x"))
        assertClassifies("https://youtube-nocookie.com/embed/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("https://www.youtube-nocookie.com/embed/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("http://youtu.be/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("HTTPS://WWW.YOUTUBE.COM/@x", YtRef.Handle("@x"))
        // The /@handle row of 04's table precedes youtu.be/{id} under first-match-wins.
        assertClassifies("https://youtu.be/@mrbeast", YtRef.Handle("@mrbeast"))
    }

    @Test
    fun channelUrls() {
        assertClassifies("https://www.youtube.com/channel/$CHANNEL", YtRef.Channel(CHANNEL))
        assertClassifies("https://m.youtube.com/channel/$CHANNEL/videos", YtRef.Channel(CHANNEL))
        assertClassifies("https://www.youtube.com/channel/$CHANNEL/", YtRef.Channel(CHANNEL))

        assertClassifies("https://www.youtube.com/channel/invalid", null)
        assertClassifies("https://www.youtube.com/channel/", null)
        assertClassifies("https://www.youtube.com/channel/${CHANNEL.dropLast(1)}B", null)
    }

    @Test
    fun feedUrls() {
        assertClassifies("https://www.youtube.com/feeds/videos.xml?channel_id=$CHANNEL", YtRef.Channel(CHANNEL))
        assertClassifies("https://www.youtube.com/feeds/videos.xml?channel_id=$CHANNEL&si=abc", YtRef.Channel(CHANNEL))
        assertClassifies(
            "https://www.youtube.com/feeds/videos.xml?user=marquesbrownlee",
            YtRef.LegacyPath("user/marquesbrownlee"),
        )

        assertClassifies("https://www.youtube.com/feeds/videos.xml?channel_id=bad", null)
        assertClassifies("https://www.youtube.com/feeds/videos.xml", null)
        assertClassifies("https://www.youtube.com/feeds/videos.xml?user=${"a".repeat(101)}", null)
    }

    @Test
    fun playlistUrls() {
        val playlist = "PL6566A39B68523E18"
        val musicAlbum = "OLAK5uy_" + "k".repeat(33)

        assertClassifies("https://www.youtube.com/playlist?list=$playlist", YtRef.Playlist(playlist))
        assertClassifies("https://www.youtube.com/feeds/videos.xml?playlist_id=$playlist", YtRef.Playlist(playlist))
        assertClassifies("https://music.youtube.com/playlist?list=$musicAlbum", YtRef.Playlist(musicAlbum))

        assertClassifies("https://www.youtube.com/playlist", null)
        assertClassifies("https://www.youtube.com/playlist?list=", null)
    }

    @Test
    fun uploadsPlaylistsBecomeChannels() {
        assertClassifies("https://www.youtube.com/playlist?list=UU$SUFFIX", YtRef.Channel(CHANNEL, 7))
        assertClassifies("https://www.youtube.com/feeds/videos.xml?playlist_id=UULF$SUFFIX", YtRef.Channel(CHANNEL, 1))
        assertClassifies("https://www.youtube.com/playlist?list=UUSH$SUFFIX", YtRef.Channel(CHANNEL, 2))
        assertClassifies("https://www.youtube.com/playlist?list=UULV$SUFFIX", YtRef.Channel(CHANNEL, 4))
        assertClassifies("https://www.youtube.com/playlist?list=UUMO$SUFFIX", YtRef.Channel(CHANNEL))

        // 24 chars: "UULF…" is the plain uploads list of the "UCLF…" channel.
        val plainTwentyFour = "UULF" + "BJycsmduvYEL83R_U4JQ"
        assertClassifies(
            "https://www.youtube.com/playlist?list=$plainTwentyFour",
            YtRef.Channel("UCLFBJycsmduvYEL83R_U4JQ", 7),
        )

        // Only the "UU" uploads family converts; other families of the same lengths stay playlists.
        assertClassifies("https://www.youtube.com/playlist?list=PL$SUFFIX", YtRef.Playlist("PL$SUFFIX"))
        assertClassifies(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=PLxx$SUFFIX",
            YtRef.Playlist("PLxx$SUFFIX"),
        )
    }

    @Test
    fun legacyPaths() {
        assertClassifies("https://www.youtube.com/c/mkbhd", YtRef.LegacyPath("c/mkbhd"))
        assertClassifies("https://www.youtube.com/user/marquesbrownlee", YtRef.LegacyPath("user/marquesbrownlee"))
        assertClassifies("https://www.youtube.com/c/mkbhd/videos", YtRef.LegacyPath("c/mkbhd"))
        assertClassifies("https://www.youtube.com/mkbhd", YtRef.LegacyPath("mkbhd"))
        assertClassifies("https://www.youtube.com/some.name_1-2", YtRef.LegacyPath("some.name_1-2"))

        assertClassifies("https://www.youtube.com/c/", null)
        assertClassifies("https://www.youtube.com/results?search_query=x", null)
        assertClassifies("https://www.youtube.com/hashtag/foo", null)

        // Legacy names share the 100-character cap of handles and custom names.
        assertClassifies("https://www.youtube.com/c/${"a".repeat(100)}", YtRef.LegacyPath("c/${"a".repeat(100)}"))
        assertClassifies("https://www.youtube.com/c/${"a".repeat(101)}", null)
        assertClassifies("https://www.youtube.com/user/${"a".repeat(101)}", null)
    }

    @Test
    fun videoUrls() {
        assertClassifies("https://www.youtube.com/watch?v=$VIDEO", YtRef.Video(VIDEO))
        assertClassifies(
            "https://www.youtube.com/watch?v=$VIDEO&list=PL6566A39B68523E18&index=2",
            YtRef.Video(VIDEO),
        )
        assertClassifies("https://www.youtube.com/shorts/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("https://www.youtube.com/live/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("https://www.youtube.com/embed/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("https://www.youtube.com/v/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("https://www.youtube.com/e/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("https://youtu.be/$VIDEO?si=xyz", YtRef.Video(VIDEO))

        assertClassifies("https://www.youtube.com/watch?v=short", null)
        assertClassifies("https://www.youtube.com/watch", null)

        // 04's table maps /watch only through a valid v=; a lone list= is not a subscribable form.
        assertClassifies("https://www.youtube.com/watch?list=PL6566A39B68523E18", null)
    }

    @Test
    fun trackingParametersAreDropped() {
        assertClassifies("https://www.youtube.com/watch?v=$VIDEO&feature=share&t=30", YtRef.Video(VIDEO))
        assertClassifies("https://youtu.be/$VIDEO?si=abc&pp=xyz", YtRef.Video(VIDEO))
        assertClassifies("https://www.youtube.com/@handle?si=abc", YtRef.Handle("@handle"))
        assertClassifies(
            "https://www.youtube.com/playlist?list=PL6566A39B68523E18&utm_source=x",
            YtRef.Playlist("PL6566A39B68523E18"),
        )
    }

    @Test
    fun schemeLessAndWrappedInputs() {
        assertClassifies("youtube.com/@x", YtRef.Handle("@x"))
        assertClassifies("www.youtube.com/watch?v=$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("youtu.be/$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("m.youtube.com/@x", YtRef.Handle("@x"))
        assertClassifies("feed:https://www.youtube.com/@x", YtRef.Handle("@x"))
        assertClassifies("view-source:https://www.youtube.com/channel/$CHANNEL", YtRef.Channel(CHANNEL))
        assertClassifies("feed:youtube.com/@x", YtRef.Handle("@x"))
        assertClassifies("youtu.be/@mrbeast", YtRef.Handle("@mrbeast"))

        // A "://" inside a query value must not be mistaken for a scheme marker.
        assertClassifies("youtube.com/watch?continue=https://accounts.google.com&v=$VIDEO", YtRef.Video(VIDEO))
        assertClassifies("youtube.com:8080/watch?v=$VIDEO", YtRef.Video(VIDEO))
    }

    @Test
    fun spoofedHostsAreRejected() {
        assertClassifies("https://youtube.com.evil.example/@x", null)
        assertClassifies("https://www.youtube.com.evil.example/@x", null)
        assertClassifies("https://evil.example/youtube.com/@x", null)
        assertClassifies("https://notyoutube.com/@x", null)
        assertClassifies("https://youtube.co/@x", null)
        assertClassifies("https://m.youtube-nocookie.com/@x", null)
        assertClassifies("youtube.com.evil.example/@x", null)

        // Userinfo tricks: the host is what follows the LAST '@', so only the real YouTube host passes.
        assertClassifies("https://youtube.com@evil.example/@x", null)
        assertClassifies("https://evil.example@youtube.com/@x", YtRef.Handle("@x"))

        // The host is never percent-decoded.
        assertClassifies("https://youtube%2Ecom/@x", null)

        // WHATWG treats '\' as '/' in http(s) URLs, so it ends the authority: the host is evil.com.
        assertClassifies("https://evil.com\\@youtube.com/watch?v=$VIDEO", null)

        // Explicit ports are stripped before host matching; a spoofed host stays rejected.
        assertClassifies("https://www.youtube.com:443/@x", YtRef.Handle("@x"))
        assertClassifies("https://youtube.com.evil.example:443/@x", null)

        // A trailing dot is a different host string, and a non-http(s) scheme is never prepended over.
        assertClassifies("https://youtube.com./@x", null)
        assertClassifies("foo://youtube.com/@x", null)
    }

    @Test
    fun percentEncodedPathsAreDecoded() {
        assertClassifies("https://www.youtube.com/@%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82", YtRef.Handle("@привет"))
        assertClassifies("https://www.youtube.com/c/%E4%BD%A0%E5%A5%BD", YtRef.LegacyPath("c/你好"))
        assertClassifies("https://www.youtube.com/%40handle", YtRef.Handle("@handle"))
        assertClassifies("https://www.youtube.com/@e%CC%81", YtRef.Handle("@\u00E9"))

        assertClassifies("https://www.youtube.com/@a%2Fb", null)
    }

    @Test
    fun mixedRawAndEncodedSupplementaryChars() {
        // U+2000B is a UTF-16 surrogate pair; raw spans must encode to UTF-8 whole, not per half.
        val supplementary = "\uD840\uDC0B"
        assertClassifies(
            "https://www.youtube.com/@${supplementary}a%62",
            YtRef.Handle("@${supplementary}ab"),
        )
        assertClassifies(
            "https://www.youtube.com/c/${supplementary}a%62",
            YtRef.LegacyPath("c/${supplementary}ab"),
        )

        // Controls: fully raw and fully escaped forms already classified correctly.
        assertClassifies("https://www.youtube.com/@${supplementary}ab", YtRef.Handle("@${supplementary}ab"))
        assertClassifies("https://www.youtube.com/@%F0%A0%80%8Bab", YtRef.Handle("@${supplementary}ab"))

        // Invalid escapes stay literal and malformed UTF-8 still decodes to U+FFFD.
        assertClassifies("https://www.youtube.com/@x%2", YtRef.Handle("@x%2"))
        assertClassifies("https://www.youtube.com/@%FF", YtRef.Handle("@\uFFFD"))
    }

    @Test
    fun classifyOrQueryFallsBackToQuery() {
        assertEquals(YtRef.Handle("@x"), classifier.classifyOrQuery("@x"))
        assertEquals(YtRef.Query("marques brownlee"), classifier.classifyOrQuery("  marques brownlee  "))
        assertEquals(YtRef.Query("https://example.com/@x"), classifier.classifyOrQuery("https://example.com/@x"))
        assertEquals(YtRef.Query(""), classifier.classifyOrQuery(""))
    }

    @Test
    fun isYouTubeHostDetectsHosts() {
        assertHost("https://www.youtube.com/results?search_query=x", true)
        assertHost("youtube.com/@x", true)
        assertHost("https://youtu.be/$VIDEO", true)
        assertHost("https://www.youtube-nocookie.com/embed/$VIDEO", true)

        assertHost("https://www.youtube.com.evil.example/@x", false)
        assertHost("https://example.com/@x", false)
        assertHost("@handle", false)
        assertHost(CHANNEL, false)
        assertHost("", false)

        // isYouTubeHost shares classify's host parsing: last-'@' userinfo, no percent-decoding, exact hosts.
        assertHost("https://youtube.com@evil.example/@x", false)
        assertHost("https://evil.example@youtube.com/@x", true)
        assertHost("https://youtube%2Ecom/@x", false)
        assertHost("https://evil.com\\@youtube.com/@x", false)
        assertHost("https://www.youtube.com:443/@x", true)
        assertHost("https://youtube.com./@x", false)
        assertHost("youtube.com/redirect?q=https://x.com", true)
        assertHost("foo://youtube.com/@x", false)
    }

    /**
     * 09's seeded robustness pass: 200 deterministic random strings must never throw, and any
     * non-null [YtRef] must satisfy its own validator. Half the inputs are prefixed with a real host
     * so the URL path is exercised too.
     */
    @Test
    fun seededRandomStringsNeverThrow() {
        val random = Random(RANDOM_SEED)
        repeat(RANDOM_STRINGS) { index ->
            val prefix = if (index % 2 == 0) "https://www.youtube.com/" else ""
            val input = prefix + randomString(random, random.nextInt(RANDOM_MAX_LENGTH))

            when (val ref = classifier.classify(input)) {
                is YtRef.Channel -> assertTrue(YouTubeIds.CHANNEL.matches(ref.id), "bad channel for <$input>")
                is YtRef.Video -> assertTrue(YouTubeIds.VIDEO.matches(ref.id), "bad video for <$input>")
                is YtRef.Handle -> assertTrue(ref.handle.startsWith('@'), "bad handle for <$input>")
                is YtRef.LegacyPath -> assertTrue(ref.path.isNotEmpty(), "bad legacy path for <$input>")
                is YtRef.Playlist -> assertTrue(ref.id.isNotEmpty(), "bad playlist for <$input>")
                is YtRef.Query -> fail("classify must never return Query, got <$input>")
                null -> Unit
            }

            classifier.isYouTubeHost(input)
        }
    }

    private fun randomString(
        random: Random,
        length: Int,
    ): String =
        buildString(length) {
            repeat(length) { append(RANDOM_CHARS[random.nextInt(RANDOM_CHARS.length)]) }
        }

    private fun assertClassifies(
        input: String,
        expected: YtRef?,
    ) = assertEquals(expected, classifier.classify(input), "classify(<$input>)")

    private fun assertHost(
        input: String,
        expected: Boolean,
    ) = assertEquals(expected, classifier.isYouTubeHost(input), "isYouTubeHost(<$input>)")

    private companion object {
        const val CHANNEL = "UCBJycsmduvYEL83R_U4JriQ"
        const val SUFFIX = "BJycsmduvYEL83R_U4JriQ"
        const val VIDEO = "dQw4w9WgXcQ"
        const val RANDOM_SEED = 20261017L
        const val RANDOM_STRINGS = 200
        const val RANDOM_MAX_LENGTH = 64

        /** Hosts, delimiters, percent escapes, spaces, control characters, non-ASCII and a lone surrogate. */
        val RANDOM_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789%:/?#&=._-@ \t\n\u0000" +
                "абвгдеёжзий你好世界éé\ud83d\ude00\u005C\uD800"
    }
}
