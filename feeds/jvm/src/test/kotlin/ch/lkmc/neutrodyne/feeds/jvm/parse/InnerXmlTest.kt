// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `InnerXml` re-serialisation rules (03 step 6): an empty non-void element keeps an explicit closing
 * tag — `<strong/>` re-serialised as bare `<strong>` would swallow the text that follows it; the HTML
 * void elements keep their bare form.
 */
class InnerXmlTest {
    private fun descriptionHtmlOf(inner: String): String? =
        XmlPullFeedParser
            .discovered()
            .parse(
                {
                    Buffer().write(
                        "<rss version=\"2.0\"><channel><item><description>$inner</description></item></channel></rss>"
                            .encodeToByteArray(),
                    )
                },
                null,
                "https://feeds.test/feed.xml",
            ).let {
                assertIs<ParseResult.Ok>(it)
                    .feed.items
                    .single()
                    .descriptionHtml
            }

    @Test
    fun emptyNonVoidElementGetsAnExplicitClose() {
        assertEquals("<strong></strong>plain", descriptionHtmlOf("<strong/>plain"))
    }

    @Test
    fun voidElementStaysBare() {
        assertEquals("a<br>b", descriptionHtmlOf("a<br/>b"))
    }
}
