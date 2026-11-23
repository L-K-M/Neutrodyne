// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.BackupDao
import ch.lkmc.neutrodyne.core.database.RestoreEpisodeCandidate
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 02 Reuse by sync: the episode matching ladder and its per-record candidate read. */
class EpisodeMatcherTest {
    private val feedUrl = "https://example.com/feed"

    @Test
    fun aKeyHitMatchesByIdentityKey() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = newPodcast(db)
                val episodeId = newEpisode(db, podcastId, "g:one")

                val matched = EpisodeMatcher(db.backupDao()).match(podcastId, "g:one", null)

                assertEquals(episodeId, matched)
            } finally {
                db.close()
            }
        }

    /** The current-version key hit is one indexed lookup, never the podcast's candidate list. */
    @Test
    fun aKeyHitDoesNotLoadTheFullCandidateList() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = newPodcast(db)
                val first = newEpisode(db, podcastId, "g:one", enclosure = SHARED_ENCLOSURE, guid = "one")
                newEpisode(db, podcastId, "g:two", enclosure = SHARED_ENCLOSURE, guid = "two")
                val fullListReads = AtomicInteger()
                val matcher = EpisodeMatcher(CountingBackupDao(db.backupDao(), fullListReads))

                assertEquals(first, matcher.match(podcastId, "g:one", null))
                assertEquals(0, fullListReads.get(), "a key hit must not load the whole candidate list")

                // A miss falls through to the enclosure rung and loads the list exactly once.
                assertEquals(
                    first,
                    matcher.match(podcastId, "g:absent", MatchHints(enc = SHARED_ENCLOSURE)),
                )
                assertEquals(1, fullListReads.get())
            } finally {
                db.close()
            }
        }

    /** The ladder's order is unchanged: the key rung outranks the enclosure and guid rungs. */
    @Test
    fun aKeyHitWinsOverAnEnclosureAndGuidHit() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = newPodcast(db)
                newEpisode(db, podcastId, "g:one", enclosure = SHARED_ENCLOSURE, guid = "one")
                val second = newEpisode(db, podcastId, "g:two", enclosure = SHARED_ENCLOSURE, guid = "two")

                // The key claims the second row; the enclosure and guid hints both point at the
                // first. The key rung must win.
                val matched =
                    EpisodeMatcher(db.backupDao()).match(
                        podcastId,
                        "g:two",
                        MatchHints(enc = SHARED_ENCLOSURE, guid = "one"),
                    )

                assertEquals(second, matched)
            } finally {
                db.close()
            }
        }

    @Test
    fun anEnclosureHitStillMatchesAfterAKeyMiss() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = newPodcast(db)
                val episodeId = newEpisode(db, podcastId, "g:one", enclosure = SHARED_ENCLOSURE)

                val matched =
                    EpisodeMatcher(db.backupDao()).match(
                        podcastId,
                        "g:absent",
                        MatchHints(enc = SHARED_ENCLOSURE),
                    )

                assertEquals(episodeId, matched)
            } finally {
                db.close()
            }
        }

    @Test
    fun aGuidHitStillMatchesAfterAKeyMiss() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = newPodcast(db)
                val episodeId = newEpisode(db, podcastId, "g:one", guid = "one")

                val matched =
                    EpisodeMatcher(db.backupDao()).match(podcastId, "g:absent", MatchHints(guid = "one"))

                assertEquals(episodeId, matched)
            } finally {
                db.close()
            }
        }

    @Test
    fun aMissReturnsNull() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = newPodcast(db)
                newEpisode(db, podcastId, "g:one")

                val matched = EpisodeMatcher(db.backupDao()).match(podcastId, "g:absent", null)

                assertNull(matched)
            } finally {
                db.close()
            }
        }

    /**
     * `EpisodeKeys.VERSION` is 1 and no earlier key version ever shipped, so a `kv` below it
     * cannot come off the wire. This pins the older-version rung's existing behaviour —
     * `EpisodeKeys.keyFor` rejects the version — rather than inventing a hit.
     */
    @Test
    fun anOlderKeyVersionIsRejectedAsBefore() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = newPodcast(db)
                newEpisode(db, podcastId, "g:one")

                assertFailsWith<IllegalArgumentException> {
                    EpisodeMatcher(db.backupDao()).match(podcastId, "g:one", MatchHints(kv = 0))
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerKeyVersionStillFallsBackToEnclosureAndGuid() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = newPodcast(db)
                val episodeId =
                    newEpisode(db, podcastId, "g:one", enclosure = SHARED_ENCLOSURE, guid = "one")
                val matched =
                    EpisodeMatcher(db.backupDao()).match(
                        podcastId,
                        "k:uncomputable-v2-key",
                        MatchHints(kv = 2, enc = SHARED_ENCLOSURE, guid = "one"),
                    )
                assertEquals(episodeId, matched)
            } finally {
                db.close()
            }
        }

    private suspend fun newPodcast(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase): Long =
        db
            .podcastDao()
            .insertPodcast(
                podcastEntity(
                    syncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    feedUrl = feedUrl,
                    feedKey = feedUrl,
                ),
            )

    private suspend fun newEpisode(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        podcastId: Long,
        identityKey: String,
        enclosure: String? = null,
        guid: String? = null,
    ): Long =
        db
            .ingestDao()
            .insertEpisodes(
                listOf(
                    episodeEntity(podcastId = podcastId, identityKey = identityKey)
                        .copy(enclosureUrl = enclosure, guid = guid),
                ),
            ).single()

    private companion object {
        const val SHARED_ENCLOSURE = "https://cdn.example.com/one.mp3"
    }
}

/** Counts the full-candidate-list reads so a key hit can prove it took the indexed lookup. */
private class CountingBackupDao(
    private val delegate: BackupDao,
    private val fullListReads: AtomicInteger,
) : BackupDao by delegate {
    override suspend fun restoreEpisodeCandidates(podcastId: Long): List<RestoreEpisodeCandidate> {
        fullListReads.incrementAndGet()
        return delegate.restoreEpisodeCandidates(podcastId)
    }
}
