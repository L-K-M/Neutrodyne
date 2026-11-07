// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S14 / MS0 acceptance 3: with `sync_state.enabled = 1` every synced-column write produces exactly
 * one coalesced outbox row per field, device-local columns produce none, deletes and resets store
 * literals, `applying = 1` suppresses capture, and a rekey moves this device's rows.
 */
class SyncCaptureTest {
    private val node = "0000000000000001"
    private val replayNode = "ffffffffffffffff"
    private val replayClock = 1L shl 57
    private val podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val groupUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val episodeRid = "${podcastSyncId}g:one"

    @Test
    fun everySyncedColumnProducesOneCoalescedRow() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                val outbox = db.syncOutboxDao()

                val cases =
                    linkedMapOf(
                        "podcast" to
                            listOf(
                                "feedUrl='https://changed.example/feed'|feedUrl",
                                "feedKey='changed-key'|feedKeys",
                                "podcastGuid='guid'|podcastGuid",
                                "title='changed'|title",
                                "artworkUrl='https://changed.example/art'|artworkUrl",
                                "link='https://changed.example'|link",
                                "customTitle='custom'|customTitle",
                                "includeInAll=0|includeInAll",
                                "episodeOrder='OLDEST_FIRST'|episodeOrder",
                                "youtubeVariants=2|youtubeVariants",
                                "needsCredentials=1|needsCredentials",
                            ),
                        "podcast_group" to
                            listOf(
                                "name='changed'|name",
                                "orderKey='b'|ok",
                                "colorArgb=3|colorArgb",
                                "iconKey='changed'|iconKey",
                                "feedOrder='OLDEST_FIRST'|feedOrder",
                                "playOrder='OLDEST_FIRST'|playOrder",
                                "filterFlags=1|filterFlags",
                                "mediaFilter='AUDIO'|mediaFilter",
                                "hideOlderThanDays=5|hideOlderThanDays",
                                "showAsTab=0|showAsTab",
                            ),
                        "podcast_settings" to
                            listOf(
                                "playbackSpeed=1.5|s.playbackSpeed",
                                "skipSilence=1|s.skipSilence",
                                "boostDb=2|s.boostDb",
                                "introSkipMs=3|s.introSkipMs",
                                "outroSkipMs=4|s.outroSkipMs",
                            ),
                        "podcast_group_settings" to
                            listOf(
                                "playbackSpeed=1.5|s.playbackSpeed",
                                "skipSilence=1|s.skipSilence",
                                "boostDb=2|s.boostDb",
                                "introSkipMs=3|s.introSkipMs",
                                "outroSkipMs=4|s.outroSkipMs",
                            ),
                        "podcast_group_member" to listOf("orderKey='b'|ok"),
                        "episode_state" to
                            listOf(
                                "playedAt=42|played",
                                "isFavorite=1|fav",
                                "playCount=2|playCount",
                                "lastPlayedAt=43|lastPlayedAt",
                                "measuredDurationMs=99|measuredDurationMs",
                            ),
                        "episode_position" to
                            listOf(
                                "positionMs=10|pos",
                                "durationMs=200|pos",
                                "positionSource='DOWNLOAD'|pos",
                            ),
                        "queue_entry" to listOf("orderKey='b'|ok"),
                        "play_session" to
                            listOf(
                                "currentEpisodeId=1|episode",
                                "contextType='ALL'|context",
                                "contextId=1|context",
                                "contextOrder='OLDEST_FIRST'|context",
                                "contextFilterFlags=1|context",
                                "contextMediaFilter='AUDIO'|context",
                                "contextMinSortDate=1|context",
                                "contextAnchorEpisodeId=1|context",
                                "contextAnchorSortDate=1|context",
                            ),
                    )
                for ((table, changes) in cases) {
                    for (change in changes) {
                        val (assignment, expected) = change.split('|')
                        db.withWriteTransaction {
                            db.useWriterConnection { connection ->
                                connection.exec("DELETE FROM sync_outbox")
                                val before = db.syncStateDao().get()!!.hlc
                                connection.exec("UPDATE $table SET $assignment")
                                val rows = outbox.rows()
                                assertEquals(
                                    expected.split(',').toSet(),
                                    rows.map { it.field }.toSet(),
                                    "$table $assignment",
                                )
                                assertTrue(rows.all { it.nodeId == node }, "$table $assignment node")
                                assertEquals(1, rows.map { it.hlc }.toSet().size, "$table $assignment clock")
                                assertTrue(rows.first().hlc > before, "$table $assignment advances the clock")
                                // An identical repeat captures nothing: one coalesced row, same clock.
                                connection.exec("UPDATE $table SET $assignment")
                                assertEquals(rows, outbox.rows(), "$table identical write")
                            }
                        }
                    }
                }
            } finally {
                db.close()
            }
        }

    @Test
    fun insertDeleteAndResetLiterals() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                val outbox = db.syncOutboxDao()

                // A podcast insert captures `*` and an unsubscribe captures the literal tombstone.
                db.withWriteTransaction {
                    db.useWriterConnection { it.exec("DELETE FROM sync_outbox") }
                    val id =
                        db
                            .podcastDao()
                            .insertPodcast(
                                podcastEntity(id = 0, syncId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc", feedKey = "k-c"),
                            )
                    assertEquals(setOf("*"), outbox.rows().map { it.field }.toSet())
                    db.podcastDao().deleteCascade(id, now = 2)
                }
                val unsub = outbox.rows().filter { it.coll == "podcast" }
                assertTrue(unsub.any { it.field == "subscribed" && it.value == "false" }, "tombstone literal")

                // A non-zero to zero position stores the reset form; zero to zero stores nothing.
                db.withWriteTransaction {
                    db.useWriterConnection { it.exec("DELETE FROM sync_outbox") }
                    db
                        .positionDao()
                        .upsert(
                            EpisodePositionEntity(
                                episodeId = 1,
                                positionMs = 100,
                                durationMs = 100,
                                positionSource = PositionSource.STREAM,
                                updatedAt = 2,
                            ),
                        )
                    db
                        .positionDao()
                        .upsert(
                            EpisodePositionEntity(
                                episodeId = 1,
                                positionMs = 0,
                                durationMs = 100,
                                positionSource = PositionSource.STREAM,
                                updatedAt = 3,
                            ),
                        )
                }
                val pos = outbox.rows().filter { it.coll == "episode" && it.field == "pos" }
                assertEquals(1, pos.size)
                assertEquals("{\"ms\":0,\"reset\":true}", pos.single().value)

                db.withWriteTransaction {
                    db.useWriterConnection { it.exec("DELETE FROM sync_outbox") }
                    db
                        .positionDao()
                        .upsert(
                            EpisodePositionEntity(
                                episodeId = 1,
                                positionMs = 0,
                                durationMs = 100,
                                positionSource = PositionSource.STREAM,
                                updatedAt = 4,
                            ),
                        )
                }
                assertEquals(0, outbox.count(), "an identical zero position captures nothing")
            } finally {
                db.close()
            }
        }

    @Test
    fun applyingSuppressesCapture() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        db
                            .podcastDao()
                            .insertPodcast(
                                podcastEntity(id = 0, syncId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd", feedKey = "k-d"),
                            )
                        db.episodeStateDao().markPlayed(listOf(1), now = 5)
                    }
                }
                assertEquals(0, db.syncOutboxDao().count(), "applying = 1 must keep the outbox empty")
            } finally {
                db.close()
            }
        }

    @Test
    fun rekeyMovesRowsAndEmitsTheRekey() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                val outbox = db.syncOutboxDao()
                db.withWriteTransaction {
                    db.episodeStateDao().markPlayed(listOf(1), now = 5)
                }
                assertTrue(outbox.rows().any { it.coll == "episode" && it.rid == episodeRid })

                db.withWriteTransaction {
                    db.useWriterConnection {
                        it.exec("UPDATE episode SET identityKey='g:two' WHERE id = 1")
                    }
                }
                val rows = outbox.rows().filter { it.coll == "episode" }
                assertTrue(rows.any { it.field == "~rekey" && it.value == "{\"to\":\"$podcastSyncId" + "g:two\"}" })
                assertTrue(
                    rows.filter { it.field != "~rekey" }.none { it.rid == episodeRid },
                    "old rid field rows move to the new rid",
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun captureAtAndStoredRespectTheCompleteClock() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                val outbox = db.syncOutboxDao()
                db.withWriteTransaction {
                    outbox.captureAt("podcast", podcastSyncId, "customTitle", 1_700_000_000_000L, "\"old\"")
                    outbox.captureAt("podcast", podcastSyncId, "customTitle", 1_600_000_000_000L, "\"older\"")
                }
                assertEquals("\"old\"", outbox.get("podcast", podcastSyncId, "customTitle")?.value)

                // A replay keeps the foreign node's complete clock and never lowers a known one.
                db.withWriteTransaction {
                    outbox.captureStored(
                        "podcast",
                        podcastSyncId,
                        "customTitle",
                        replayClock,
                        replayNode,
                        "\"replay\"",
                    )
                    outbox.captureStored(
                        "podcast",
                        podcastSyncId,
                        "customTitle",
                        replayClock - 1,
                        replayNode,
                        "\"older\"",
                    )
                }
                val row = outbox.get("podcast", podcastSyncId, "customTitle")!!
                assertEquals(replayClock, row.hlc)
                assertEquals(replayNode, row.nodeId)
                assertEquals(SyncCaptureKind.REPLAY, row.captureKind)
                assertEquals("\"replay\"", row.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalCaptureKeepsANewerReplayRow() =
        runTest {
            val db = TestDb.inMemory()
            try {
                seed(db)
                val outbox = db.syncOutboxDao()
                db.withWriteTransaction {
                    db.useWriterConnection { it.exec("DELETE FROM sync_outbox") }
                    // Packed clocks are wallMs shl 16; a far-future stamp stays newer than now.
                    val replayClock = Long.MAX_VALUE / 2
                    outbox.captureStored(
                        "podcast",
                        podcastSyncId,
                        "customTitle",
                        replayClock,
                        "0000000000000009",
                        "\"replay\"",
                    )
                    outbox.captureLiteral("podcast", podcastSyncId, "customTitle", "\"local\"")
                }
                val row = outbox.get("podcast", podcastSyncId, "customTitle")!!
                assertEquals("0000000000000009", row.nodeId)
                assertEquals(SyncCaptureKind.REPLAY, row.captureKind)
                assertEquals("\"replay\"", row.value)
            } finally {
                db.close()
            }
        }

    private suspend fun seed(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                db.podcastDao().insertPodcast(podcastEntity(id = 1, syncId = podcastSyncId, feedKey = "k"))
                db.ingestDao().insertEpisodes(listOf(episodeEntity(id = 1, podcastId = 1, identityKey = "g:one")))
                db.groupDao().insert(groupEntity(uuid = groupUuid, name = "Group", orderKey = "a"))
                db.groupDao().insertMember(memberEntity(groupId = 1, podcastId = 1, orderKey = "a"))
                db.scopeSettingsDao().upsertPodcast(PodcastSettingsEntity(1, ScopeOverrides()))
                db.scopeSettingsDao().upsertGroup(PodcastGroupSettingsEntity(1, ScopeOverrides()))
                db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = 1, updatedAt = 1))
                db
                    .positionDao()
                    .insertIfAbsent(
                        EpisodePositionEntity(
                            episodeId = 1,
                            positionMs = 0,
                            positionSource = PositionSource.STREAM,
                            updatedAt = 1,
                        ),
                    )
                db.queueDao().insert(queueEntryEntity(episodeId = 1, orderKey = "a"))
                db.playSessionDao().ensureNow(1)
            }
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = node, hlc = 1L shl 16))
        }
    }
}
