// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

    @Test
    fun aSessionSnapshotEmitsThePlaySessionContext() =
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
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                val anchorId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:anchor")))
                        .single()
                val groupId = db.groupDao().insert(groupEntity(uuid = GROUP_UUID, name = "G", orderKey = "a0"))
                db.withWriteTransaction {
                    db.playSessionDao().ensureNow(1L)
                    db
                        .playSessionDao()
                        .restoreSession(
                            currentEpisodeId = episodeId,
                            contextType = ContextType.GROUP,
                            contextId = groupId,
                            contextOrder = FeedOrder.OLDEST_FIRST,
                            contextFilterFlags = 5,
                            contextMediaFilter = MediaFilter.VIDEO,
                            contextMinSortDate = 1_234L,
                            contextAnchorEpisodeId = anchorId,
                            contextAnchorSortDate = 987L,
                            onlyWhenIdle = false,
                            now = 1L,
                        )
                }
                val reader = SourceReader(db, FakeSecretStore(), sharePasswords = { false })
                val context = reader.session()!!.field("context") as JsonObject
                assertEquals(JsonPrimitive("GROUP"), context["type"])
                assertEquals(JsonPrimitive(GROUP_UUID), context["group"])
                assertEquals(JsonPrimitive("OLDEST_FIRST"), context["order"])
                val filters = context["filters"] as JsonObject
                assertEquals(JsonPrimitive(5), filters["flags"])
                assertEquals(JsonPrimitive("VIDEO"), filters["media"])
                assertEquals(JsonPrimitive(1_234L), context["minSortDate"])
                val anchor = context["anchor"] as JsonObject
                assertEquals(JsonPrimitive(syncId), anchor["p"])
                assertEquals(JsonPrimitive("g:anchor"), anchor["k"])
                assertEquals(JsonPrimitive(987L), context["anchorSortDate"])

                val all = reader.allFields(SyncCollection.SESSION, "current")
                assertNotNull(all.fields["episode"])
                assertNotNull(all.fields["context"])
            } finally {
                db.close()
            }
        }

    @Test
    fun aSessionContextWithoutRowsDegradesToExternal() =
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
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                db.withWriteTransaction {
                    db.playSessionDao().ensureNow(1L)
                    db
                        .playSessionDao()
                        .restoreSession(
                            currentEpisodeId = episodeId,
                            contextType = ContextType.GROUP,
                            contextId = 9_999L,
                            contextOrder = FeedOrder.NEWEST_FIRST,
                            contextFilterFlags = 0,
                            contextMediaFilter = MediaFilter.ALL,
                            contextMinSortDate = null,
                            contextAnchorEpisodeId = null,
                            contextAnchorSortDate = null,
                            onlyWhenIdle = false,
                            now = 1L,
                        )
                }
                val context =
                    SourceReader(db, FakeSecretStore(), sharePasswords = { false })
                        .session()!!
                        .field("context") as JsonObject
                assertEquals(JsonPrimitive("EXTERNAL"), context["type"], "a dangling context id degrades")
                assertEquals(null, context["group"])
            } finally {
                db.close()
            }
        }

    private companion object {
        const val GROUP_UUID = "00000000-0000-4000-8000-0000000000bb"
    }
}
