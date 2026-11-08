// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpAudioMapperTest {
    private val now = TestClock.DEFAULT_NOW

    private fun map(
        dto: YtxResolve = wire(),
        pref: AudioPref = AudioPref(),
    ) = YtDlpAudioMapper.map(dto, VIDEO, pref, now)

    @Test fun recordedShimResultHasExactUnitsAndMemoryOnlyUrl() {
        val dto = YtDlpClient.decode(recordedResolve(), YtxResolve.serializer())
        val audio = map(dto)
        assertEquals("140", audio.formatId)
        assertEquals(128000, audio.averageBitrate)
        assertEquals(1000L, audio.contentLength)
        assertEquals(123L, audio.lastModifiedMicros)
        assertEquals(120000L, audio.durationMs)
        assertEquals(4102444800000L, audio.expiresAtMs)
        assertEquals(1792368000000L, audio.availableAtMs)
        assertEquals(IpFamily.V4, audio.ipFamily)
        assertEquals(now, audio.resolvedAtMs)
        assertFalse(audio.toString().contains("sig="))
        assertFalse(dto.toString().contains("sig="))
    }

    @Test fun identityDistinguishesDrcAndTrackDespiteSameItag() {
        val plain = wireFormat(tags = "lang=en:acont=original")
        val drc = wireFormat(suffix = "-drc", tags = "lang=en:acont=original")
        val dub = wireFormat(tags = "lang=de:acont=dubbed-auto")
        val dto = wire().copy(formats = listOf(plain, drc, dub))
        assertEquals("140~en.original", map(dto).formatId)
        val stable = map(dto, AudioPref(preferDrc = true))
        assertEquals("140-drc~en.original", stable.formatId)
        assertEquals("en.original", stable.audioTrackId)
        assertTrue(stable.isDrc)
        assertEquals("140-drc", map(wire(drc)).formatId)
    }

    @Test fun untypedTrackCannotAliasLanguageNamedUntyped() {
        val plain = wireFormat()
        val language = wireFormat(extra = "&xtags=lang%3Duntyped").copy(language = "untyped")
        val dto = wire().copy(formats = listOf(plain, language))
        val unspecified = map(dto)
        val typed = map(dto, AudioPref(preferredLanguage = "untyped"))
        assertNull(unspecified.audioTrackId)
        assertEquals("untyped", typed.audioTrackId)
        assertNotEquals(unspecified.formatId, typed.formatId)
    }

    @Test fun urlTagsMimeIpv6AndPrerollAreDecoded() {
        val f =
            wireFormat().copy(
                url =
                    "https://media.invalid/a?itag=140&mime=audio%2Fmp4&ip=2001%3Adb8%3A%3A1&" +
                        "xtags=lang%3Dfr%3Aacont%3Doriginal",
                availableAt = 1800000000.5,
            )
        val audio = map(wire(f))
        assertEquals("fr.original", audio.audioTrackId)
        assertEquals(IpFamily.V6, audio.ipFamily)
        assertEquals(1800000000500, audio.availableAtMs)
        assertEquals(now + ResolvedUrlCache.MAX_AGE_MS, audio.expiresAtMs)
        assertNull(audio.contentLength)
        assertNull(audio.lastModifiedMicros)
    }

    @Test fun languagePreferenceAndFilesizeFallback() {
        val original =
            wireFormat(139).copy(
                languagePreference = 10,
                language = "en",
                tbr = 48.5,
                abr = null,
                filesize = 54321,
                url = "https://media.invalid/a?itag=139",
            )
        val dub = wireFormat(140, tags = "lang=en:acont=dubbed")
        val audio = map(wire().copy(formats = listOf(dub, original)))
        assertEquals(139, audio.itag)
        assertEquals(48500, audio.averageBitrate)
        assertEquals(54321, audio.contentLength)
        assertEquals(120250, audio.durationMs)
    }

    @Test fun malformedUrlAndNumericMetadataFailClosedWithoutInputInException() {
        val base = wireFormat()
        val bad =
            listOf(
                base.copy(url = "https://private@media.invalid/a?itag=140"),
                base.copy(url = base.url + "#private"),
                base.copy(url = base.url + "&itag=251"),
                base.copy(url = base.url.replace("itag=140", "itag=251")),
                base.copy(url = base.url.replace("clen=1000", "clen=-1")),
                base.copy(url = base.url.replace("lmt=123", "lmt=bad")),
                base.copy(url = base.url.replace("expire=4102444800", "expire=9223372036854775807")),
                base.copy(url = base.url.replace("expire=4102444800", "expire=1")),
                base.copy(url = base.url.replace("ip=192.0.2.1", "ip=evil")),
                base.copy(url = base.url + "&mime=%zz"),
                base.copy(url = base.url.replace("ip=192.0.2.1", "ip=2001%3Adb8%3A1")),
                base.copy(url = base.url.replace("ip=192.0.2.1", "ip=1%3A2%3A3%3A4%3A5%3A6%3A7%3A8%3A9")),
                base.copy(formatId = "140-unknown"),
                base.copy(url = base.url.replace("ip=192.0.2.1", "ip=192.0.2.1%3A%3A")),
                base.copy(
                    url = base.url.replace("https://media.example.invalid", "https://media.example.invalid:99999"),
                ),
                base.copy(acodec = "mp4a.https://private.invalid"),
                base.copy(abr = Double.NaN),
                base.copy(availableAt = Double.POSITIVE_INFINITY),
                base.copy(xtags = "lang=https://private:acont=original"),
            )
        for ((index, format) in bad.withIndex()) {
            val failure = assertFailsWith<YtxCallException>("Invalid format $index") { map(wire(format)) }
            assertEquals("EXTRACTION", failure.code)
            assertFalse(failure.stackTraceToString().contains("private"))
        }
    }

    @Test fun onlyHttpAudioCodecAndMimeAreEligible() {
        val base = wireFormat()
        for (bad in listOf(
            base.copy(protocol = "m3u8_native"),
            base.copy(protocol = "sabr"),
            base.copy(acodec = "none"),
            base.copy(url = base.url + "&mime=video%2Fmp4"),
            base.copy(url = base.url.replace("https://", "file://")),
        )) {
            assertFailsWith<YtxCallException> { map(wire(bad)) }
        }
        assertEquals("audio/webm", map(wire(base.copy(ext = "webm", acodec = "opus"))).mimeType)
        assertEquals(140, map(wire(base.copy(protocol = "http", url = base.url.replace("https://", "http://")))).itag)
    }

    @Test fun mismatchedIdentityAndUnboundedFormatsCannotProduceAudio() {
        assertFailsWith<YtxCallException> { map(wire(video = OTHER_VIDEO)) }
        assertFailsWith<YtxCallException> { map(wire().copy(formats = List(257) { wireFormat() })) }
        assertFailsWith<YtxCallException> { map(wire().copy(duration = -1.0)) }
    }

    @Test fun boundedDecoderRejectsMalformedDeepAndOversizedDataWithoutLeaks() {
        val valid = wire().json()
        for (text in listOf(
            "{secret",
            "null",
            "{}",
            valid.replace("120.25", "\"secret\""),
            "[".repeat(33) + "]".repeat(33),
            " ".repeat(YtxLimits.RESULT_BYTES + 1),
            "é".repeat(YtxLimits.RESULT_BYTES / 2 + 1),
            valid.dropLast(1) + ",\"unknown\":true}",
        )) {
            val failure = assertFailsWith<YtxCallException> { YtDlpClient.decode(text, YtxResolve.serializer()) }
            assertEquals("EXTRACTION", failure.code)
            assertFalse(failure.toString().contains("secret"))
        }
        // Escaped brackets are data, not nesting, and must not trigger the depth guard.
        val facts = YtxFacts(VIDEO, description = "[".repeat(100) + "\\\"" + "]".repeat(100))
        assertEquals(facts, YtDlpClient.decode(Json.encodeToString(facts), YtxFacts.serializer()))
        assertFalse(wire().copy(availability = "https://secret.invalid").toString().contains("secret"))
    }
}
