// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * X6: numeric character references above U+FFFF decode to the supplementary character, not the
 * single `char` kxml2's `pushEntity` truncates to (`&#x1F600;` → U+F600). The fix rewrites the
 * decoded input before the parser sees it, so a reference and the literal character are equivalent
 * in text and in attributes — GUID identity keys included. The Android binding shares this input
 * path (AOSP's KXmlParser forks the same code); `:feeds:jvm` carries no Robolectric, so coverage
 * here runs on the kxml2 binding.
 */
class SupplementaryReferencesTest {
    private val baseUrl = "https://example.com/feed.xml"

    private fun feedOf(xml: String): ParsedFeed =
        XmlPullFeedParser
            .discovered()
            .parse({ Buffer().write(xml.encodeToByteArray()) }, null, baseUrl)
            .let { assertIs<ParseResult.Ok>(it).feed }

    private fun itemXml(content: String): String =
        "<rss version=\"2.0\"><channel><item>$content</item></channel></rss>"

    @Test
    fun supplementaryReferenceInGuidMatchesLiteral() {
        assertEquals("ep-😀", feedOf(itemXml("<guid>ep-&#x1F600;</guid>")).items.single().guid)
        assertEquals(
            feedOf(itemXml("<guid>ep-😀</guid>")).items.single().guid,
            feedOf(itemXml("<guid>ep-&#x1F600;</guid>")).items.single().guid,
        )
    }

    @Test
    fun supplementaryReferenceInTitleMatchesLiteral() {
        assertEquals(
            feedOf(itemXml("<title>Show 😀</title>")).items.single().title,
            feedOf(itemXml("<title>Show &#x1F600;</title>")).items.single().title,
        )
    }

    @Test
    fun supplementaryReferenceInDescriptionMatchesLiteral() {
        assertEquals(
            feedOf(itemXml("<description>notes 😀</description>")).items.single().descriptionHtml,
            feedOf(itemXml("<description>notes &#x1F600;</description>")).items.single().descriptionHtml,
        )
    }

    /** Decimal spellings are the same reference. */
    @Test
    fun supplementaryReferenceInDecimalForm() {
        assertEquals("ep-😀", feedOf(itemXml("<guid>ep-&#128512;</guid>")).items.single().guid)
    }

    /** Attribute values decode the same way — a ref in an enclosure URL must not corrupt it. */
    @Test
    fun supplementaryReferenceInAttributeValue() {
        val feed =
            feedOf(
                itemXml(
                    "<enclosure url=\"https://cdn.test/e-&#x1F600;.mp3\" type=\"audio/mpeg\" length=\"1\"/>",
                ),
            )
        assertEquals(
            "https://cdn.test/e-😀.mp3",
            feed.items
                .single()
                .enclosures
                .single()
                .url,
        )
    }

    /** A BMP reference and an out-of-range-free neighbor stay untouched by the rewrite. */
    @Test
    fun bmpReferencesAreUnchanged() {
        assertEquals("a\u00E9b", feedOf(itemXml("<guid>a&#xE9;b</guid>")).items.single().guid)
        assertEquals("a&#b", feedOf(itemXml("<guid>a&amp;#b</guid>")).items.single().guid)
    }
}
