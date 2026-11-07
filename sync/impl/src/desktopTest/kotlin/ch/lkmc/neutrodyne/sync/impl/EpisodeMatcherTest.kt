// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 02 Reuse by sync: a newer key version skips key matching, not the enclosure and guid rungs. */
class EpisodeMatcherTest {
    @Test
    fun aNewerKeyVersionStillFallsBackToEnclosureAndGuid() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId =
                    db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                syncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                feedUrl = "https://example.com/feed",
                                feedKey = "https://example.com/feed",
                            ),
                        )
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(
                                episodeEntity(podcastId = podcastId, identityKey = "g:one")
                                    .copy(enclosureUrl = "https://cdn.example.com/one.mp3", guid = "one"),
                            ),
                        ).single()
                val matched =
                    EpisodeMatcher(db.backupDao()).match(
                        podcastId,
                        "k:uncomputable-v2-key",
                        MatchHints(kv = 2, enc = "https://cdn.example.com/one.mp3", guid = "one"),
                    )
                assertEquals(episodeId, matched)
            } finally {
                db.close()
            }
        }
}
