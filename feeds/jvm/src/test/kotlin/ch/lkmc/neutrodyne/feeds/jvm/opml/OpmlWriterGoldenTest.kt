// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.opml.ExportDocument
import ch.lkmc.neutrodyne.feeds.opml.ExportFeed
import ch.lkmc.neutrodyne.feeds.opml.ExportGroup
import ch.lkmc.neutrodyne.feeds.opml.OpmlLayout
import ch.lkmc.neutrodyne.feeds.opml.OpmlWriter
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The deterministic OPML writer's golden corpus (05 Testing, AC9 writer leg): grouped, flat,
 * single-group, empty-group, escaping/percent-encoding and invalid-character output, each pinned to a
 * golden under `feeds/src/test/resources/opml/writer/` (`./gradlew :feeds:jvm:test -PupdateGoldens`
 * rewrites them; refused on CI).
 */
class OpmlWriterGoldenTest {
    @Test
    fun groupedMatchesGolden() = assertGolden("grouped.golden.opml", library(), OpmlLayout.GROUPED)

    @Test
    fun flatMatchesGolden() = assertGolden("flat.golden.opml", library(), OpmlLayout.FLAT)

    @Test
    fun singleGroupMatchesGolden() = assertGolden("single_group.golden.opml", singleGroup(), OpmlLayout.GROUPED)

    @Test
    fun emptyGroupsMatchGolden() = assertGolden("empty_groups.golden.opml", emptyGroups(), OpmlLayout.GROUPED)

    @Test
    fun escapingMatchesGolden() = assertGolden("escaping.golden.opml", escaping(), OpmlLayout.GROUPED)

    @Test
    fun invalidCharactersMatchGolden() = assertGolden("invalid_chars.golden.opml", invalidChars(), OpmlLayout.GROUPED)

    /** Equal input sorts identically regardless of the caller's feed order (AC9 determinism). */
    @Test
    fun feedOrderDoesNotChangeOutput() {
        val document = library()
        val reversed = document.copy(feeds = document.feeds.reversed())
        assertEquals(render(document, OpmlLayout.GROUPED), render(reversed, OpmlLayout.GROUPED))
        assertEquals(render(document, OpmlLayout.FLAT), render(reversed, OpmlLayout.FLAT))
    }

    private fun assertGolden(
        name: String,
        document: ExportDocument,
        layout: OpmlLayout,
    ) {
        val actual = render(document, layout)
        assertEquals(actual, render(document, layout), "writer output is not deterministic for $name")
        Goldens.assertTextMatches(name, actual)
    }

    private fun render(
        document: ExportDocument,
        layout: OpmlLayout,
    ): String {
        val buffer = Buffer()
        OpmlWriter.write(buffer, document, layout)
        return buffer.readUtf8()
    }

    /** The 05 Structure example: three groups (one empty), a grouped, a YouTube and an ungrouped feed. */
    private fun library() =
        ExportDocument(
            title = "Neutrodyne subscriptions",
            createdAt = CREATED_AT,
            groups =
                listOf(
                    ExportGroup("g-tech", "tech", 0xFF2196F3.toInt(), "memory"),
                    ExportGroup("g-news", "news", 0xFFF44336.toInt(), null),
                    ExportGroup("g-fiction", "fiction", 0xFF673AB7.toInt(), "auto_stories"),
                ),
            feeds =
                listOf(
                    ExportFeed(
                        displayTitle = "ATP",
                        feedTitle = "Accidental Tech Podcast",
                        xmlUrl = "https://atp.fm/rss",
                        htmlUrl = "https://atp.fm",
                        language = null,
                        groupKeys = listOf("g-tech", "g-news"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "Marques Brownlee",
                        feedTitle = "Marques Brownlee",
                        xmlUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ",
                        htmlUrl = "https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ",
                        language = null,
                        groupKeys = listOf("g-tech"),
                        youtube = true,
                        ytVariants = 1,
                    ),
                    ExportFeed(
                        displayTitle = "The Daily",
                        feedTitle = "The Daily",
                        xmlUrl = "https://feeds.example.com/daily",
                        htmlUrl = null,
                        language = null,
                        groupKeys = listOf("g-news"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "Some Show",
                        feedTitle = "Some Show",
                        xmlUrl = "https://example.com/rss",
                        htmlUrl = null,
                        language = null,
                        groupKeys = emptyList(),
                        youtube = false,
                        ytVariants = null,
                    ),
                ),
        )

    /** 05 Single-group export: one folder, `category` names that group only. */
    private fun singleGroup() =
        ExportDocument(
            title = "Neutrodyne: tech",
            createdAt = CREATED_AT,
            groups = listOf(ExportGroup("g-tech", "tech", 0xFF2196F3.toInt(), "memory")),
            feeds =
                listOf(
                    ExportFeed(
                        displayTitle = "Marques Brownlee",
                        feedTitle = "Marques Brownlee",
                        xmlUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ",
                        htmlUrl = "https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ",
                        language = null,
                        groupKeys = listOf("g-tech"),
                        youtube = true,
                        ytVariants = 5,
                    ),
                    ExportFeed(
                        displayTitle = "ATP",
                        feedTitle = "Accidental Tech Podcast",
                        xmlUrl = "https://atp.fm/rss",
                        htmlUrl = "https://atp.fm",
                        language = null,
                        groupKeys = listOf("g-tech"),
                        youtube = false,
                        ytVariants = null,
                    ),
                ),
        )

    private fun emptyGroups() =
        ExportDocument(
            title = "Neutrodyne subscriptions",
            createdAt = CREATED_AT,
            groups =
                listOf(
                    ExportGroup("g-fiction", "fiction", 0xFF673AB7.toInt(), "auto_stories"),
                    ExportGroup("g-plain", "plain", null, null),
                ),
            feeds = emptyList(),
        )

    /** Escaping, emoji/ZWJ, `,`/`/`/`%` category encoding and private-URL userinfo. */
    private fun escaping() =
        ExportDocument(
            title = "Neutrodyne subscriptions",
            createdAt = CREATED_AT,
            groups =
                listOf(
                    ExportGroup("g-comma", "a,b", null, null),
                    ExportGroup("g-slash", "c/d", null, null),
                    ExportGroup("g-percent", "100%", null, null),
                    ExportGroup("g-literal", "a%2Cb", null, null),
                    ExportGroup("g-special", "Rock & Roll <\"quotes\"> 'single'", 0xFFFFFFFF.toInt(), "star"),
                ),
            feeds =
                listOf(
                    ExportFeed(
                        displayTitle = "apple",
                        feedTitle = "apple",
                        xmlUrl = "https://example.com/a",
                        htmlUrl = null,
                        language = null,
                        groupKeys = listOf("g-comma", "g-percent"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "Apple",
                        feedTitle = "Apple",
                        xmlUrl = "https://example.com/b",
                        htmlUrl = null,
                        language = null,
                        groupKeys = listOf("g-comma"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "Line\nBreak\tTab",
                        feedTitle = "Quoted \"Title\" & <more>",
                        xmlUrl = "https://example.com/x?a=1&b=2",
                        htmlUrl = "https://example.com/x?a=1&b=2",
                        language = "en-GB",
                        groupKeys = listOf("g-slash", "g-literal"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "Private",
                        feedTitle = "Private",
                        xmlUrl = "https://user:p%40ss@example.com/rss",
                        htmlUrl = null,
                        language = null,
                        groupKeys = listOf("g-special"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "🎧 Commute 👩\u200D💻 ❤\uFE0F",
                        feedTitle = "Commute",
                        xmlUrl = "https://example.com/emoji",
                        htmlUrl = null,
                        language = null,
                        groupKeys = emptyList(),
                        youtube = false,
                        ytVariants = null,
                    ),
                ),
        )

    /** U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, U+FFFE and lone surrogates are stripped; pairs survive. */
    private fun invalidChars() =
        ExportDocument(
            title = "Neutrodyne subscriptions",
            createdAt = CREATED_AT,
            groups = listOf(ExportGroup("g-invalid", "Inv\u0000alid\u0008Name\uFFFE", null, null)),
            feeds =
                listOf(
                    ExportFeed(
                        displayTitle = "T\u0000i\u000Btle\u000C",
                        feedTitle = "Feed\u000E\u001F",
                        xmlUrl = "https://example.com/\uD800rss",
                        htmlUrl = null,
                        language = null,
                        groupKeys = listOf("g-invalid"),
                        youtube = false,
                        ytVariants = null,
                    ),
                    ExportFeed(
                        displayTitle = "Lone\uDC00Low",
                        feedTitle = "Emoji \uD83C\uDFA7",
                        xmlUrl = "https://example.com/emoji",
                        htmlUrl = null,
                        language = null,
                        groupKeys = emptyList(),
                        youtube = false,
                        ytVariants = null,
                    ),
                ),
        )

    private companion object {
        /** Sun, 04 Oct 2026 21:30:00 GMT, the date in 05's example. */
        const val CREATED_AT = 1791149400000L
    }
}
