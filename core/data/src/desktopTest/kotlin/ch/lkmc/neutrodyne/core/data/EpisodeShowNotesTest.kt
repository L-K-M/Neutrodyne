// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.repo.EpisodeRepositoryImpl
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end show-notes rendering through the real parser, ingestor and repository (03 Show
 * notes): the parser's `descriptionIsHtml` must survive storage so an Atom `type="text"` body is
 * shown literally, and the lazily-decoded note cache must key on every input the sanitised output
 * depends on — the description blob *and* the episode's link base.
 */
class EpisodeShowNotesTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val ingestor = newIngestor(db, clock)
    private val repo = EpisodeRepositoryImpl(db, JsoupShowNotesSanitizer(), clock)
    private var podcastId = 0L

    @Test
    fun atomTextContentStaysLiteral() =
        runTest {
            ingest(
                atom("Use &lt;b&gt;literal&lt;/b&gt; and &amp;copy; stays text.", "text", LINK),
                IngestMode.INITIAL,
            )
            val eid = episodeId()

            // The snippet already honours the parser's flag; the stored blob must too — the tag
            // and the entity render literally, never as markup.
            assertEquals("Use <b>literal</b> and &copy; stays text.", db.episodeDao().byId(eid)!!.snippet)
            assertEquals("Use <b>literal</b> and &copy; stays text.", repo.observeShowNotes(eid).first()!!.text())
        }

    @Test
    fun atomHtmlContentRendersMarkup() =
        runTest {
            ingest(atom("&lt;p&gt;See &lt;b&gt;bold&lt;/b&gt;&lt;/p&gt;", "html", LINK), IngestMode.INITIAL)
            val notes = repo.observeShowNotes(episodeId()).first()!!

            assertEquals("See bold", notes.text())
            val styled =
                notes.blocks
                    .filterIsInstance<ShowNoteBlock.Paragraph>()
                    .flatMap { it.spans }
                    .filterIsInstance<ShowNoteSpan.Text>()
                    .any { it.style and ShowNotesStyles.BOLD != 0 }
            assertTrue(styled, "the <b> run keeps its style")
        }

    @Test
    fun aTextToHtmlFlipOnIdenticalTextRerenders() =
        runTest {
            // `type="text"` stores `<b>bold</b>` as literal text …
            ingest(atom("&lt;b&gt;bold&lt;/b&gt;", "text", LINK), IngestMode.INITIAL)
            val eid = episodeId()
            assertEquals("<b>bold</b>", repo.observeShowNotes(eid).first()!!.text())

            // … and `type="html"` stores the same text as markup — the flip must re-render.
            ingest(atom("<b>bold</b>", "html", LINK))
            assertEquals("bold", repo.observeShowNotes(eid).first()!!.text())
        }

    @Test
    fun anEpisodeLinkChangeResolvesNotesAgainstTheNewBase() =
        runTest {
            val raw = "&lt;p&gt;&lt;a href=\"related.html\"&gt;More&lt;/a&gt;&lt;/p&gt;"
            ingest(atom(raw, "html", "https://show.example/old/index.html"), IngestMode.INITIAL)
            val eid = episodeId()
            assertEquals(
                listOf("https://show.example/old/related.html"),
                repo.observeShowNotes(eid).first()!!.links(),
            )

            // Only the episode link moves; the description blob is byte-identical, so the cached
            // note is a hit on the blob alone — the base must be part of the cache match.
            ingest(atom(raw, "html", "https://show.example/new/index.html"))

            assertEquals(
                listOf("https://show.example/new/related.html"),
                repo.observeShowNotes(eid).first()!!.links(),
            )
        }

    @Test
    fun aPreCodecBlobStillDecodesAsHtml() =
        runTest {
            ingest(atom("&lt;i&gt;i&lt;/i&gt;", "html", LINK), IngestMode.INITIAL)
            val eid = episodeId()

            // A blob written before the codec existed (or any unrecognised header) keeps the
            // whole-payload UTF-8 fallback — and it has always meant markup (02 codec).
            db.ingestDao().replaceChildren(
                episodeId = eid,
                description = "<b>legacy</b>".encodeToByteArray(),
                transcripts = emptyList(),
                altEnclosures = emptyList(),
                persons = emptyList(),
                funding = emptyList(),
                pscChapters = emptyList(),
            )

            assertEquals("legacy", repo.observeShowNotes(eid).first()!!.text())
        }

    private suspend fun podcast(): Long {
        if (podcastId == 0L) podcastId = seedPodcast(db, FEED_URL)
        return podcastId
    }

    private suspend fun ingest(
        feed: ParsedFeed,
        mode: IngestMode = IngestMode.REFRESH,
    ) {
        ingestor.ingest(
            dueFeedOf(db, podcast()),
            feed,
            IngestContext(mode = mode, partial = false, fetch = fetchMeta()),
        )
    }

    private suspend fun episodeId(): Long =
        db
            .ingestDao()
            .existing(podcast())
            .single()
            .id

    private fun atom(
        content: String,
        contentType: String,
        link: String,
    ): ParsedFeed {
        val xml =
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>Show</title><entry><id>notes</id>""" +
                """<title>Notes</title><link rel="alternate" href="$link"/>""" +
                """<link rel="enclosure" href="https://cdn.example/notes.mp3" type="audio/mpeg"/>""" +
                """<content type="$contentType">$content</content></entry></feed>"""
        val result = XmlPullFeedParser.discovered().parse({ Buffer().writeUtf8(xml) }, null, FEED_URL)
        return (result as ParseResult.Ok).feed
    }

    private fun ShowNotes.text(): String =
        blocks
            .filterIsInstance<ShowNoteBlock.Paragraph>()
            .flatMap { it.spans }
            .joinToString("") {
                when (it) {
                    is ShowNoteSpan.Text -> it.text
                    is ShowNoteSpan.Link -> it.text
                    is ShowNoteSpan.Timestamp -> it.text
                    ShowNoteSpan.LineBreak -> "\n"
                }
            }

    private fun ShowNotes.links(): List<String> =
        blocks
            .filterIsInstance<ShowNoteBlock.Paragraph>()
            .flatMap { it.spans }
            .filterIsInstance<ShowNoteSpan.Link>()
            .map { it.url }

    private companion object {
        const val FEED_URL = "https://show.example/feed"
        const val LINK = "https://show.example/ep/1"
    }
}
