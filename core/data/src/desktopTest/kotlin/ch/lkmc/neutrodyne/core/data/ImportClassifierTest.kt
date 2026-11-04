// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.importing.ImportClassifier
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.groups.GroupNames
import ch.lkmc.neutrodyne.feeds.importing.ImportEntry
import ch.lkmc.neutrodyne.youtube.api.YouTubeUrlClassifier
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ImportClassifier` (05 §3): in-file dedupe, already-subscribed matching by `feedKey`/alias,
 * invalid URLs, YouTube routing (unsupported until M8), preselect and group-name shaping.
 * The existing-library cases run on a real in-memory Room db — feedKey/alias matching is the
 * classifier's one non-pure lookup.
 */
class ImportClassifierTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val classifier = ImportClassifier(db, YouTubeUrlClassifier())

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun youtubeOnlyFormatsRejectForeignRssUrls() =
        runTest {
            val result =
                classifier.classify(
                    listOf(entry("https://rss.example/feed")),
                    ImportClassifier.InputMode.YOUTUBE_ONLY,
                )
            val item = result.items.single()
            assertEquals(ImportItemStatus.INVALID_URL, item.status)
            assertNull(item.normalizedUrl)
            assertTrue(!item.selected)
        }

    @Test
    fun normalisesFeedUrlsToCanonicalAndIdentity() =
        runTest {
            val items =
                classify(
                    entry("feed:https://atp.fm/rss"),
                    entry("pcast://feeds.example.com/daily"),
                    entry("itpc://www.example.com/show"),
                    entry("example.org/flat"),
                )

            assertEquals(
                listOf(
                    "https://atp.fm/rss",
                    "https://feeds.example.com/daily",
                    "https://www.example.com/show",
                    "https://example.org/flat",
                ),
                items.map { it.normalizedUrl },
            )
            assertTrue(items.all { it.status == ImportItemStatus.PREVIEW && it.selected })
        }

    @Test
    fun invalidUrlsAreMarkedAndNeverSelected() =
        runTest {
            val items =
                classify(
                    entry("not a url at all"),
                    entry("javascript:alert(1)"),
                    entry("https://"),
                )

            assertTrue(items.all { it.status == ImportItemStatus.INVALID_URL })
            assertTrue(items.all { !it.selected && it.normalizedUrl == null })
        }

    @Test
    fun userinfoStaysInOriginalUrlOnly() =
        runTest {
            val items = classify(entry("https://user:secret@feeds.example.com/premium.xml"))

            assertEquals("https://user:secret@feeds.example.com/premium.xml", items[0].originalUrl)
            assertEquals("https://feeds.example.com/premium.xml", items[0].normalizedUrl)
            assertEquals(ImportItemStatus.PREVIEW, items[0].status)
        }

    @Test
    fun duplicatesInFileLoseToTheFirstOccurrenceAndUnionGroups() =
        runTest {
            val items =
                classify(
                    entry("https://atp.fm/rss", groups = listOf("tech")),
                    entry("https://feeds.example.com/daily", title = "other"),
                    entry("https://ATP.FM/rss/", groups = listOf("news", "tech")),
                )

            assertEquals(ImportItemStatus.PREVIEW, items[0].status)
            assertEquals(listOf("tech", "news"), items[0].groupNames)
            assertEquals(ImportItemStatus.PREVIEW, items[1].status)
            assertEquals(ImportItemStatus.DUPLICATE_IN_FILE, items[2].status)
            assertTrue(!items[2].selected)
        }

    @Test
    fun alreadySubscribedMatchesFeedKeyAndAliases() =
        runTest {
            val podcastId =
                db.podcastDao().insertPodcast(
                    podcastEntity(feedUrl = "https://atp.fm/rss", feedKey = "atp.fm/rss"),
                )
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "feeds.example.com/daily",
                    podcastId = podcastId,
                    reason = AliasReason.SUBSCRIBE_INPUT,
                    addedAt = clock.now(),
                ),
            )

            val items =
                classify(
                    entry("https://atp.fm/rss"),
                    entry("https://feeds.example.com/daily"),
                    entry("https://new.example.com/feed"),
                )

            // Membership-only rows: flagged, linked and never offered for subscription (AC7).
            assertEquals(ImportItemStatus.ALREADY_SUBSCRIBED, items[0].status)
            assertEquals(podcastId, items[0].podcastId)
            assertEquals(ImportItemStatus.ALREADY_SUBSCRIBED, items[1].status)
            assertEquals(podcastId, items[1].podcastId)
            assertEquals(ImportItemStatus.PREVIEW, items[2].status)
            assertNull(items[2].podcastId)
            assertTrue(items[0].selected.not() && items[1].selected.not() && items[2].selected)
        }

    @Test
    fun preselectFalseKeepsItemUnselected() =
        runTest {
            val items = classify(entry("https://a.example.com/rss", preselect = false))

            assertEquals(ImportItemStatus.PREVIEW, items[0].status)
            assertTrue(!items[0].selected)
        }

    @Test
    fun youtubeEntriesAreSelectableExceptPlaylists() =
        runTest {
            val items =
                classify(
                    entry("https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"),
                    entry("https://www.youtube.com/@handle"),
                    entry("https://www.youtube.com/user/someuser"),
                    entry("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
                    entry("https://www.youtube.com/playlist?list=PLxyz"),
                )

            assertTrue(items.all { it.kind == ImportItemKind.YOUTUBE })
            assertTrue(items.take(4).all { it.status == ImportItemStatus.PREVIEW && it.selected })
            assertEquals(ImportItemStatus.YOUTUBE_UNSUPPORTED_YET, items[4].status)
            // Only a channel ref resolves to the canonical feed URL.
            assertEquals(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ",
                items[0].normalizedUrl,
            )
            assertNull(items[1].normalizedUrl)
            assertEquals("playlist", items[4].errorDetail)
        }

    @Test
    fun youtubeDuplicatesDedupeByResolvedIdentity() =
        runTest {
            val items =
                classify(
                    entry("https://www.youtube.com/@handle"),
                    entry("https://youtube.com/@HANDLE"),
                )

            assertEquals(ImportItemStatus.PREVIEW, items[0].status)
            assertEquals(ImportItemStatus.DUPLICATE_IN_FILE, items[1].status)
        }

    @Test
    fun displayTitleFallsBackToCustomTitleThenHost() =
        runTest {
            val items =
                classify(
                    entry("https://a.example.com/rss", title = "Title"),
                    entry("https://b.example.com/rss", customTitle = "Custom"),
                    entry("https://c.example.com/rss"),
                    entry("https://www.youtube.com/@nohandle"),
                )

            assertEquals("Title", items[0].title)
            assertEquals("Custom", items[1].title)
            assertEquals("c.example.com", items[2].title)
            assertEquals("@nohandle", items[3].title)
        }

    @Test
    fun groupNamesNormaliseAndTruncate() =
        runTest {
            val overlong = "a".repeat(GroupNames.MAX_CODE_POINTS + 5)
            val result =
                classifier.classify(
                    listOf(entry("https://a.example.com/rss", groups = listOf("  Tech  ", "", "tech", overlong))),
                )

            val names = result.items[0].groupNames
            assertEquals(2, names.size, "empty drops, 'tech' dedupes by nameKey")
            assertEquals("Tech", names[0])
            assertEquals(GroupNames.MAX_CODE_POINTS, names[1].length)
            assertEquals(1, result.truncatedNames)
        }

    private suspend fun classify(vararg entries: ImportEntry) = classifier.classify(entries.toList()).items

    private fun entry(
        url: String,
        title: String? = null,
        customTitle: String? = null,
        groups: List<String> = emptyList(),
        preselect: Boolean = true,
        ordinal: Int = nextOrdinal++,
    ): ImportEntry =
        ImportEntry(
            ordinal = ordinal,
            url = url,
            title = title,
            customTitle = customTitle,
            groupNames = groups,
            preselect = preselect,
        )

    private var nextOrdinal = 0
}
