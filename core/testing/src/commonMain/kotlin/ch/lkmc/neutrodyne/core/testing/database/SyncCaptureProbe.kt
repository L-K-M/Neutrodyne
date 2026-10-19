// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import androidx.room3.PooledConnection
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.model.PositionSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Database-only capture proof shared across drivers. Writer integration belongs to W5. */
object SyncCaptureProbe {
    private const val PODCAST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private const val EPISODE = PODCAST + "g:one"
    private const val GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private const val NODE = "0000000000000001"
    private const val PEER = "0000000000000002"

    private suspend fun seed(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                db.podcastDao().insertPodcast(podcastEntity(id = 1, syncId = PODCAST))
                db.ingestDao().insertEpisodes(listOf(episodeEntity(id = 1, podcastId = 1, identityKey = "g:one")))
                db.useWriterConnection { c ->
                    c.exec(
                        "INSERT INTO podcast_group(id, uuid, name, nameKey, orderKey, createdAt, updatedAt) VALUES (1, '$GROUP', 'group', 'group', 'a', 1, 1)",
                    )
                    c.exec("INSERT INTO podcast_settings(podcastId) VALUES (1)")
                    c.exec("INSERT INTO podcast_group_settings(groupId) VALUES (1)")
                    c.exec(
                        "INSERT INTO podcast_group_member(groupId, podcastId, orderKey, addedAt) VALUES (1, 1, 'a', 1)",
                    )
                    c.exec("INSERT INTO episode_state(episodeId, updatedAt) VALUES (1, 1)")
                    c.exec(
                        "INSERT INTO episode_position(episodeId, positionMs, positionSource, updatedAt) VALUES (1, 0, 'STREAM', 1)",
                    )
                    c.exec("INSERT INTO queue_entry(episodeId, orderKey, addedAt) VALUES (1, 'a', 1)")
                    c.exec(
                        "INSERT INTO credential(id, origin, username, secretCipher, iv, createdAt) VALUES (1, 'example.com', 'u', x'01', x'02', 1)",
                    )
                }
            }
        }
    }

    /** Each column has an independently declared expectation, rather than copying the renderer. */
    suspend fun everySyncedUpdate(db: NeutrodyneDatabase) {
        seed(db)
        val outbox = db.syncOutboxDao()
        val tables =
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
                        "credentialId=1|credentialOrigin,auth,needsCredentials",
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
                "episode_position" to listOf("positionMs=10|pos"),
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
        for ((table, changes) in tables) {
            for (change in changes) {
                val (assignment, expected) = change.split('|')
                db.withWriteTransaction {
                    db.useWriterConnection { c ->
                        c.exec("DELETE FROM sync_outbox")
                        val before = db.syncStateDao().get()!!.hlc
                        c.exec("UPDATE $table SET $assignment")
                        val rows = outbox.rows()
                        assertEquals(expected.split(',').toSet(), rows.map { it.field }.toSet(), "$table $assignment")
                        assertTrue(rows.all { it.nodeId == NODE })
                        assertEquals(1, rows.map { it.hlc }.toSet().size)
                        assertTrue(rows.first().hlc > before)
                        val after = db.syncStateDao().get()!!.hlc
                        // Repeating the exact same UPDATE must leave payloads and clock untouched.
                        c.exec("UPDATE $table SET $assignment")
                        assertEquals(rows, outbox.rows(), "$table identical write")
                        assertEquals(after, db.syncStateDao().get()!!.hlc)
                    }
                }
            }
        }
        // Changing a derived GUID is local. Becoming authoritative captures the existing GUID.
        db.withWriteTransaction {
            db.useWriterConnection { c ->
                c.exec("DELETE FROM sync_outbox")
                c.exec("UPDATE podcast SET podcastGuidDerived=1, podcastGuid='derived'")
                assertEquals(0, outbox.count())
                c.exec("UPDATE podcast SET podcastGuidDerived=0")
                assertEquals(
                    "podcastGuid",
                    outbox
                        .rows()
                        .single()
                        .field,
                )
            }
        }
    }

    suspend fun localColumnsAndSuppression(db: NeutrodyneDatabase) {
        seed(db)
        val outbox = db.syncOutboxDao()
        // Every non-relational column outside this allow-list must remain local.
        val synced =
            mapOf(
                "podcast" to "feedUrl feedKey podcastGuid podcastGuidDerived title artworkUrl link " +
                    "customTitle includeInAll episodeOrder youtubeVariants credentialId needsCredentials",
                "podcast_group" to "name orderKey colorArgb iconKey feedOrder playOrder filterFlags " +
                    "mediaFilter hideOlderThanDays showAsTab",
                "podcast_settings" to "playbackSpeed skipSilence boostDb introSkipMs outroSkipMs",
                "podcast_group_settings" to "playbackSpeed skipSilence boostDb introSkipMs outroSkipMs",
                "podcast_group_member" to "orderKey",
                "episode_state" to "playedAt isFavorite playCount lastPlayedAt measuredDurationMs",
                "episode_position" to "positionMs",
                "queue_entry" to "orderKey",
                "play_session" to "currentEpisodeId contextType contextId contextOrder contextFilterFlags " +
                    "contextMediaFilter contextMinSortDate contextAnchorEpisodeId contextAnchorSortDate",
                "episode" to "identityKey",
            )
        db.withWriteTransaction {
            db.useWriterConnection { c ->
                for ((table, captured) in synced) {
                    val excluded = captured.split(' ').toSet() + setOf("id", "podcastId", "episodeId", "groupId")
                    val localColumns =
                        c.usePrepared("PRAGMA table_info($table)") { statement ->
                            buildList {
                                while (statement.step()) {
                                    if (statement.getText(1) !in excluded) {
                                        add(statement.getText(1) to statement.getText(2))
                                    }
                                }
                            }
                        }
                    for ((column, type) in localColumns) {
                        val value =
                            when (type) {
                                "INTEGER", "REAL" -> "COALESCE($column, 0) + 1"
                                else -> "COALESCE($column, '') || 'local'"
                            }
                        c.exec("SAVEPOINT local_column")
                        c.exec("UPDATE $table SET $column = $value")
                        assertEquals(0, outbox.count(), "$table.$column is local")
                        assertEquals(0L, db.syncStateDao().get()!!.hlc, "$table.$column must not tick")
                        c.exec("ROLLBACK TO local_column")
                        c.exec("RELEASE local_column")
                    }
                }
                assertEquals(0, outbox.count())
                assertEquals(0L, db.syncStateDao().get()!!.hlc)
                db.syncStateDao().withApplying {
                    c.exec("UPDATE podcast SET customTitle='remote'")
                    db.syncStateDao().withApplying { c.exec("UPDATE episode_position SET positionMs=50") }
                    c.exec("DELETE FROM queue_entry")
                }
                assertEquals(0, outbox.count())
                assertEquals(0L, db.syncStateDao().get()!!.hlc)
                assertFalse(db.syncStateDao().get()!!.applying)
            }
        }
    }

    suspend fun insertsDeletesAndLiterals(db: NeutrodyneDatabase) {
        seed(db)
        val outbox = db.syncOutboxDao()
        db.withWriteTransaction {
            db.useWriterConnection { c ->
                c.exec("UPDATE episode_state SET playedAt=123, playCount=1")
                val played = outbox.get("episode", EPISODE, "played")!!
                assertEquals("{\"played\":true,\"playedAt\":123}", played.value)
                c.exec("UPDATE episode_position SET positionMs=200, durationMs=500, positionSource='STREAM'")
                assertEquals(
                    "{\"ms\":200,\"dur\":500,\"src\":\"STREAM\"}",
                    outbox.get("episode", EPISODE, "pos")!!.value,
                )
                c.exec("UPDATE episode_position SET positionMs=0")
                assertEquals(
                    "{\"ms\":0,\"reset\":true}",
                    outbox.get("episode", EPISODE, "pos")!!.value,
                )
                c.exec("UPDATE episode_state SET playedAt=NULL")
                assertEquals(
                    "{\"played\":false,\"playedAt\":null}",
                    outbox.get("episode", EPISODE, "played")!!.value,
                )
                c.exec("DELETE FROM sync_outbox")
                val clock = db.syncStateDao().get()!!.hlc
                c.exec("DELETE FROM episode_state")
                c.exec("DELETE FROM episode_position")
                assertEquals(clock, db.syncStateDao().get()!!.hlc)
                assertEquals(0, outbox.count())
                c.exec("INSERT INTO episode_state(episodeId, updatedAt) VALUES (1, 1)")
                c.exec(
                    "INSERT INTO episode_position(episodeId, positionMs, positionSource, updatedAt) VALUES (1, 0, 'STREAM', 1)",
                )
                assertEquals(0, outbox.count(), "default inserts have no user state")
                assertEquals(clock, db.syncStateDao().get()!!.hlc)
                c.exec("DELETE FROM episode_state")
                c.exec("DELETE FROM episode_position")
                c.exec(
                    "INSERT INTO episode_position(episodeId, positionMs, positionSource, updatedAt) VALUES (1, 12, 'STREAM', 1)",
                )
                assertEquals("{\"ms\":12,\"dur\":null,\"src\":\"STREAM\"}", outbox.rows().single().value)
                c.exec("DELETE FROM episode_position")
                c.exec("DELETE FROM sync_outbox")
                c.exec(
                    "INSERT INTO episode_state(episodeId, playedAt, isFavorite, playCount, lastPlayedAt, measuredDurationMs, updatedAt) VALUES (1, 123, 1, 2, 124, 500, 1)",
                )
                assertEquals(
                    setOf(
                        "played",
                        "fav",
                        "playCount",
                        "lastPlayedAt",
                        "measuredDurationMs",
                    ),
                    outbox
                        .rows()
                        .map {
                            it.field
                        }.toSet(),
                )
                c.exec("DELETE FROM sync_outbox")
                for (table in listOf("podcast_settings", "podcast_group_settings")) {
                    c.exec("DELETE FROM $table")
                    assertEquals(0, outbox.count(), "deleting default settings has no fields")
                    val id = if (table == "podcast_settings") "podcastId" else "groupId"
                    c.exec(
                        "INSERT INTO $table($id, playbackSpeed, skipSilence, boostDb, introSkipMs, outroSkipMs) VALUES (1, 1.5, 1, 2, 3, 4)",
                    )
                    assertEquals(5, outbox.count())
                    c.exec("DELETE FROM $table")
                    assertTrue(outbox.rows().all { it.value == "null" })
                    assertEquals(5, outbox.count())
                    c.exec("DELETE FROM sync_outbox")
                }
                for ((table, rid, coll, insert) in listOf(
                    listOf(
                        "podcast_group_member",
                        GROUP + PODCAST,
                        "member",
                        "groupId, podcastId, orderKey, addedAt) VALUES (1, 1, 'a', 1)",
                    ),
                    listOf(
                        "queue_entry",
                        EPISODE,
                        "upnext",
                        "episodeId, orderKey, addedAt) VALUES (1, 'a', 1)",
                    ),
                )) {
                    c.exec("DELETE FROM $table")
                    assertEquals("false", outbox.get(coll, rid, "in")!!.value)
                    c.exec("INSERT OR IGNORE INTO $table($insert")
                    assertEquals(null, outbox.get(coll, rid, "in")!!.value)
                    assertEquals(if (coll == "member") 3 else 2, outbox.count())
                    c.exec("DELETE FROM sync_outbox")
                }
            }
        }
    }

    suspend fun cascades(db: NeutrodyneDatabase) {
        seed(db)
        val outbox = db.syncOutboxDao()
        db.withWriteTransaction {
            db.useWriterConnection { c ->
                db.syncStateDao().withApplying {
                    c.exec("UPDATE podcast_settings SET playbackSpeed=1.5")
                    c.exec("UPDATE podcast_group_settings SET playbackSpeed=1.5")
                }
                c.exec("DELETE FROM podcast_group WHERE id=1")
                val groupRows = outbox.rows()
                assertEquals("true", groupRows.single { it.coll == "group" }.value)
                val childRemovals = groupRows.filter { it.coll != "group" }
                assertTrue(childRemovals.all { it.coll == "member" && it.field == "in" && it.value == "false" })
                println("S14 group cascade capture: " + groupRows.map { "${it.coll}.${it.field}" })
                db.syncStateDao().withApplying {
                    c.exec(
                        "INSERT INTO podcast_group(id, uuid, name, nameKey, orderKey, createdAt, updatedAt) VALUES (1, '$GROUP', 'group', 'group', 'a', 1, 1)",
                    )
                    c.exec(
                        "INSERT INTO podcast_group_member(groupId, podcastId, orderKey, addedAt) VALUES (1, 1, 'a', 1)",
                    )
                }
                c.exec("DELETE FROM sync_outbox")
                c.exec("UPDATE play_session SET currentEpisodeId=1")
                c.exec("DELETE FROM sync_outbox")
                c.exec("DELETE FROM podcast WHERE id=1")
                val rows = outbox.rows()
                assertEquals("false", rows.single { it.coll == "podcast" }.value)
                assertTrue(
                    rows.filter { it.coll == "member" || it.coll == "upnext" }.all {
                        it.field == "in" &&
                            it.value == "false"
                    },
                )
                assertTrue(rows.none { it.coll == "episode" }, "retention/cascade never deletes episode state on wire")
                assertEquals("episode", rows.single { it.coll == "session" }.field)
                println("S14 cascade capture: " + rows.map { "${it.coll}.${it.field}" })
                c.exec("DELETE FROM sync_outbox")
                c.exec("DELETE FROM podcast_group WHERE id=1")
                assertEquals(
                    "true",
                    outbox
                        .rows()
                        .single()
                        .value,
                )
            }
        }
    }

    suspend fun rekeys(db: NeutrodyneDatabase) {
        seed(db)
        val outbox = db.syncOutboxDao()
        db.withWriteTransaction {
            db.useWriterConnection { c ->
                c.exec("UPDATE episode SET identityKey='g:unknown'")
                assertEquals(0, outbox.count(), "unknown keys have no wire rekey")
                val old = PODCAST + "g:unknown"
                val target = PODCAST + "g:new\"\\key"
                db.syncClockDao().upsert(SyncClockEntity("episode", old, "{\"raw\":1,\"pendingAuth\":\"protected\"}"))
                db.syncClockDao().upsert(SyncClockEntity("episode", target, "{\"pendingSetting\":2}"))
                db.syncClockDao().upsert(SyncClockEntity("upnext", old, "{\"raw\":3}"))
                outbox.upsert(SyncOutboxEntity("episode", old, "fav", 100, PEER, value = "true"))
                outbox.upsert(SyncOutboxEntity("episode", target, "fav", 100, NODE, value = "false"))
                outbox.upsert(SyncOutboxEntity("episode", old, "pos", 100, NODE, value = "old"))
                outbox.upsert(SyncOutboxEntity("episode", target, "pos", 101, NODE, value = "newer"))
                c.exec("UPDATE episode SET identityKey='g:new\"\\key'")
                assertEquals("true", outbox.get("episode", target, "fav")!!.value)
                assertEquals("newer", outbox.get("episode", target, "pos")!!.value)
                assertEquals(
                    "{\"to\":\"" + PODCAST + "g:new\\\"\\\\key\"}",
                    outbox.get("episode", old, "~rekey")!!.value,
                )
                assertEquals(3, db.syncClockDao().count(), "both colliding opaque envelopes survive")
                assertEquals("{\"raw\":3}", db.syncClockDao().get("upnext", target)!!.clocks)
                assertEquals(null, db.syncClockDao().get("upnext", old))
                assertEquals(
                    "{\"raw\":1,\"pendingAuth\":\"protected\"}",
                    db.syncClockDao().get("episode", old)!!.clocks,
                )
                // A second rekey moves the first marker too, without conflicting with its old key.
                c.exec("UPDATE episode SET identityKey='g:final'")
                assertTrue(outbox.rows().any { it.rid == target && it.field == "~rekey" })
                val conflict = PODCAST + "g:conflict"
                outbox.upsert(SyncOutboxEntity("episode", conflict, "fav", 100, PEER, value = "false"))
                val before = outbox.rows()
                assertFailsWith<Exception> { c.exec("UPDATE OR IGNORE episode SET identityKey='g:conflict'") }
                assertEquals(before, outbox.rows(), "equal-clock conflicts abort without losing either side")
                assertEquals("g:final", db.episodeDao().byId(1)!!.identityKey)
            }
        }
    }

    suspend fun rollback(db: NeutrodyneDatabase) {
        seed(db)
        val outbox = db.syncOutboxDao()
        try {
            db.withWriteTransaction {
                db.useWriterConnection { it.exec("UPDATE episode_position SET positionMs=100") }
                throw TestRollback()
            }
        } catch (_: TestRollback) {
            // Expected: neither the source write, capture nor clock may escape the transaction.
        }
        assertEquals(0L, db.positionDao().byEpisode(1)!!.positionMs)
        assertEquals(0, outbox.count())
        assertEquals(0L, db.syncStateDao().get()!!.hlc)
    }

    /** Millisecond precision probe and interleaved database-only save/bulk measurements. */
    suspend fun costs(db: NeutrodyneDatabase): String {
        seed(db)
        val outbox = db.syncOutboxDao()
        val version = db.useWriterConnection { c -> c.text("SELECT sqlite_version()") }
        val clocks = mutableSetOf<Long>()
        db.useWriterConnection { c ->
            val deadline =
                kotlin.time.TimeSource.Monotonic
                    .markNow()
            while (deadline.elapsedNow().inWholeMilliseconds < 100) {
                clocks += c.number("SELECT CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)")
            }
        }
        val precision =
            clocks
                .sorted()
                .zipWithNext()
                .map { (a, b) -> b - a }
                .minOrNull()
        assertTrue(precision == 1L, "millisecond resolution: $precision")

        suspend fun saves(
            mode: CaptureMode,
            base: Long,
        ): List<Long> {
            db.withWriteTransaction {
                db.syncStateDao().upsert(db.syncStateDao().get()!!.copy(enabled = mode == CaptureMode.ENABLED))
            }
            return (1..50).map { tick ->
                val start =
                    kotlin.time.TimeSource.Monotonic
                        .markNow()
                db.withWriteTransaction {
                    // The non-zero save branch of PositionWriter, including its safety reads.
                    assertTrue(db.episodeDao().byId(1) != null)
                    assertEquals(null, db.episodeStateDao().byEpisode(1)?.playedAt)
                    db.positionDao().insertIfAbsent(
                        EpisodePositionEntity(1, 0, positionSource = PositionSource.STREAM, updatedAt = 1),
                    )
                    db.positionDao().updateGuarded(
                        1,
                        base + tick,
                        null,
                        PositionSource.STREAM,
                        tick.toLong(),
                        pinStartedAt = 1,
                    )
                    db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = 1, updatedAt = tick.toLong()))
                    db.episodeStateDao().markStarted(1, tick.toLong())
                }
                start.elapsedNow().inWholeNanoseconds
            }
        }
        saves(CaptureMode.DISABLED, 0)
        saves(CaptureMode.ENABLED, 100)
        val off = mutableListOf<Long>()
        val on = mutableListOf<Long>()
        repeat(5) { round ->
            if (round % 2 == 0) {
                off += saves(CaptureMode.DISABLED, 1_000L * round)
                on += saves(CaptureMode.ENABLED, 1_000L * round + 100)
            } else {
                on += saves(CaptureMode.ENABLED, 1_000L * round)
                off += saves(CaptureMode.DISABLED, 1_000L * round + 100)
            }
        }
        db.withWriteTransaction {
            db.syncStateDao().upsert(db.syncStateDao().get()!!.copy(enabled = true))
            db.syncStateDao().withApplying {
                db.ingestDao().insertEpisodes(
                    (2L..5_000L).map { episodeEntity(id = it, podcastId = 1, identityKey = "g:$it") },
                )
                db.episodeStateDao().ensureAll((1L..5_000L).toList(), 1)
            }
            db.useWriterConnection { it.exec("DELETE FROM sync_outbox") }
        }
        val start =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        db.withWriteTransaction { db.episodeStateDao().markPlayed((1L..5_000L).toList(), 42) }
        val bulk = start.elapsedNow().inWholeMilliseconds
        assertEquals(10_000, outbox.count())
        val clocksByRecord =
            outbox.rows().groupBy { it.rid }.values.map { rows ->
                assertEquals(setOf("played", "playCount"), rows.map { it.field }.toSet())
                rows.map { it.hlc }.toSet().single()
            }
        assertEquals(5_000, clocksByRecord.toSet().size, "each changed row ticks within one statement")
        val afterBulk = db.syncStateDao().get()!!.hlc
        db.withWriteTransaction { assertEquals(0, db.episodeStateDao().markPlayed((1L..5_000L).toList(), 43)) }
        assertEquals(afterBulk, db.syncStateDao().get()!!.hlc)

        fun stats(samples: List<Long>): String {
            val sorted = samples.sorted()
            return "p50=${sorted[sorted.size / 2] / 1_000}us p95=${sorted[sorted.size * 95 / 100] / 1_000}us"
        }
        return "SQLite $version; precision=${precision}ms; 250 samples each, off ${stats(
            off,
        )}, on ${stats(on)}; bulk5000=${bulk}ms rows=10000; reference-phone budget OPEN"
    }

    private enum class CaptureMode { DISABLED, ENABLED }

    private class TestRollback : RuntimeException()

    private suspend fun PooledConnection.exec(sql: String) {
        usePrepared(sql) { it.step() }
    }

    private suspend fun PooledConnection.text(sql: String): String =
        usePrepared(sql) {
            check(it.step())
            it.getText(0)
        }

    private suspend fun PooledConnection.number(sql: String): Long =
        usePrepared(sql) {
            check(it.step())
            it.getLong(0)
        }
}
