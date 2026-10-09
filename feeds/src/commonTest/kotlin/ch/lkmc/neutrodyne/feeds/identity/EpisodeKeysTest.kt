// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import ch.lkmc.neutrodyne.feeds.model.Enclosure
import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import ch.lkmc.neutrodyne.feeds.model.TranscriptRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every key kind of 03 Episode keys and matching helpers, with fixed vectors. */
class EpisodeKeysTest {
    private fun episode(
        guid: String? = null,
        enclosureUrl: String? = null,
        title: String? = null,
        pubDate: Long? = null,
        link: String? = null,
        description: String? = null,
        descriptionIsHtml: Boolean = false,
    ): ParsedEpisode {
        val enclosure = enclosureUrl?.let { Enclosure(it, "audio/mpeg", 1, "audio/mpeg") }
        return ParsedEpisode(
            feedOrder = 0,
            guid = guid,
            title = title,
            pubDate = pubDate,
            descriptionHtml = description,
            descriptionIsHtml = descriptionIsHtml,
            link = link,
            enclosures = listOfNotNull(enclosure),
            primaryEnclosure = enclosure,
        )
    }

    @Test
    fun guidKeyIsVerbatimTrimmed() {
        assertEquals("g:yt:video:3iRUwVzRDZQ", EpisodeKeys.primary(episode(guid = "  yt:video:3iRUwVzRDZQ ")))
        assertEquals("g:https://example.com/?p=123", EpisodeKeys.primary(episode(guid = "https://example.com/?p=123")))
    }

    @Test
    fun enclosureKeyUsesNormalisedUrl() {
        val key = EpisodeKeys.primary(episode(enclosureUrl = "HTTPS://CDN.Example.com:443/pod/a.mp3?x=1"))
        // The query is part of the enclosure identity, not stripped.
        assertEquals("u:cdn.example.com/pod/a.mp3?x=1", key)
    }

    @Test
    fun titleDayKeyIsSha1OfLowercaseTitleAndUtcDay() {
        // 2026-10-03T12:34:56Z → UTC day 2026-10-03; sha1("episode 42|2026-10-03T00:00:00Z").
        val key = EpisodeKeys.titleDayKey(" Episode 42 ", 1791030896000L)
        assertEquals("t:1f46eba7092e57135e3aef7b6570849f3712b5c1", key)
        assertEquals(key, EpisodeKeys.primary(episode(title = "Episode 42", pubDate = 1791030896000L)))
        // 23:59:59Z is still the same UTC day; a non-UTC default timezone would bucket it to Oct 4.
        assertEquals(key, EpisodeKeys.titleDayKey("Episode 42", 1791071999000L))
    }

    @Test
    fun titleDayKeyNeedsTitleAndDate() {
        assertNull(EpisodeKeys.titleDayKey("Episode 42", null))
        assertNull(EpisodeKeys.titleDayKey("", 1791030896000L))
        assertNull(EpisodeKeys.titleDayKey(null, 1791030896000L))
    }

    @Test
    fun linkKey() {
        val key = EpisodeKeys.primary(episode(link = "https://example.com/ep-1"))
        assertEquals("l:cdbce1b074f7cf4b346ffdb683fc78363bd903e6", key)
    }

    @Test
    fun headKeyAlwaysApplies() {
        val key = EpisodeKeys.primary(episode())
        assertTrue(key.startsWith("h:"))
        assertEquals(42, key.length)
    }

    @Test
    fun headKeySeparatesTitleFromDescription() {
        // "AB"+"C…" and "A"+"BC…" must not hash to the same h: key.
        assertNotEquals(
            EpisodeKeys.primary(episode(title = "AB", description = "C")),
            EpisodeKeys.primary(episode(title = "A", description = "BC")),
        )
    }

    @Test
    fun headKeySeparatorInsideTitleCannotForgeBoundary() {
        // U+001F can reach a title/description in practice: the lenient pull parsers emit it
        // for &#x1F; despite XML 1.0. It must not let a field forge the separator position.
        assertNotEquals(
            EpisodeKeys.primary(episode(title = "A", description = "B\u001FC")),
            EpisodeKeys.primary(episode(title = "A\u001FB", description = "C")),
        )
        // A separator run must not slide across the boundary either: doubling alone encodes
        // both of these pairs as A + 3×U+001F + B, so the escape needs its own prefix.
        assertNotEquals(
            EpisodeKeys.primary(episode(title = "A\u001F", description = "B")),
            EpisodeKeys.primary(episode(title = "A", description = "\u001F\u001FB")),
        )
        assertNotEquals(
            EpisodeKeys.primary(episode(title = "A\u001F\u001F", description = "B")),
            EpisodeKeys.primary(episode(title = "A", description = "\u001F\u001FB")),
        )
        // A literal backslash-run must not conflate with an escaped separator.
        assertNotEquals(
            EpisodeKeys.primary(episode(title = "A\\u001F", description = "B")),
            EpisodeKeys.primary(episode(title = "A\u001F", description = "B")),
        )
        // The stored-key path hashes the same escaped input.
        assertEquals(
            EpisodeKeys.primary(episode(title = "A\u001FB", description = "C")),
            EpisodeKeys.keyFor(KeyInput(title = "A\u001FB", descriptionHead = "C"), 1),
        )
    }

    @Test
    fun precedenceGuidEnclosureTitleLinkHead() {
        val both = episode(guid = "g-1", enclosureUrl = "https://example.com/a.mp3")
        assertEquals("g:g-1", EpisodeKeys.primary(both))
        assertTrue(EpisodeKeys.primary(episode(enclosureUrl = "https://example.com/a.mp3")).startsWith("u:"))
        // Title-day outranks link when neither guid nor enclosure is present.
        val titleAndLink = episode(title = "T", pubDate = 1791030896000L, link = "https://example.com/ep-1")
        assertTrue(EpisodeKeys.primary(titleAndLink).startsWith("t:"))
    }

    @Test
    fun fallbacksAreUrlAndTitleDayMinusPrimary() {
        val e = episode(guid = "g-1", enclosureUrl = "https://example.com/a.mp3", title = "T", pubDate = 1791030896000L)
        val fallbacks = EpisodeKeys.fallbacks(e)
        assertEquals(2, fallbacks.size)
        assertTrue(fallbacks[0].startsWith("u:"))
        assertTrue(fallbacks[1].startsWith("t:"))

        // A u-primary item's fallbacks drop the u key.
        val uPrimary = episode(enclosureUrl = "https://example.com/a.mp3", title = "T", pubDate = 1791030896000L)
        assertEquals(listOf(EpisodeKeys.titleDayKey("T", 1791030896000L)), EpisodeKeys.fallbacks(uPrimary))

        // No u:/t: candidate → no fallbacks; the h: key is never a fallback.
        assertTrue(EpisodeKeys.fallbacks(episode()).isEmpty())
        assertTrue(EpisodeKeys.fallbacks(episode(guid = "g-1")).isEmpty())
    }

    @Test
    fun nonPrimaryEnclosureIsNotTheKeySource() {
        // Only the primary enclosure feeds u:; a stray non-primary one must not (03 Episode keys).
        val pdf = Enclosure("https://e.example/notes.pdf", "application/pdf", 1, "application/pdf")
        val e =
            ParsedEpisode(
                feedOrder = 0,
                title = "Episode 42",
                pubDate = 1791030896000L,
                enclosures = listOf(pdf),
                primaryEnclosure = null,
            )
        assertEquals("t:1f46eba7092e57135e3aef7b6570849f3712b5c1", EpisodeKeys.primary(e))
    }

    @Test
    fun candidatesArePrimaryOnlyInV1() {
        val e = episode(guid = "g-1")
        assertEquals(listOf(EpisodeKeys.primary(e)), EpisodeKeys.candidates(e))
    }

    @Test
    fun keyForFollowsTheSameLadder() {
        val input = KeyInput(guid = null, enclosureUrl = "https://example.com/a.mp3")
        assertEquals(
            EpisodeKeys.primary(episode(enclosureUrl = "https://example.com/a.mp3")),
            EpisodeKeys.keyFor(input, 1),
        )
    }

    @Test
    fun keyForRejectsUnknownVersions() {
        assertFailsWith<IllegalArgumentException> { EpisodeKeys.keyFor(KeyInput(), 2) }
    }

    @Test
    fun versionOfParsesNumericPrefix() {
        assertEquals(1, EpisodeKeys.versionOf("g:x"))
        assertEquals(1, EpisodeKeys.versionOf("u:example.com/a"))
        assertEquals(2, EpisodeKeys.versionOf("2g:x"))
        assertEquals(10, EpisodeKeys.versionOf("10u:example.com/a"))
    }

    @Test
    fun contentHashExcludesFeedOrder() {
        val enclosure = Enclosure("https://example.com/a.mp3", "audio/mpeg", 1, "audio/mpeg")
        val base =
            ParsedEpisode(
                feedOrder = 0,
                guid = "g",
                title = "T",
                enclosures = listOf(enclosure),
                primaryEnclosure = enclosure,
            )
        assertEquals(EpisodeContentHash.of(base), EpisodeContentHash.of(base.copy(feedOrder = 7)))
        assertNotEquals(EpisodeContentHash.of(base), EpisodeContentHash.of(base.copy(title = "T2")))
    }

    @Test
    fun contentHashCoversRawPubDateSeparately() {
        // rawPubDate is a stored column: a raw-text change at the same instant must flip the hash.
        val base = episode(pubDate = 1791030896000L).copy(rawPubDate = "Sat, 03 Oct 2026 12:34:56 GMT")
        assertNotEquals(
            EpisodeContentHash.of(base),
            EpisodeContentHash.of(base.copy(rawPubDate = "2026-10-03T12:34:56Z")),
        )
    }

    @Test
    fun contentHashSeparatesChaptersPair() {
        // A "|" inside chaptersUrl must not let the pair collide with a different split.
        assertNotEquals(
            EpisodeContentHash.of(episode().copy(chaptersUrl = "a|b")),
            EpisodeContentHash.of(episode().copy(chaptersUrl = "a", chaptersType = "b")),
        )
    }

    @Test
    fun contentHashSeparatorInsideFieldCannotForgeBoundary() {
        // Free-text fields join by U+001F; a separator inside one must not forge neighbours'
        // positions (feeds can inject it via &#x1F; — see headKeySeparatorInsideTitleCannotForgeBoundary).
        assertNotEquals(
            EpisodeContentHash.of(episode(title = "A").copy(rawPubDate = "B\u001F\u001FD")),
            EpisodeContentHash.of(episode(title = "A\u001F\u001FB").copy(rawPubDate = "D")),
        )
        // The list separator U+001E inside a leaf must not forge an extra item boundary.
        assertNotEquals(
            EpisodeContentHash.of(
                episode().copy(transcripts = listOf(TranscriptRef("a\u001F\u001F\u001F\u001Eb"))),
            ),
            EpisodeContentHash.of(
                episode().copy(transcripts = listOf(TranscriptRef("a"), TranscriptRef("b"))),
            ),
        )
    }

    /** W10: text-vs-HTML interpretation alone changes the hash — the description bytes need not. */
    @Test
    fun contentHashCoversDescriptionIsHtml() {
        val text = episode(description = "<b>Hello</b>")
        val html = episode(description = "<b>Hello</b>", descriptionIsHtml = true)
        assertNotEquals(EpisodeContentHash.of(text), EpisodeContentHash.of(html))
    }
}
