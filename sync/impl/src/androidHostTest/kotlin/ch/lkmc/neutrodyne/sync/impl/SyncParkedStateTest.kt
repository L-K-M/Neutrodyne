// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 10 Parked state and stubs on the Android host: the journal, the same-transaction release on a
 * podcast or group insert, the ingest hook's release and the restart pass — the desktop suite's
 * core cases against this host's real Room.
 */
@RunWith(RobolectricTestRunner::class)
class SyncParkedStateTest {
    private val node = NodeId("0000000000000001")
    private val remote = NodeId("0000000000000002")
    private val podcastSyncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val groupUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val now = 1_800_000_000_000L

    @Test
    fun aParkedEpisodeIsJournaledAndRecoversThroughTheIngestHook() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                link(db)
                val podcastId =
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
                        listOf(
                            episodeRecord(
                                "played" to bool(true),
                                "playedAt" to int(5L),
                            ),
                        ),
                        roundId = now,
                    )
                assertThat(parked.parked).isEqualTo(1)
                assertThat(db.syncParkedDao().count()).isEqualTo(1)

                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one").copy(guid = "one")),
                        ).single()
                parkedApplier(db, applier).afterIngest(podcastId)

                assertThat(db.syncParkedDao().count()).isEqualTo(0)
                assertThat(db.episodeStateDao().byEpisode(episodeId)?.playedAt).isEqualTo(5L)
            } finally {
                db.close()
            }
        }

    @Test
    fun aPodcastArrivingLaterReleasesTheParkedMember() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                link(db)
                db.groupDao().insert(groupEntity(uuid = groupUuid, name = "News", orderKey = "a0"))
                val applier = applier(db)
                val parked =
                    applier.applyPage(listOf(memberRecord("ok" to text("a1"))), roundId = now)
                assertThat(parked.parked).isEqualTo(1)

                val released =
                    applier.applyPage(
                        listOf(podcastRecord("feedUrl" to text("https://example.com/feed"))),
                        roundId = now + 1,
                    )
                assertThat(released.applied).isEqualTo(1)
                assertThat(db.syncParkedDao().count()).isEqualTo(0)
                val group = db.groupDao().groupByUuid(groupUuid)
                assertThat(db.groupDao().membersOf(group!!.id)).hasSize(1)
            } finally {
                db.close()
            }
        }

    @Test
    fun aGroupArrivingLaterReleasesTheParkedMember() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
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
                    applier.applyPage(listOf(memberRecord("ok" to text("a1"))), roundId = now)
                assertThat(parked.parked).isEqualTo(1)

                val released =
                    applier.applyPage(
                        listOf(groupRecord("name" to text("News"), "ok" to text("a0"))),
                        roundId = now + 1,
                    )
                assertThat(released.applied).isEqualTo(1)
                assertThat(db.syncParkedDao().count()).isEqualTo(0)
                val group = db.groupDao().groupByUuid(groupUuid)
                assertThat(db.groupDao().membersOf(group!!.id)).hasSize(1)
            } finally {
                db.close()
            }
        }

    @Test
    fun aRestartPassRecoversRowsAndADeadPodcastDropsItsChildren() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                link(db)
                val podcastId =
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
                applier.applyPage(
                    listOf(episodeRecord("played" to bool(true), "playedAt" to int(5L))),
                    roundId = now,
                )
                applier.applyPage(listOf(upnextRecord("in" to bool(true), "ok" to text("a1"))), roundId = now + 1)
                assertThat(db.syncParkedDao().count()).isEqualTo(2)

                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "g:one").copy(guid = "one")),
                        ).single()
                val result = parkedApplier(db, applier).retryAll()

                assertThat(result.applied).isEqualTo(2)
                assertThat(db.syncParkedDao().count()).isEqualTo(0)
                assertThat(db.episodeStateDao().byEpisode(episodeId)?.playedAt).isEqualTo(5L)
                assertThat(db.queueDao().entries().map { it.episodeId }).containsExactly(episodeId)
            } finally {
                db.close()
            }
        }

    @Test
    fun aDeadPodcastTombstoneDropsItsChildrenWithoutParking() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                link(db)
                val applier = applier(db)
                val parked =
                    applier.applyPage(
                        listOf(episodeRecord("played" to bool(true), "playedAt" to int(5L))),
                        roundId = now,
                    )
                assertThat(parked.parked).isEqualTo(1)
                val dead =
                    applier.applyPage(listOf(podcastRecord("subscribed" to bool(false))), roundId = now + 1)
                assertThat(dead.applied).isEqualTo(1)
                assertThat(db.syncParkedDao().count()).isEqualTo(0)

                val child =
                    applier.applyPage(
                        listOf(episodeRecord("played" to bool(true), "playedAt" to int(6L), keySuffix = "two")),
                        roundId = now + 2,
                    )
                assertThat(child.stale).isEqualTo(1)
                assertThat(child.parked).isEqualTo(0)
            } finally {
                db.close()
            }
        }

    private fun parkedApplier(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        applier: SyncApplier,
    ): SyncParkedStateApplier =
        SyncParkedStateApplier(
            object : dev.zacsweers.metro.Provider<ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase> {
                override fun invoke(): ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase = db
            },
            object : dev.zacsweers.metro.Provider<SyncApplier> {
                override fun invoke(): SyncApplier = applier
            },
        )

    private fun applier(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase): SyncApplier =
        SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = FakeSecretStore(),
            settings = FakeSettingsSyncPort(),
            playback = NoPlaybackSync,
            syncIngestHook = SyncIngestHook.None,
            channelSync = NoGroupChannelSync,
            notices = SyncNoticeBus(),
            deviceNames = ClientDeviceNames { emptyList() },
            sharePasswords = { false },
            coordinator =
                ch.lkmc.neutrodyne.core.testing
                    .FakeCredentialCommitCoordinator(),
        )

    private suspend fun link(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.syncStateDao().upsert(SyncStateEntity(enabled = true, nodeId = node.value, hlc = 1L shl 16))
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

    private fun podcastRecord(vararg fields: Pair<String, FieldValue>): RecordDto =
        RecordDto(
            coll = "podcast",
            id = JsonPrimitive(podcastSyncId),
            seq = 1,
            by = null,
            fields = fields.toMap(),
        )

    private fun groupRecord(vararg fields: Pair<String, FieldValue>): RecordDto =
        RecordDto(
            coll = "group",
            id = JsonPrimitive(groupUuid),
            seq = 1,
            by = null,
            fields = fields.toMap(),
        )

    private fun memberRecord(vararg fields: Pair<String, FieldValue>): RecordDto =
        RecordDto(
            coll = "member",
            id =
                buildJsonObject {
                    put("g", groupUuid)
                    put("p", podcastSyncId)
                },
            seq = 1,
            by = null,
            fields = fields.toMap(),
        )

    private fun episodeRecord(
        vararg fields: Pair<String, FieldValue>,
        keySuffix: String = "one",
    ): RecordDto = record("episode", "g:$keySuffix", MatchHints(guid = keySuffix), *fields)

    private fun upnextRecord(
        vararg fields: Pair<String, FieldValue>,
        keySuffix: String = "one",
    ): RecordDto = record("upnext", "g:$keySuffix", MatchHints(guid = keySuffix), *fields)

    private fun record(
        coll: String,
        key: String,
        match: MatchHints?,
        vararg fields: Pair<String, FieldValue>,
    ): RecordDto =
        RecordDto(
            coll = coll,
            id =
                buildJsonObject {
                    put("p", podcastSyncId)
                    put("k", key)
                },
            seq = 1,
            by = null,
            fields = fields.toMap(),
            match = match,
            matchClocks = mapOf("guid" to clock(10).toString()),
        )

    private fun bool(value: Boolean): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun text(value: String): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun int(value: Long): FieldValue = FieldValue(JsonPrimitive(value), clock(10).toString())

    private fun clock(counter: Long): Hlc = Hlc.of(now, counter, remote)
}
