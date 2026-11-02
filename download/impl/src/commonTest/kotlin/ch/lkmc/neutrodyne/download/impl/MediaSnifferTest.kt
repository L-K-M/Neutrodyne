// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSnifferTest {
    @Test
    fun generatedSignaturesWinOverWrongMimeTypes() {
        val samples =
            mapOf(
                MediaKind.MP3 to "ID3",
                MediaKind.M4B to "\u0000\u0000\u0000\u0018ftypM4B ",
                MediaKind.MP4 to "\u0000\u0000\u0000\u0018ftypisom",
                MediaKind.OGG to "OggS",
                MediaKind.FLAC to "fLaC",
                MediaKind.WAV to "RIFF1234WAVE",
                MediaKind.WEBM to "\u001aEß£",
            )
        for ((kind, text) in samples) {
            val bytes =
                if (kind ==
                    MediaKind.WEBM
                ) {
                    byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())
                } else {
                    text.encodeToByteArray()
                }
            assertEquals(kind, MediaSniffer.classify(bytes, "text/html"))
        }
        assertEquals(MediaKind.AAC, MediaSniffer.classify(byteArrayOf(0xff.toByte(), 0xf1.toByte()), null))
        assertEquals(MediaKind.MP3, MediaSniffer.classify(byteArrayOf(0xff.toByte(), 0xfb.toByte()), null))
    }

    @Test
    fun textualErrorsAndUnknownBinaryAreDistinct() {
        for ((text, mime) in listOf(
            "\uFEFF \n<html>" to null,
            "{}" to "application/json",
            "[]" to "application/problem+json",
            "login" to "text/plain",
            "login" to "application/xhtml+xml",
            "" to null,
        )) {
            assertEquals(MediaKind.NOT_MEDIA, MediaSniffer.classify(text.encodeToByteArray(), mime))
        }
        assertEquals(MediaKind.HLS, MediaSniffer.classify(" \n#EXTM3U".encodeToByteArray(), "audio/mpeg"))
        assertEquals(MediaKind.UNKNOWN_BINARY, MediaSniffer.classify(byteArrayOf(1, 2, 3), "application/octet-stream"))
    }

    @Test
    fun extensionUsesMagicThenMimeThenUrlAndNeverQuery() {
        assertEquals("mp3", MediaSniffer.extension(MediaKind.MP3, byteArrayOf(), "video/mp4", "a.mov", true))
        assertEquals("m4a", MediaSniffer.extension(MediaKind.MP4, byteArrayOf(), null, "a.mp3", false))
        assertEquals("mp4", MediaSniffer.extension(MediaKind.MP4, byteArrayOf(), "video/mp4", "a.mp3", false))
        val opus = ByteArray(36).also { "OpusHead".encodeToByteArray().copyInto(it, 28) }
        assertEquals("opus", MediaSniffer.extension(MediaKind.OGG, opus, null, "a.ogg", false))
        assertEquals(
            "flac",
            MediaSniffer.extension(MediaKind.UNKNOWN_BINARY, byteArrayOf(), "audio/flac", "a.mp3", false),
        )
        assertEquals(
            "m4b",
            MediaSniffer.extension(MediaKind.UNKNOWN_BINARY, byteArrayOf(), null, "https://a/file.M4B?x=.mp3", false),
        )
        assertEquals(
            "bin",
            MediaSniffer.extension(MediaKind.UNKNOWN_BINARY, byteArrayOf(), null, "https://a/path?x=.mp3", false),
        )
    }
}
