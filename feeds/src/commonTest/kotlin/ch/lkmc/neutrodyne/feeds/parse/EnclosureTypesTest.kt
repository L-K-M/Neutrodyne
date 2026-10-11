// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.parse

import ch.lkmc.neutrodyne.feeds.model.Enclosure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The alias table, extension inference and primary-enclosure rule of 03 Enclosure types. */
class EnclosureTypesTest {
    @Test
    fun aliasesMapToCanonicalForms() {
        assertEquals("audio/mpeg", EnclosureTypes.effective("audio/mp3", "https://e.example/x.bin"))
        assertEquals("audio/mpeg", EnclosureTypes.effective("audio/x-mp3", "https://e.example/x.bin"))
        assertEquals("audio/mpeg", EnclosureTypes.effective("audio/mpeg3", "https://e.example/x.bin"))
        assertEquals("audio/mpeg", EnclosureTypes.effective("AUDIO/X-MPEG", "https://e.example/x.bin"))
        assertEquals("audio/mp4", EnclosureTypes.effective("audio/x-m4a", "https://e.example/x.bin"))
        assertEquals("audio/m4b", EnclosureTypes.effective("audio/m4b", "https://e.example/x.bin"))
        assertEquals("audio/aac", EnclosureTypes.effective("audio/x-aac", "https://e.example/x.bin"))
        assertEquals("audio/ogg", EnclosureTypes.effective("application/ogg", "https://e.example/x.bin"))
        assertEquals("audio/wav", EnclosureTypes.effective("audio/x-wav", "https://e.example/x.bin"))
        assertEquals("video/mp4", EnclosureTypes.effective("video/x-m4v", "https://e.example/x.bin"))
    }

    @Test
    fun hlsAliases() {
        assertEquals("application/x-mpegurl", EnclosureTypes.effective("application.x-mpegurl", "https://e.example/x"))
        assertEquals(
            "application/x-mpegurl",
            EnclosureTypes.effective("application/vnd.apple.mpegurl", "https://e.example/x"),
        )
        assertEquals(
            "application/x-mpegurl",
            EnclosureTypes.effective("application/x-mpegurl", "https://e.example/x"),
        )
    }

    @Test
    fun parametersAreStripped() {
        assertEquals("audio/mpeg", EnclosureTypes.effective("audio/mpeg; charset=binary", "https://e.example/x"))
    }

    @Test
    fun declaredTypeWinsOverExtension() {
        // A declared playable type is authoritative; the URL extension only fills in when the
        // declared type is absent or unusable.
        assertEquals("video/mp4", EnclosureTypes.effective("video/mp4", "https://e.example/a.mp3"))
        assertEquals("audio/mpeg", EnclosureTypes.effective("audio/mpeg", "https://e.example/a.mp4"))
    }

    @Test
    fun unknownTypeInfersFromExtension() {
        assertEquals("audio/mpeg", EnclosureTypes.effective("application/octet-stream", "https://e.example/a.mp3"))
        assertEquals("audio/mp4", EnclosureTypes.effective(null, "https://e.example/a.m4a"))
        assertEquals("audio/aac", EnclosureTypes.effective("bogus", "https://e.example/a.aac"))
        assertEquals("audio/ogg", EnclosureTypes.effective(null, "https://e.example/a.oga"))
        assertEquals("audio/opus", EnclosureTypes.effective(null, "https://e.example/a.opus"))
        assertEquals("audio/flac", EnclosureTypes.effective(null, "https://e.example/a.flac"))
        assertEquals("video/quicktime", EnclosureTypes.effective(null, "https://e.example/a.mov"))
        assertEquals("video/webm", EnclosureTypes.effective(null, "https://e.example/a.webm"))
        assertEquals(
            "application/x-mpegurl",
            EnclosureTypes.effective("application/octet-stream", "https://e.example/a.m3u8?sig=1"),
        )
        // Plain `.m3u` maps onto the same HLS family as `.m3u8` (an undecorated playlist URL
        // with no declared type is the common shape).
        assertEquals("application/x-mpegurl", EnclosureTypes.effective(null, "https://e.example/a.m3u"))
    }

    @Test
    fun extensionCaseInsensitiveAndQueryStripped() {
        assertEquals("audio/mpeg", EnclosureTypes.effective(null, "https://e.example/A.MP3?token=x"))
        // Some CDNs emit the query percent-encoded; decode-then-cut still finds the extension.
        assertEquals("audio/mp4", EnclosureTypes.effective(null, "https://cdn.example/ep1.m4a%3Ftoken%3Dx"))
        // A decoded `?` inside a path segment must not hide the real extension: the raw path
        // is sniffed first, the decoded form only as fallback.
        assertEquals("audio/mp4", EnclosureTypes.effective(null, "https://cdn.example/ep%3Fa.m4a?token=1"))
        assertEquals("video/mp4", EnclosureTypes.effective(null, "https://cdn.example/a.mp4%23x"))
        // Sign-prefixed escapes are not hex: %+2F and %-1 pass through instead of decoding.
        assertNull(EnclosureTypes.effective(null, "https://cdn.example/ep%+2Fm4a"))
        assertNull(EnclosureTypes.effective(null, "https://cdn.example/ep%-1m4a"))
        // A percent-encoded dot is still a dot for extension sniffing.
        assertEquals("audio/mpeg", EnclosureTypes.effective(null, "https://cdn.example/file%2Emp3"))
        // A bad escape passes through harmlessly.
        assertNull(EnclosureTypes.effective(null, "https://cdn.example/file%zzmp3"))
    }

    @Test
    fun nothingKnownYieldsNull() {
        assertNull(EnclosureTypes.effective(null, "https://e.example/download"))
        // A declared but unplayable type survives (stored, never chosen as primary enclosure).
        assertEquals("text/html", EnclosureTypes.effective("text/html", "https://e.example/page"))
        val html = Enclosure("https://e.example/page", "text/html", null, "text/html")
        val unknown = Enclosure("https://e.example/x", null, null, null)
        assertNull(EnclosureTypes.primary(listOf(html, unknown)))
    }

    @Test
    fun isVideo() {
        assertTrue(EnclosureTypes.isVideo("video/mp4"))
        assertFalse(EnclosureTypes.isVideo("audio/mpeg"))
        assertFalse(EnclosureTypes.isVideo(null))
    }

    @Test
    fun primaryPrefersAudioThenVideoThenHls() {
        val audio = Enclosure("https://e.example/a.mp3", "audio/mpeg", 1, "audio/mpeg")
        val video = Enclosure("https://e.example/v.mp4", "video/mp4", 2, "video/mp4")
        val hls = Enclosure("https://e.example/s.m3u8", null, null, "application/x-mpegurl")
        val unknown = Enclosure("https://e.example/x", null, null, null)

        assertSame(video, EnclosureTypes.primary(listOf(video, hls, unknown)))
        assertSame(hls, EnclosureTypes.primary(listOf(hls, unknown)))
        assertSame(audio, EnclosureTypes.primary(listOf(video, audio)))
        // The adjacent pair this rule exists for: audio must beat the HLS playlist.
        assertSame(audio, EnclosureTypes.primary(listOf(audio, hls)))
        assertNull(EnclosureTypes.primary(listOf(unknown)))
        assertNull(EnclosureTypes.primary(emptyList()))
        // Equal precedence: document order decides, not length or anything else.
        val audioLong = Enclosure("https://e.example/b.mp3", "audio/mpeg", 2, "audio/mpeg")
        assertSame(audio, EnclosureTypes.primary(listOf(audio, audioLong)))
        assertSame(audioLong, EnclosureTypes.primary(listOf(audioLong, audio)))
    }

    @Test
    fun emptySubtypeFallsBackToExtension() {
        // "audio/" carries no subtype: it must not satisfy the audio-family guard in [primary]
        // (the KDoc promises unplayable declared types are never chosen as primary).
        assertEquals("audio/mpeg", EnclosureTypes.effective("audio/", "https://e.example/a.mp3"))
        assertNull(EnclosureTypes.effective("audio/", "https://e.example/download"))
        // The stored effectiveType is effective()'s output — null here, so nothing is picked.
        val bogusAudio =
            Enclosure(
                "https://e.example/download",
                "audio/",
                null,
                EnclosureTypes.effective("audio/", "https://e.example/download"),
            )
        assertNull(EnclosureTypes.primary(listOf(bogusAudio)))
        // Same hole on the video side.
        val bogusVideo =
            Enclosure(
                "https://e.example/v",
                "video/",
                null,
                EnclosureTypes.effective("video/", "https://e.example/v"),
            )
        assertNull(EnclosureTypes.primary(listOf(bogusVideo)))
    }

    @Test
    fun legacyM3uTypesAreHlsNotAudio() {
        // Common legacy m3u MIME types map onto the HLS family, so a playlist-only item lands on
        // the HLS fallback instead of being picked as primary audio.
        assertEquals(
            "application/x-mpegurl",
            EnclosureTypes.effective("audio/x-mpegurl", "https://e.example/a.m3u"),
        )
        assertEquals(
            "application/x-mpegurl",
            EnclosureTypes.effective("audio/mpegurl", "https://e.example/a"),
        )
        val m3u =
            Enclosure(
                "https://e.example/a.m3u",
                "audio/x-mpegurl",
                null,
                EnclosureTypes.effective("audio/x-mpegurl", "https://e.example/a.m3u"),
            )
        assertSame(m3u, EnclosureTypes.primary(listOf(m3u)))
        val audio = Enclosure("https://e.example/a.mp3", "audio/mpeg", 1, "audio/mpeg")
        assertSame(audio, EnclosureTypes.primary(listOf(m3u, audio)))
    }
}
