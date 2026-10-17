// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.opml.OpmlFailure
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import ch.lkmc.neutrodyne.feeds.opml.OpmlWarningCode
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Strict-pass behaviors the golden corpus does not pin: failure reasons and the opener contract. */
class XmlPullOpmlReaderTest {
    private val reader: OpmlReader = XmlPullOpmlReader.discovered()

    private fun read(xml: String): OpmlReadResult = reader.read({ Buffer().writeString(xml, Charsets.UTF_8) })

    private fun failure(xml: String): OpmlFailure = (read(xml) as OpmlReadResult.Failed).failure

    @Test
    fun emptyDocumentHasNoOutlines() {
        assertEquals(OpmlFailure.NO_OUTLINES, failure("<opml version=\"1.0\"/>"))
    }

    @Test
    fun nonOpmlRootHasNoOutlines() {
        assertEquals(OpmlFailure.NO_OUTLINES, failure("<rss version=\"2.0\"><channel/></rss>"))
    }

    @Test
    fun malformedXmlFailsMalformed() {
        assertEquals(OpmlFailure.MALFORMED, failure("<opml><body><outline"))
    }

    @Test
    fun entityDeclarationFails() {
        assertEquals(
            OpmlFailure.ENTITY_DECLARED,
            failure("<!DOCTYPE opml [<!ENTITY x \"y\">]><opml version=\"1.0\"/>"),
        )
    }

    /** A bare DOCTYPE without entity declarations is not hostile; it just holds no outlines. */
    @Test
    fun plainDoctypeParses() {
        assertEquals(OpmlFailure.NO_OUTLINES, failure("<!DOCTYPE opml><opml version=\"1.0\"/>"))
    }

    /**
     * An attribute over `maxAttrChars` drops its outline with a warning (05 walk rules), leaving
     * the rest of the document readable; a dropped folder's children re-parent upwards.
     */
    @Test
    fun oversizedAttributeDropsOnlyItsOutline() {
        val big = "b".repeat(9_000)
        val xml =
            "<opml version=\"1.0\"><body>" +
                "<outline text=\"folder\">" +
                "<outline type=\"rss\" text=\"heavy\" xmlUrl=\"https://heavy.example.com/rss\" note=\"$big\"/>" +
                "<outline type=\"rss\" text=\"light\" xmlUrl=\"https://light.example.com/rss\"/>" +
                "</outline>" +
                "</body></opml>"
        val result = read(xml)
        assertIs<OpmlReadResult.Ok>(result)
        assertEquals(1, result.document.entries.size)
        assertEquals("https://light.example.com/rss", result.document.entries[0].url)
        assertEquals(
            listOf(OpmlWarningCode.ATTRIBUTE_TOO_LONG),
            result.document.warnings.map { it.code },
        )
    }

    /** `open` is invoked exactly once per pass — the strict pass. */
    @Test
    fun openerIsCalledOnce() {
        var opens = 0
        reader.read({
            opens++
            Buffer().writeString("<opml version=\"1.0\"/>", Charsets.UTF_8)
        })
        assertEquals(1, opens)
    }
}
