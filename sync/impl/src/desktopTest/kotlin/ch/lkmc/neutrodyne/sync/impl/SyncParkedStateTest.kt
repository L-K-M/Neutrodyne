// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 10 Parked state and stubs: a record whose podcast, group or episode is missing is journaled in
 * `sync_parked` instead of being dropped behind the advancing cursor, and the dependency's later
 * arrival releases it in the same transaction — no redelivery needed.
 */
class SyncParkedStateTest {
    private val node = NodeId("0000000000000001")
    private val remote = NodeId("0000000000000002")
    private val podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val groupUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val now = 1_800_000_000_000L
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    @Test
    fun anUnlinkedRetrySnapshotCannotOverwriteNewLocalState() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(5L),
                        ),
                    ),
                    roundId = now,
                )
                // The retry reads before acquiring the writer. Unlink and a new local edit
                // can complete while that writer is queued.
                val snapshot = db.syncParkedDao().all()
                assertEquals(1, snapshot.size)
                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                db.syncStateDao().unlink()
                db.episodeStateDao().ensure(
                    ch.lkmc.neutrodyne.core.database.EpisodeStateEntity(
                        episodeId = episodeId,
                        updatedAt = now + 1,
                    ),
                )
                assertEquals(null, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
                val result = applier.applyParkedRecords(snapshot)
                assertEquals(
                    null,
                    db.episodeStateDao().byEpisode(episodeId)?.playedAt,
                    "old-account work must not overwrite a newer unlinked local value",
                )
                assertEquals(0, result.applied)
                assertEquals(0, db.syncParkedDao().count())
                assertEquals(0, db.syncOutboxDao().rows().size)
            } finally {
                db.close()
            }
        }

    @Test
    fun aRetrySnapshotUsesTheLatestJournalFields() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(5L),
                        ),
                    ),
                    roundId = now,
                )
                val snapshot = db.syncParkedDao().all()
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "pos" to pos(777L),
                        ),
                    ),
                    roundId = now + 1,
                )
                assertEquals(
                    snapshot.single().id,
                    db
                        .syncParkedDao()
                        .all()
                        .single()
                        .id,
                )
                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                applier.applyParkedRecords(snapshot)
                assertEquals(
                    777L,
                    db.positionDao().byEpisode(episodeId)?.positionMs,
                    "a stale read must not discard fields added before the retry transaction",
                )
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
                assertEquals(0, db.syncParkedDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun aParkedEpisodeIsJournaledWithItsHints() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val result =
                    applier(db).applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one", enc = "https://cdn.example.com/one.mp3"),
                                "played" to bool(true),
                                "playedAt" to int(5L),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, result.parked)
                assertEquals(1, db.syncParkedDao().count())
                val row = db.syncParkedDao().forPodcast(podcastSyncId).single()
                assertEquals("g:one", row.identityKey)
                assertEquals("one", row.guid)
                // 10: enclosureKey is UrlNormalizer.forIdentity(enc), which drops the scheme.
                assertEquals(
                    ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
                        .forIdentity("https://cdn.example.com/one.mp3"),
                    row.enclosureKey,
                )
                assertTrue(row.record.contains("\"played\""), "the journal keeps the record's fields")
            } finally {
                db.close()
            }
        }

    @Test
    fun aPodcastArrivingOnALaterPageReleasesTheParkedMember() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                db.groupDao().insert(groupEntity(uuid = groupUuid, name = "News", orderKey = "a0"))
                val applier = applier(db)
                val parked =
                    applier.applyPage(
                        listOf(memberRecord(groupUuid, podcastSyncId, "ok" to text("a1"))),
                        roundId = now,
                    )
                assertEquals(1, parked.parked)
                val row = db.syncParkedDao().forPodcast(podcastSyncId).single()
                assertEquals("@member:$groupUuid", row.identityKey)

                // The podcast arrives on the next page; the member must release in that page's
                // transaction, without the member record being delivered again.
                val released =
                    applier.applyPage(
                        listOf(podcastRecord(podcastSyncId, "feedUrl" to text("https://example.com/feed"))),
                        roundId = now + 1,
                    )
                assertEquals(1, released.applied)
                assertEquals(0, db.syncParkedDao().count())
                val group = db.groupDao().groupByUuid(groupUuid)
                val podcast = db.podcastDao().bySyncId(podcastSyncId)
                assertNotNull(group)
                assertNotNull(podcast)
                assertEquals(1, db.groupDao().membersOf(group.id).size)
            } finally {
                db.close()
            }
        }

    @Test
    fun aGroupArrivingOnALaterPageReleasesTheParkedMember() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                db
                    .podcastDao()
                    .insertPodcast(
                        podcastEntity(
                            syncId = podcastSyncId,
                            feedUrl = "https://example.com/feed",
                            feedKey = "https://example.com/feed",
                        ),
                    )
                val applier = applier(db)
                val parked =
                    applier.applyPage(
                        listOf(memberRecord(groupUuid, podcastSyncId, "ok" to text("a1"))),
                        roundId = now,
                    )
                assertEquals(1, parked.parked)
                assertEquals(1, db.syncParkedDao().count())

                val released =
                    applier.applyPage(
                        listOf(groupRecord(groupUuid, "name" to text("News"), "ok" to text("a0"))),
                        roundId = now + 1,
                    )
                assertEquals(1, released.applied)
                assertEquals(0, db.syncParkedDao().count())
                val group = db.groupDao().groupByUuid(groupUuid)
                assertNotNull(group)
                assertEquals(1, db.groupDao().membersOf(group.id).size)
            } finally {
                db.close()
            }
        }

    @Test
    fun aPullThatParksAdvancesTheCursorAndKeepsTheJournal() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val episode =
                    episodeRecord(
                        podcastSyncId,
                        "g:one",
                        MatchHints(guid = "one"),
                        "played" to bool(true),
                        "playedAt" to int(5L),
                    )
                val podcast =
                    podcastRecord(podcastSyncId, "feedUrl" to text("https://example.com/feed"))
                var sinceCursor: String? = null
                val client =
                    object : ScriptedClient() {
                        override suspend fun sync(
                            since: String?,
                            changes: List<ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto>,
                        ): SyncResponse =
                            SyncResponse(
                                results = emptyList(),
                                records = listOf(episode),
                                cursor = "c:00000001:5",
                                hasMore = true,
                                serverTime = "",
                            )

                        override suspend fun changes(
                            since: String?,
                            limit: Int,
                        ): ChangesPage {
                            sinceCursor = since
                            return ChangesPage(
                                records = listOf(podcast),
                                cursor = "c:00000001:9",
                                hasMore = false,
                                serverTime = "",
                            )
                        }
                    }
                engine(db, client).round(SyncRoundReason.SYNC_NOW)

                // The cursor moved past the parked record's page…
                assertEquals("c:00000001:5", sinceCursor)
                assertEquals("c:00000001:9", db.syncStateDao().get()?.cursor)
                // …so only the journal can still carry the record.
                assertEquals(1, db.syncParkedDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun afterIngestReleasesAParkedEpisodeWithoutRedelivery() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                val parked =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one"),
                                "played" to bool(true),
                                "playedAt" to int(5L),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, parked.parked)
                assertEquals(1, db.syncParkedDao().count())

                // The feed ingest inserts the episode; the hook applies the parked record.
                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                val outboxBefore = db.syncOutboxDao().rows()
                parkedApplier(db, applier).afterIngest(podcastId)

                assertEquals(0, db.syncParkedDao().count())
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
                // No echo: the recovery wrote with applying = 1, so nothing new was captured
                // (the podcast insert's own capture happened before the hook, as local intent).
                assertEquals(outboxBefore, db.syncOutboxDao().rows())
            } finally {
                db.close()
            }
        }

    @Test
    fun disjointFieldsAccumulateAcrossPagesIntoOneRow() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(5L),
                        ),
                    ),
                    roundId = now,
                )
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "pos" to pos(777L),
                        ),
                    ),
                    roundId = now + 1,
                )
                assertEquals(1, db.syncParkedDao().count())

                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                parkedApplier(db, applier).afterIngest(podcastId)

                assertEquals(0, db.syncParkedDao().count())
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
                assertEquals(777L, db.positionDao().byEpisode(episodeId)?.positionMs)
            } finally {
                db.close()
            }
        }

    @Test
    fun anIdenticalReplayRewritesTheSameRowInsteadOfGrowingOne() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                val record =
                    episodeRecord(
                        podcastSyncId,
                        "g:one",
                        MatchHints(guid = "one"),
                        "played" to bool(true),
                        "playedAt" to int(5L),
                    )
                applier.applyPage(listOf(record), roundId = now)
                applier.applyPage(listOf(record), roundId = now + 1)
                applier.applyPage(listOf(record), roundId = now + 2)
                assertEquals(1, db.syncParkedDao().count())

                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                parkedApplier(db, applier).afterIngest(podcastId)
                assertEquals(0, db.syncParkedDao().count())
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun episodeAndUpNextRecordsOfTheSameKeyStayDistinct() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(5L),
                        ),
                    ),
                    roundId = now,
                )
                applier.applyPage(
                    listOf(upnextRecord(podcastSyncId, "g:one", "in" to bool(true), "ok" to text("a1"))),
                    roundId = now + 1,
                )
                assertEquals(2, db.syncParkedDao().count())

                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                parkedApplier(db, applier).afterIngest(podcastId)

                assertEquals(0, db.syncParkedDao().count())
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
                assertEquals(listOf(episodeId), db.queueDao().entries().map { it.episodeId })
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerLocalPositionBeatsTheParkedRemoteValue() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(9L),
                            "pos" to pos(999L),
                        ),
                    ),
                    roundId = now,
                )
                assertEquals(1, db.syncParkedDao().count())
                // The podcast and episode arrive locally, and the user's newer position intent is
                // already pending in the outbox when the recovery finally runs.
                val podcastId = seedPodcast(db, podcastSyncId)
                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                db.syncOutboxDao().upsert(
                    ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                        coll = "episode",
                        rid = podcastSyncId + "g:one",
                        field = "pos",
                        hlc = Hlc.of(now + 1000L, 1, node).packed,
                        nodeId = node.value,
                        value = "{\"ms\":500}",
                    ),
                )

                parkedApplier(db, applier).retryAll()

                // The remote played mark won (nothing local opposed it)…
                assertEquals(9L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
                // …but the newer local position survived the parked record's older one.
                assertEquals(500L, db.positionDao().byEpisode(episodeId)?.positionMs)
                assertEquals(0, db.syncParkedDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun aRolledBackRecoveryRetainsTheJournalAndTheProjection() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(5L),
                        ),
                    ),
                    roundId = now,
                )
                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")

                // A failure anywhere in the retry's transaction rolls the whole pass back: the
                // journal row and the untouched projection both survive.
                runCatching {
                    db.withWriteTransaction {
                        db.syncStateDao().withApplying {
                            applier.applyParkedRows(db.syncParkedDao().all())
                            throw Rollback()
                        }
                    }
                }.onFailure { failure ->
                    if (failure !is Rollback) throw failure
                }

                assertEquals(1, db.syncParkedDao().count())
                assertEquals(null, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnresolvedRetryKeepsTheRowForTheNextDependency() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:two",
                            MatchHints(guid = "two"),
                            "played" to bool(true),
                            "playedAt" to int(5L),
                        ),
                    ),
                    roundId = now,
                )
                // Only g:one is ingested; the g:two row must stay parked, honestly unresolved.
                ingestEpisode(db, podcastId, "g:one", guid = "one")
                val result = parkedApplier(db, applier).retryPodcast(podcastSyncId)

                assertEquals(1, result.parked)
                assertEquals(0, result.applied)
                assertEquals(1, db.syncParkedDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun aRestartPassRecoversRowsStrandedAfterTheIngestCommit() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                applier.applyPage(
                    listOf(
                        episodeRecord(
                            podcastSyncId,
                            "g:one",
                            MatchHints(guid = "one"),
                            "played" to bool(true),
                            "playedAt" to int(5L),
                        ),
                    ),
                    roundId = now,
                )
                // Crash between the ingest commit and the hook: no afterIngest ever ran.
                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                val result = parkedApplier(db, applier).retryAll()

                assertEquals(1, result.applied)
                assertEquals(0, db.syncParkedDao().count())
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun firstLinkMergerRowsRecoverThroughTheHook() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                // Exactly the row FirstLinkMerger.park writes for an unmatched staged episode.
                val dto =
                    episodeRecord(
                        podcastSyncId,
                        "g:one",
                        MatchHints(guid = "one", enc = "https://cdn.example.com/one.mp3"),
                        "played" to bool(true),
                        "playedAt" to int(5L),
                    )
                db.syncParkedDao().park(
                    ch.lkmc.neutrodyne.core.database.SyncParkedEntity(
                        podcastSyncId = podcastSyncId,
                        identityKey = "g:one",
                        guid = "one",
                        enclosureKey =
                            ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
                                .forIdentity("https://cdn.example.com/one.mp3"),
                        record =
                            ch.lkmc.neutrodyne.sync.protocol.SyncJson
                                .encodeToString(
                                    ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
                                        .serializer(),
                                    dto,
                                ),
                        receivedAt = now,
                    ),
                )

                val episodeId = ingestEpisode(db, podcastId, "g:one", guid = "one")
                parkedApplier(db, applier(db)).afterIngest(podcastId)

                assertEquals(0, db.syncParkedDao().count())
                assertEquals(5L, db.episodeStateDao().byEpisode(episodeId)?.playedAt)
            } finally {
                db.close()
            }
        }

    @Test
    fun aDeadPodcastDropsItsChildrenAndDeletesItsParkedRows() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val applier = applier(db)
                // A live record parks first; the tombstone then takes the row with it.
                val parked =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one"),
                                "played" to bool(true),
                                "playedAt" to int(5L),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, parked.parked)
                val dead =
                    applier.applyPage(
                        listOf(podcastRecord(podcastSyncId, "subscribed" to bool(false))),
                        roundId = now + 1,
                    )
                assertEquals(1, dead.applied)
                assertEquals(0, db.syncParkedDao().count())

                // With the tombstone's clocks recorded, later children drop instead of parking.
                val child =
                    applier.applyPage(
                        listOf(
                            episodeRecord(
                                podcastSyncId,
                                "g:two",
                                MatchHints(guid = "two"),
                                "played" to bool(true),
                                "playedAt" to int(6L),
                            ),
                        ),
                        roundId = now + 2,
                    )
                assertEquals(1, child.stale)
                assertEquals(0, child.parked)
                assertEquals(0, db.syncParkedDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun aDeadGroupOrARemovalDropsInsteadOfParkingAMember() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                seedPodcast(db, podcastSyncId)
                val applier = applier(db)
                val deadGroup =
                    applier.applyPage(
                        listOf(groupRecord(groupUuid, "deleted" to bool(true))),
                        roundId = now,
                    )
                assertEquals(1, deadGroup.applied)
                val member =
                    applier.applyPage(
                        listOf(memberRecord(groupUuid, podcastSyncId, "ok" to text("a1"))),
                        roundId =
                            now + 1,
                    )
                assertEquals(1, member.stale)
                assertEquals(0, db.syncParkedDao().count())

                // A membership removal for unknown ends has nothing to park either.
                val removal =
                    applier.applyPage(
                        listOf(
                            memberRecord(
                                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                                podcastSyncId,
                                "in" to bool(false),
                            ),
                        ),
                        roundId = now + 2,
                    )
                assertEquals(1, removal.stale)
                assertEquals(0, db.syncParkedDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun anUnresolvedUpNextReferenceWithHintsStubsInsteadOfParking() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val podcastId = seedPodcast(db, podcastSyncId)
                val result =
                    applier(db).applyPage(
                        listOf(
                            upnextRecord(
                                podcastSyncId,
                                "g:one",
                                MatchHints(guid = "one", enc = "https://cdn.example.com/one.mp3"),
                                "in" to bool(true),
                                "ok" to text("a1"),
                            ),
                        ),
                        roundId = now,
                    )
                assertEquals(1, result.applied)
                assertEquals(0, db.syncParkedDao().count())
                val episode = db.episodeDao().byIdentityKey(podcastId, "g:one")
                assertNotNull(episode)
                assertEquals(false, episode.inFeed)
                assertEquals(listOf(episode.id), db.queueDao().entries().map { it.episodeId })
            } finally {
                db.close()
            }
        }

    /** Only `sync`/`changes` matter here; the scripted subclasses fill them in. */
    private open class ScriptedClient : SyncClient {
        override suspend fun discover(baseUrl: String): ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument =
            unsupported()

        override suspend fun login(
            baseUrl: String,
            username: String,
            password: String,
            device: ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto,
        ): ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse = unsupported()

        override suspend fun redeemInvite(
            baseUrl: String,
            code: String,
            device: ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto,
        ): ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse = unsupported()

        override suspend fun linkStart(
            baseUrl: String,
            device: ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto,
        ): ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse = unsupported()

        override suspend fun linkToken(
            baseUrl: String,
            deviceCode: String,
        ): ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse = unsupported()

        override suspend fun linkApprove(
            baseUrl: String,
            token: String,
            userCode: String,
        ): ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse = unsupported()

        override suspend fun linkConfirm(
            baseUrl: String,
            token: String,
            userCode: String,
            approve: Boolean,
        ) = unsupported()

        override suspend fun logout() = unsupported()

        override suspend fun devices(): List<ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto> = unsupported()

        override suspend fun renameDevice(
            deviceId: String,
            name: String,
        ): ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto = unsupported()

        override suspend fun revokeDevice(deviceId: String) = unsupported()

        override suspend fun account(): ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary = unsupported()

        override suspend fun sync(
            since: String?,
            changes: List<ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto>,
        ): SyncResponse = unsupported()

        override suspend fun changes(
            since: String?,
            limit: Int,
        ): ChangesPage = unsupported()

        override suspend fun reset(
            confirm: String,
            mode: String,
            beforeCursor: String?,
        ): ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary = unsupported()

        private fun unsupported(): Nothing = error("not scripted")
    }

    private fun applier(
        db: NeutrodyneDatabase,
        settingsPort: FakeSettingsSyncPort = FakeSettingsSyncPort(),
    ): SyncApplier =
        SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = FakeSecretStore(),
            settings = settingsPort,
            playback = NoPlaybackSync,
            syncIngestHook = SyncIngestHook.None,
            channelSync = NoGroupChannelSync,
            sharePasswords = { false },
            coordinator =
                ch.lkmc.neutrodyne.core.testing
                    .FakeCredentialCommitCoordinator(),
        )

    private fun parkedApplier(
        db: NeutrodyneDatabase,
        applier: SyncApplier,
    ): SyncParkedStateApplier =
        SyncParkedStateApplier(
            object : dev.zacsweers.metro.Provider<NeutrodyneDatabase> {
                override fun invoke(): NeutrodyneDatabase = db
            },
            object : dev.zacsweers.metro.Provider<SyncApplier> {
                override fun invoke(): SyncApplier = applier
            },
        )

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

    private suspend fun ingestEpisode(
        db: NeutrodyneDatabase,
        podcastId: Long,
        identityKey: String,
        guid: String,
    ): Long =
        db
            .ingestDao()
            .insertEpisodes(
                listOf(
                    ch.lkmc.neutrodyne.core.testing.database
                        .episodeEntity(
                            podcastId = podcastId,
                            identityKey = identityKey,
                        ).copy(guid = guid),
                ),
            ).single()

    private class Rollback : Exception("rolled back")

    private fun engine(
        db: NeutrodyneDatabase,
        client: SyncClient,
    ): SyncEngine {
        val settingsPort = FakeSettingsSyncPort()
        val writeLocks = TestSettingsWriteCoordinator()
        return SyncEngine(
            db = db,
            client = client,
            applier = applier(db, settingsPort),
            clock = TestClock(now),
            secrets = FakeSecretStore(),
            sharePasswords = { false },
            address =
                object : ch.lkmc.neutrodyne.core.domain.SyncServerAddress {
                    override suspend fun serverUrl(): String = "https://sync.example"

                    override suspend fun username(): String? = null

                    override suspend fun deviceName(): String? = null

                    override suspend fun shareFeedPasswords(): Boolean = false

                    override suspend fun syncPlaybackSettings(): Boolean = true

                    override suspend fun setServerUrl(url: String?) = Unit

                    override suspend fun setUsername(username: String?) = Unit

                    override suspend fun setShareFeedPasswords(enabled: Boolean) = Outcome.Success(Unit)

                    override suspend fun setSyncPlaybackSettings(enabled: Boolean) = Outcome.Success(Unit)
                },
            tokens =
                SyncTokenStore(FakeSecretStore()).apply {
                    kotlinx.coroutines.runBlocking { save("sync.example", "token") }
                },
            activity = SyncActivityState(),
            notices = SyncNoticeBus(),
            gate = FakeLocalNetworkGate(),
            effects =
                SyncApplyEffects(
                    db,
                    settingsPort,
                    writeLocks,
                    { true },
                    FakeSecretStore(),
                    ch.lkmc.neutrodyne.core.testing
                        .FakeCredentialCommitCoordinator(),
                    okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "nd-parked-no-auth-cache",
                ),
            capture = SettingsCapture(db, settingsPort, writeLocks) { true },
        )
    }

    private suspend fun link(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = node.value, hlc = 1L shl 16))
        }
    }

    private object NoGroupChannelSync : GroupChannelSync {
        override suspend fun sync() = Unit

        override suspend fun onUuidChanged(
            oldUuid: String,
            newUuid: String,
        ) = Unit

        override suspend fun onDeleted(uuid: String) = Unit
    }

    private fun podcastRecord(
        rid: String,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto = record("podcast", JsonPrimitive(rid), null, *fields)

    private fun groupRecord(
        uuid: String,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto = record("group", JsonPrimitive(uuid), null, *fields)

    private fun memberRecord(
        group: String,
        podcast: String,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto =
        record(
            "member",
            buildJsonObject {
                put("g", group)
                put("p", podcast)
            },
            null,
            *fields,
        )

    private fun episodeRecord(
        podcast: String,
        key: String,
        match: MatchHints?,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto =
        record(
            "episode",
            buildJsonObject {
                put("p", podcast)
                put("k", key)
            },
            match,
            *fields,
        )

    private fun upnextRecord(
        podcast: String,
        key: String,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto = upnextRecord(podcast, key, null, *fields)

    private fun upnextRecord(
        podcast: String,
        key: String,
        match: MatchHints?,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto =
        record(
            "upnext",
            buildJsonObject {
                put("p", podcast)
                put("k", key)
            },
            match,
            *fields,
        )

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
