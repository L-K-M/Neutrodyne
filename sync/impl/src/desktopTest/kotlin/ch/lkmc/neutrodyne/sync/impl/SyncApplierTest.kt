// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 10 Pull and apply: collection writes, the mass-change guard and idempotent re-application. */
class SyncApplierTest {
    private val node = NodeId("0000000000000001")
    private val remote = NodeId("0000000000000002")
    private val podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val now = 1_800_000_000_000L
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    @Test
    fun remotePodcastIsInsertedAsPendingAndStateApplied() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(
                            podcastRecord(
                                podcastSyncId,
                                "subscribed" to bool(true),
                                "feedUrl" to text("https://example.com/feed"),
                                "title" to text("Example"),
                                "subscribedAt" to int(1L),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, result.applied)

                val podcast = db.podcastDao().bySyncId(podcastSyncId)
                assertNotNull(podcast)
                assertEquals(ch.lkmc.neutrodyne.core.model.PodcastStatus.PENDING_FIRST_FETCH, podcast.status)
                assertTrue(podcast.initialFetch)
                assertEquals("https://example.com/feed", podcast.feedUrl)
                assertNotNull(podcast.nextRefreshAt)

                val podcastId = podcast.id
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                val episode =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one", enc = "https://cdn.example.com/one.mp3"),
                                "played" to bool(true),
                                "playedAt" to int(5L),
                            ),
                        ),
                        roundId = now + 1,
                    )
                assertEquals(1, episode.applied)
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun reapplyingTheSamePageIsIdempotent() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val applier = applier(db)
                val page =
                    listOf(
                        podcastRecord(
                            podcastSyncId,
                            "subscribed" to bool(true),
                            "feedUrl" to text("https://example.com/feed"),
                            "title" to text("Example"),
                        ),
                    )
                assertEquals(1, applier.applyPage(page, roundId = now).applied)
                val second = applier.applyPage(page, roundId = now + 1)
                assertEquals(0, second.applied)
                assertEquals(1, second.stale)
                assertEquals(1, db.podcastDao().bySyncId(podcastSyncId)?.let { 1 } ?: 0)
            } finally {
                db.close()
            }
        }

    @Test
    fun aSingleRemoteUnsubscribeIsAppliedBelowTheThreshold() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(podcastRecord(podcastSyncId, "subscribed" to bool(false))),
                        roundId = now,
                    )
                assertEquals(1, result.held)
                val held = MassChangeGuard.evaluate(db, roundId = now, localPodcasts = 1)
                assertNull(held, "one removal of one is below the thresholds")
                assertNotNull(db.podcastDao().bySyncId(podcastSyncId))
            } finally {
                db.close()
            }
        }

    @Test
    fun elevenRemoteUnsubscribesAreHeldAndKeepMineRecaptures() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                repeat(50) { seedPodcast(db, "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}") }
                val applier = applier(db)
                val removals =
                    (0 until 11).map { index ->
                        val rid = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                        podcastRecord(rid, "subscribed" to bool(false))
                    }
                assertEquals(11, applier.applyPage(removals, roundId = now).held)

                val held = MassChangeGuard.evaluate(db, roundId = now, localPodcasts = 50)
                assertNotNull(held)
                assertEquals(11, held.podcasts.size)
                assertEquals(50, db.podcastDao().allForExport().size)

                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        MassChangeGuard.keepMine(db, held)
                    }
                }
                val outbox = db.syncOutboxDao().rows()
                assertTrue(outbox.any { it.coll == "podcast" && it.field == "*" })
                assertTrue(outbox.none { it.field == "subscribed" && it.value == "false" })
            } finally {
                db.close()
            }
        }

    @Test
    fun aWinningRemotePlayedMarkOverwritesAnOlderLocalPosition() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one")))
                        .single()
                db
                    .episodeStateDao()
                    .ensure(
                        ch.lkmc.neutrodyne.core.database
                            .EpisodeStateEntity(episodeId, updatedAt = 1),
                    )
                db
                    .positionDao()
                    .upsert(
                        ch.lkmc.neutrodyne.core.database.EpisodePositionEntity(
                            episodeId = episodeId,
                            positionMs = 500,
                            positionSource = ch.lkmc.neutrodyne.core.model.PositionSource.STREAM,
                            updatedAt = 1,
                        ),
                    )
                db
                    .syncOutboxDao()
                    .upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = "episode",
                            rid = "$podcastSyncId" + "g:one",
                            field = "pos",
                            hlc = 1L shl 16,
                            nodeId = node.value,
                            value = "{\"ms\":500}",
                        ),
                    )

                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(9L),
                            "pos" to pos(0, reset = true),
                        ),
                    ),
                    roundId = now,
                )
                val state = db.episodeStateDao().byEpisode(episodeId)
                assertNotNull(state)
                assertEquals(9L, state.playedAt)
                assertEquals(0L, db.positionDao().byEpisode(episodeId)?.positionMs)
            } finally {
                db.close()
            }
        }

    private fun applier(db: NeutrodyneDatabase): SyncApplier {
        val settings = FakeSettingsSyncPort()
        return SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = FakeSecretStore(),
            settings = settings,
            playback = NoPlaybackSync,
            syncIngestHook = SyncIngestHook.None,
            channelSync =
                object : GroupChannelSync {
                    override suspend fun sync() = Unit

                    override suspend fun onUuidChanged(
                        oldUuid: String,
                        newUuid: String,
                    ) = Unit

                    override suspend fun onDeleted(uuid: String) = Unit
                },
            sharePasswords = { false },
        )
    }

    private suspend fun link(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = node.value, hlc = 1L shl 16))
        }
    }

    private suspend fun seedPodcast(
        db: NeutrodyneDatabase,
        syncId: String,
    ): Long =
        db
            .podcastDao()
            .insertPodcast(
                podcastEntity(
                    syncId = syncId,
                    feedUrl = "https://example.com/$syncId",
                    feedKey = "https://example.com/$syncId",
                ),
            )

    private fun podcastRecord(
        rid: String,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto = record("podcast", JsonPrimitive(rid), null, *fields)

    private fun episodeRecord(
        podcast: String,
        key: String,
        match: MatchHints?,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto {
        val id =
            buildJsonObject {
                put("p", podcast)
                put("k", key)
            }
        return record("episode", id, match, *fields)
    }

    private fun record(
        coll: String,
        id: JsonElement,
        match: MatchHints?,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto =
        RecordDto(
            coll = coll,
            id = id,
            seq = 1,
            by = deviceId,
            fields = fields.toMap(),
            match = match,
            matchClocks = match?.let { mapOf("guid" to clock(10).toString()) } ?: emptyMap(),
        )

    private fun bool(value: Boolean): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun text(value: String): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun int(value: Long): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun pos(
        ms: Long,
        reset: Boolean = false,
    ): FieldValue = FieldValue(JsonObject(buildMap { put("ms", JsonPrimitive(ms)) }), clock(10).toString(), reset)

    private fun clock(counter: Long): Hlc = Hlc.of(now, counter, remote)
}
