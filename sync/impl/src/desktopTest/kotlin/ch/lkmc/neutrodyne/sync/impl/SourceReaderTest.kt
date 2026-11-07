// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals

/** 10 Push: the `*` snapshot projects the same values the per-field path emits. */
class SourceReaderTest {
    private val syncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test
    fun anEpisodeSnapshotEmitsNullsForNullableState() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId =
                    db
                        .podcastDao()
                        .insertPodcast(
                            podcastEntity(
                                syncId = syncId,
                                feedUrl = "https://example.com/feed",
                                feedKey = "https://example.com/feed",
                            ),
                        )
                db
                    .ingestDao()
                    .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                val reader = SourceReader(db, FakeSecretStore(), sharePasswords = { false })
                val state = reader.allFields(SyncCollection.EPISODE, "$syncId" + "g:one")
                assertEquals(JsonNull, state.fields["playedAt"]?.v)
                assertEquals(JsonNull, state.fields["lastPlayedAt"]?.v)
                assertEquals(JsonNull, state.fields["measuredDurationMs"]?.v)
            } finally {
                db.close()
            }
        }
}
