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

/** Strict-pass behaviors the golden corpus does not pin: failure reasons and the opener contract. */
class XmlPullOpmlReaderTest {
    private val reader: OpmlReader = XmlPullOpmlReader.discovered()

    private fun read(xml: String): OpmlReadResult = reader.read({ Buffer().writeString(xml, Charsets.UTF_8) })

    private fun failure(xml: String): OpmlFailure = assertIs<OpmlReadResult.Failed>(read(xml)).failure

    private fun failureBytes(bytes: ByteArray): OpmlFailure =
        assertIs<OpmlReadResult.Failed>(reader.read({ Buffer().write(bytes) })).failure

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

    /**
     * 05 walk rules: `url` is the feed fallback only when the URL does not end in `.opml` — a
     * `type="rss"` outline pointing at another list is a folder, like any untyped outline.
     */
    @Test
    fun rssOutlineWithOpmlUrlIsAFolder() {
        val result =
            read(
                "<opml version=\"1.0\"><body>" +
                    "<outline type=\"rss\" url=\"https://x.example.com/list.opml\" text=\"shared\"/>" +
                    "</body></opml>",
            )
        assertIs<OpmlReadResult.Ok>(result)
        assertEquals(emptyList(), result.document.entries)
        assertEquals(listOf("shared"), result.document.folders.map { it.name })
    }

    /**
     * `encoding="UTF-16"` shifts the parser's view of the body where the byte-level guard keeps
     * its ASCII view — the `<!ENTITY` is invisible to the stream scan and is caught by the
     * DOCDECL-token text check instead. The fallback is terminal: `ENTITY_DECLARED`.
     */
    @Test
    fun entityAfterDeclaredCharsetShiftIsRejected() {
        val head = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
        val body = "<!DOCTYPE opml [<!ENTITY x \"y\">]><opml version=\"1.0\"/>"
        val bytes =
            head.toByteArray(Charsets.US_ASCII) + body.toByteArray(Charsets.UTF_16)
        assertEquals(OpmlFailure.ENTITY_DECLARED, failureBytes(bytes))
    }

    /** A declaration that lies about the body encoding fails fast — never a hang or a crash. */
    @Test
    fun misdeclaredEncodingFails() {
        val head = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
        val bytes =
            head.toByteArray(Charsets.US_ASCII) +
                "<opml version=\"1.0\"/>".toByteArray(Charsets.US_ASCII)
        assertIs<OpmlReadResult.Failed>(reader.read({ Buffer().write(bytes) }))
    }

    /** A pretty-printed `<title>` trims like folder names, so 05's gPodder title check holds. */
    @Test
    fun headTitleIsTrimmed() {
        val xml =
            "<opml version=\"1.0\"><head><title>\n    gPodder subscriptions\n  </title></head>" +
                "<body><outline xmlUrl=\"https://example.com/feed\"/></body></opml>"
        assertEquals("gPodder subscriptions", assertIs<OpmlReadResult.Ok>(read(xml)).document.headTitle)
    }

    /** `nd:groupColor` takes ASCII hex only: a sign or a non-ASCII digit is not a colour. */
    @Test
    fun groupColorRejectsSignedAndNonAsciiHex() {
        assertEquals(0xFFFFAA00.toInt(), folderColor("#FFAA00"))
        for (raw in listOf(
            "#-fffff",
            "#+fffff",
            "#-fffffff",
            "#\uFF26\uFF26\uFF26\uFF26\uFF26\uFF26",
            "#\u0660\u0660\u0660\u0660\u0660\u0660",
        )) {
            assertEquals(null, folderColor(raw), raw)
        }
    }

    private fun folderColor(raw: String): Int? {
        val xml =
            "<opml version=\"1.0\" xmlns:nd=\"urn:neutrodyne:opml:1\"><body>" +
                "<outline text=\"g\" nd:groupColor=\"$raw\"><outline xmlUrl=\"https://example.com/feed\"/></outline>" +
                "</body></opml>"
        return assertIs<OpmlReadResult.Ok>(read(xml))
            .document.folders
            .single()
            .colorArgb
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
