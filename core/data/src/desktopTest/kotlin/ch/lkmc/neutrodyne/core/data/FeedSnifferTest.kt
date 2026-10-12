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

    @Test
    fun `namespace-prefixed roots classify on the local name`() {
        // The parser matches the root's local name, so a prefixed Atom document
        // must not read as OTHER.
        assertThat(
            FeedSniffer.sniff(
                "<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\"/>".encodeToByteArray(),
            ),
        ).isEqualTo(Sniff.ATOM)
        assertThat(
            FeedSniffer.sniff(
                "<a:feed xmlns:a=\"http://www.w3.org/2005/Atom\"/>".encodeToByteArray(),
            ),
        ).isEqualTo(Sniff.ATOM)
        assertThat(
            FeedSniffer.sniff(
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>"
                    .encodeToByteArray(),
            ),
        ).isEqualTo(Sniff.RDF)
    }

    @Test
    fun `root names longer than the keyword are not feeds`() {
        assertThat(FeedSniffer.sniff("<rssfoo><channel/></rssfoo>".encodeToByteArray()))
            .isEqualTo(Sniff.OTHER)
        assertThat(FeedSniffer.sniff("<feeder/>".encodeToByteArray())).isEqualTo(Sniff.OTHER)
    }
}
