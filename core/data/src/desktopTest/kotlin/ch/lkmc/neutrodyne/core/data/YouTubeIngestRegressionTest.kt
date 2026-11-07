// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.ingest.RowHint
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeIngestRegressionTest {
    @Test fun atomPreservesPageMetadata() =
        runTest {
            val clock = TestClock()
            val db = newDb(clock)
            try {
                val id =
                    seedPodcast(db, "https://www.youtube.com/feeds/videos.xml?channel_id=UCaaaaaaaaaaaaaaaaaaaaaA") {
                        copy(
                            sourceType = SourceType.YOUTUBE_CHANNEL,
                            artworkUrl = "https://example.com/avatar",
                            artworkKey = "avatar",
                            bannerUrl = "https://example.com/banner",
                            descriptionHtml = "Page description",
                        )
                    }
                val before = db.podcastDao().byId(id)!!
                newIngestor(db, clock).ingest(dueFeedOf(db, id), parsedFeed(items = listOf(item())), context())
                val after = db.podcastDao().byId(id)!!
                assertEquals(before.artworkUrl, after.artworkUrl)
                assertEquals(before.artworkKey, after.artworkKey)
                assertEquals(before.bannerUrl, after.bannerUrl)
                assertEquals(before.descriptionHtml, after.descriptionHtml)
                assertEquals(before.link, after.link)
            } finally {
                db.close()
            }
        }

    @Test fun variantOnlyShortsChangePersistsAndStaysSticky() =
        runTest {
            val clock = TestClock()
            val db = newDb(clock)
            try {
                val id =
                    seedPodcast(db, "https://www.youtube.com/feeds/videos.xml?channel_id=UCaaaaaaaaaaaaaaaaaaaaaA") {
                        copy(sourceType = SourceType.YOUTUBE_CHANNEL)
                    }
                val ingestor = newIngestor(db, clock)
                ingestor.ingest(dueFeedOf(db, id), parsedFeed(items = listOf(item())), context(false))
                ingestor.ingest(dueFeedOf(db, id), parsedFeed(items = listOf(item())), context(true))
                assertTrue(db.episodeDao().byIdentityKey(id, "g:yt:video:aaaaaaaaaaa")!!.isShort)
                ingestor.ingest(
                    dueFeedOf(db, id),
                    parsedFeed(items = listOf(item().copy(title = "Edited"))),
                    context(false),
                )
                assertTrue(db.episodeDao().byIdentityKey(id, "g:yt:video:aaaaaaaaaaa")!!.isShort)
            } finally {
                db.close()
            }
        }

    private fun item() =
        parsedEpisode(0, guid = "yt:video:aaaaaaaaaaa", enclosureUrl = null, externalMediaId = "aaaaaaaaaaa")

    private fun context(short: Boolean = false) =
        IngestContext(
            IngestMode.REFRESH,
            true,
            fetchMeta(),
            rowHints = mapOf("aaaaaaaaaaa" to RowHint(null, short, false)),
        )
}
