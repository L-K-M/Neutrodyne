// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PodcastRepositoryImpl` over a real database: the `FeedMove` host contract, the artwork
 * version on the detail model and multi-podcast unsubscribe atomicity (03).
 */
class PodcastRepositoryTest {
    private val clock = TestClock(NOW)
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()
    private val scheduler = FakeRefreshScheduler()

    private fun repo() =
        PodcastRepositoryImpl(
            db = db,
            refresher = newRefresher(db, emptyMap(), clock, settings),
            scheduler = scheduler,
            sanitizer = JsoupShowNotesSanitizer(),
            clock = clock,
        )

    @Test
    fun aFeedMoveCarriesTheIdentityFormHost() =
        runTest {
            // Pin: alias URLs are persisted in `forIdentity` (scheme-less) form, so the moves
            // list's host extraction is `substringBefore('/')`, not a scheme strip.
            val id = seedPodcast(db, "feeds.example.com/show", feedKey = "feeds.example.com/show")
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "old.example.com/feed",
                    podcastId = id,
                    reason = AliasReason.REDIRECT,
                    addedAt = 42L,
                ),
            )

            val info = assertNotNull(repo().observeFeedInfo(id).first())

            assertEquals("old.example.com", info.moves.single().fromHost)
            assertEquals(AliasReason.REDIRECT, info.moves.single().reason)
        }

    @Test
    fun thePodcastDetailCarriesTheStoredArtworkVersion() =
        runTest {
            val id = seedPodcast(db, "https://a.example.com/f")
            val key = db.podcastDao().byId(id)!!.artworkKey
            db.artworkDao().upsert(ArtworkEntity(key = key, version = 7))

            val detail = assertNotNull(repo().observePodcast(id).first())

            assertEquals(7, detail.artwork.version)
        }

    @Test
    fun aMultiPodcastUnsubscribeIsAtomic() =
        runTest {
            // A crash mid-list must not leave a half-unsubscribed library (03 Unsubscribe).
            val a = seedPodcast(db, "https://a.example.com/f")
            val b = seedPodcast(db, "https://b.example.com/f")
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER fail_unsub BEFORE DELETE ON podcast" +
                        " WHEN old.id = $b BEGIN SELECT RAISE(ABORT, 'unsub'); END",
                )
            }

            val result = runCatching { repo().unsubscribe(listOf(a, b), ChangeOrigin.LOCAL) }

            assertTrue(result.isFailure, "unsubscribe must propagate the DAO fault")
            assertNotNull(db.podcastDao().byId(a))
            assertNotNull(db.podcastDao().byId(b))
        }

    @Test
    fun anUnsubscribeOfOnePodcastStillCommits() =
        runTest {
            val id = seedPodcast(db, "https://a.example.com/f")

            val removed = repo().unsubscribe(listOf(id), ChangeOrigin.LOCAL)

            assertEquals(listOf(id), removed)
            assertNull(db.podcastDao().byId(id))
            assertTrue(scheduler.rescheduleCount > 0)
        }

    private companion object {
        const val NOW = 1_791_000_000_000L
    }
}
