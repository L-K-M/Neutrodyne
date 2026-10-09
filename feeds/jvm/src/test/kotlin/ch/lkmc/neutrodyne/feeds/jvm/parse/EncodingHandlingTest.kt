// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Charset detection and the re-parse heuristic (03 Parser setup and charset steps 2–5): BOM and
 * declaration detection, canonical charset comparison and the HTTP-charset / windows-1252 fallback
 * when the first pass leaves U+FFFD debris.
 */
class EncodingHandlingTest {
    private val baseUrl = "https://example.com/feed.xml"

    /** `windows1252-http-charset.xml` declares `utf-8` but carries raw cp1252 bytes (`Caf\xE9`). */
    @Test
    fun httpCharsetWinsWhenItDiffersFromDetected() {
        val feed = parseOk("windows1252-http-charset.xml", httpCharset = "windows-1252")

        assertEquals("Café", feed.title)
        assertTrue(feed.warnings.any { it.code == WarningCode.CHARSET_REPARSED })
    }

    /**
     * The S12 case: HTTP also claims UTF-8, so the HTTP charset cannot win; the fallback re-parse
     * with windows-1252 must still fix the mojibake.
     */
    @Test
    fun utf8HttpCharsetFallsBackToWindows1252() {
        val feed = parseOk("windows1252-http-charset.xml", httpCharset = "UTF-8")

        assertEquals("Café", feed.title)
        assertTrue(feed.warnings.any { it.code == WarningCode.CHARSET_REPARSED })
    }

    @Test
    fun heuristicReparseWithoutHttpCharset() {
        val feed = parseOk("windows1252-http-charset.xml", httpCharset = null)

        assertEquals("Café", feed.title)
        assertTrue(feed.warnings.any { it.code == WarningCode.CHARSET_REPARSED })
    }

    @Test
    fun utf16BomIsDetected() {
        val feed = parseOk("utf16-bom.xml", httpCharset = null)

        assertEquals("UTF-16 LE BOM Show", feed.title)
        assertTrue(feed.warnings.none { it.code == WarningCode.CHARSET_REPARSED })
    }

    @Test
    fun latin1DeclarationIsHonoured() {
        val feed = parseOk("latin1-declared.xml", httpCharset = null)

        assertEquals("Latin eins: Öl und Käse", feed.title)
        assertTrue(feed.warnings.none { it.code == WarningCode.CHARSET_REPARSED })
    }

    private fun parseOk(
        fixture: String,
        httpCharset: String?,
    ) = XmlPullFeedParser
        .discovered()
        .parse({ Buffer().write(Goldens.fixture(fixture).readBytes()) }, httpCharset, baseUrl)
        .let { assertIs<ParseResult.Ok>(it).feed }
}
