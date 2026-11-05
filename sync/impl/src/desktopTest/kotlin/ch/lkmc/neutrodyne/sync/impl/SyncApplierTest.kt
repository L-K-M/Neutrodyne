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
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
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
                        MassChangeGuard.keepMine(db, held, TestClock(now))
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

    @Test
    fun aReceivedAuthIsStagedProtectedAndJournaledAsPendingAuth() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = FakeSecretStore()
            try {
                link(db)
                seedSilently(db, podcastSyncId)
                val applier = applier(db, secrets)
                val authClock = clock(10).toString()
                applier.applyPage(
                    listOf(
                        podcastRecord(
                            podcastSyncId,
                            "needsCredentials" to bool(true),
                            "credentialOrigin" to text("https://example.com"),
                            "auth" to auth("anna", "hunter2"),
                        ),
                    ),
                    roundId = now,
                )

                // The secret lives only in the protected staging entry.
                val stagedKey = pendingAuthOrigin("acc-1", podcastSyncId, authClock)
                assertEquals("hunter2", secrets.get(stagedKey)?.secret?.concatToString())

                // Raw state carries the marker and the reference; no plaintext anywhere in Room.
                val raw = rawRecord(db, podcastSyncId)
                assertNotNull(raw)
                assertEquals(
                    stagedKey,
                    authMarkerKey(
                        raw.state.fields
                            .getValue("auth")
                            .v,
                    ),
                )
                assertEquals(stagedKey, raw.pendingAuth?.reference)
                assertEquals("https://example.com", raw.pendingAuth?.credentialOrigin)
                assertEquals(stagedKey, raw.authReference?.storeKey)
                assertFalse(
                    db
                        .syncClockDao()
                        .get("podcast", podcastSyncId)!!
                        .clocks
                        .contains("hunter2"),
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun anEqualClockRedeliveryKeepsThePendingEffectWithoutConflict() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = FakeSecretStore()
            try {
                link(db)
                seedSilently(db, podcastSyncId)
                val applier = applier(db, secrets)
                val page =
                    listOf(
                        podcastRecord(
                            podcastSyncId,
                            "needsCredentials" to bool(true),
                            "auth" to auth("anna", "hunter2"),
                        ),
                    )
                applier.applyPage(page, roundId = now)
                val second = applier.applyPage(page, roundId = now + 1)

                assertEquals(1, second.stale)
                val raw = rawRecord(db, podcastSyncId)
                assertNotNull(raw)
                assertNotNull(raw.pendingAuth, "an equal-clock redelivery must not suppress unfinished work")
                assertEquals("hunter2", secrets.get(raw.pendingAuth!!.reference)?.secret?.concatToString())
            } finally {
                db.close()
            }
        }

    @Test
    fun aPodcastNeedingAPasswordWithoutAuthIsFlaggedAndReported() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedSilently(db, podcastSyncId)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(podcastRecord(podcastSyncId, "needsCredentials" to bool(true))),
                        roundId = now,
                    )

                assertEquals(listOf(podcastId), result.passwordNeeded)
                assertEquals(true, db.podcastDao().byId(podcastId)?.needsCredentials)
            } finally {
                db.close()
            }
        }

    @Test
    fun aReceivedAuthSuppressesThePasswordNeededNotice() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                seedSilently(db, podcastSyncId)
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(
                            podcastRecord(
                                podcastSyncId,
                                "needsCredentials" to bool(true),
                                "auth" to auth("anna", "hunter2"),
                            ),
                        ),
                        roundId = now,
                    )

                assertEquals(emptyList<Long>(), result.passwordNeeded)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLosingAuthCandidateIsRemovedAndALocalWinnerIsMarkedWithTheFeedOrigin() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = FakeSecretStore()
            try {
                link(db)
                val podcastId = seedSilently(db, podcastSyncId)
                val credentialId =
                    db
                        .credentialDao()
                        .putByOrigin(
                            ch.lkmc.neutrodyne.core.database.CredentialEntity(
                                origin = "https://example.com",
                                username = "local",
                                secretCipher = null,
                                iv = null,
                                createdAt = 1,
                            ),
                        )
                val fakeRowId = secrets.put("https://example.com", "local", "local-secret".toCharArray())!!
                secrets.podcastCredentialIds.value = mapOf(podcastId to fakeRowId)
                db.podcastDao().setCredentialId(podcastId, credentialId)
                // A pending local auth row newer than the incoming remote value.
                db
                    .syncOutboxDao()
                    .upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = "podcast",
                            rid = podcastSyncId,
                            field = "auth",
                            hlc = (now + 100_000L) shl 16,
                            nodeId = node.value,
                        ),
                    )
                val applier = applier(db, secrets)
                val remoteClock = clock(10).toString()
                applier.applyPage(
                    listOf(podcastRecord(podcastSyncId, "auth" to auth("anna", "remote-secret"))),
                    roundId = now,
                )

                val raw = rawRecord(db, podcastSyncId)
                assertNotNull(raw)
                assertNull(raw.pendingAuth, "the local credential is already installed; nothing to drain")
                assertEquals(
                    "https://example.com",
                    authMarkerKey(
                        raw.state.fields
                            .getValue("auth")
                            .v,
                    ),
                )
                assertEquals("https://example.com", raw.authReference?.storeKey)
                assertNull(
                    secrets.get(pendingAuthOrigin("acc-1", podcastSyncId, remoteClock)),
                    "the losing candidate left the staging store",
                )
                assertFalse(
                    db
                        .syncClockDao()
                        .get("podcast", podcastSyncId)!!
                        .clocks
                        .contains("remote-secret"),
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun anAbsentPlayedAtKeepsTheLocalValue() =
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
                            .EpisodeStateEntity(episodeId, playedAt = 9L, updatedAt = 1),
                    )

                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "fav" to bool(true),
                        ),
                    ),
                    roundId = now,
                )
                assertEquals(9L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun anExplicitNullPlayedAtClearsTheLocalValue() =
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
                            .EpisodeStateEntity(episodeId, playedAt = 9L, updatedAt = 1),
                    )

                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(false),
                            "playedAt" to FieldValue(kotlinx.serialization.json.JsonNull, clock(10).toString()),
                        ),
                    ),
                    roundId = now,
                )
                assertNull(db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun aPodcastOnAnExistingFeedKeyIsInvalidNotAConstraintError() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val feedUrl = "https://example.com/feed/duplicate"
                db
                    .podcastDao()
                    .insertPodcast(
                        podcastEntity(
                            syncId = "00000000-0000-4000-8000-0000000000aa",
                            feedUrl = feedUrl,
                            feedKey =
                                ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
                                    .forIdentity(feedUrl)!!,
                        ),
                    )
                val applier = applier(db)
                val result =
                    applier.applyPage(
                        listOf(
                            podcastRecord(
                                podcastSyncId,
                                "subscribed" to bool(true),
                                "feedUrl" to text(feedUrl),
                                "title" to text("Duplicate feed"),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, result.invalid)
                assertNull(db.podcastDao().bySyncId(podcastSyncId))
            } finally {
                db.close()
            }
        }

    /** [seedPodcast] with the capture triggers silenced, so the seed stays out of the merge. */
    private suspend fun seedSilently(
        db: NeutrodyneDatabase,
        syncId: String,
    ): Long =
        db.withWriteTransaction {
            db.syncStateDao().withApplying { seedPodcast(db, syncId) }
        }

    private fun auth(
        username: String,
        password: String,
    ): FieldValue =
        FieldValue(
            JsonObject(
                buildMap {
                    put("u", JsonPrimitive(username))
                    put("p", JsonPrimitive(password))
                },
            ),
            clock(10).toString(),
        )

    private suspend fun rawRecord(
        db: NeutrodyneDatabase,
        rid: String,
    ): RawSyncRecord? =
        db.syncClockDao().get("podcast", rid)?.let {
            runCatching { SyncJson.decodeFromString<RawSyncRecord>(it.clocks) }.getOrNull()
        }

    private fun applier(
        db: NeutrodyneDatabase,
        secrets: FakeSecretStore = FakeSecretStore(),
    ): SyncApplier {
        val settings = FakeSettingsSyncPort()
        return SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = secrets,
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
                .upsert(SyncStateEntity(enabled = true, nodeId = node.value, hlc = 1L shl 16, accountId = "acc-1"))
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
