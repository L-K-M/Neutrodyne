// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.fetch.FeedSniffer
import ch.lkmc.neutrodyne.core.data.fetch.Sniff
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedSnifferTest {
    @Test
    fun `many preambles still classify the root element`() {
        val doc =
            buildString {
                append("<?xml version=\"1.0\"?>")
                repeat(200) { append("<!-- pad $it -->") }
                append("<!DOCTYPE rss>")
                append("<rss version=\"2.0\"><channel/></rss>")
            }
        assertThat(FeedSniffer.sniff(doc.encodeToByteArray())).isEqualTo(Sniff.RSS)
    }

    @Test
    fun `alternating preamble kinds keep classification`() {
        val doc =
            "<?xml version=\"1.0\"?>" +
                "<!-- a -->" +
                "<!DOCTYPE feed>" +
                "<!-- b -->" +
                "<feed xmlns=\"http://www.w3.org/2005/Atom\"/>"
        assertThat(FeedSniffer.sniff(doc.encodeToByteArray())).isEqualTo(Sniff.ATOM)
    }
}
