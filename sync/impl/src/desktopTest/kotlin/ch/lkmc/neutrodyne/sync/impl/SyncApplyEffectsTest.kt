// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 10 Durable apply effects and Settings capture. */
class SyncApplyEffectsTest {
    private val now = 1_800_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    @Test
    fun aRemoteSettingIsJournaledWithTheCursorAndDrainedAfterCommit() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val applier = applier(db, port)
                applier.applyPage(settingPage(PlaybackSettingKeys.SKIP_BACK_MS.name, "15000"), roundId = now)

                // The page committed the cursor; the DataStore write is still pending.
                val pending = pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name)
                assertEquals("15000", pending)

                effects(db, port).drainSettings()
                assertEquals(1, port.applied.size)
                assertEquals(PlaybackSettingKeys.SKIP_BACK_MS, port.applied.single().first)
                assertNull(pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aFailedDataStoreWriteKeepsTheMarkerForTheNextDrain() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                val applier = applier(db, port)
                applier.applyPage(settingPage(PlaybackSettingKeys.SKIP_BACK_MS.name, "15000"), roundId = now)

                port.remoteResult = false
                effects(db, port).drainSettings()
                assertEquals("15000", pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))

                port.remoteResult = true
                effects(db, port).drainSettings()
                assertNull(pendingSetting(db, PlaybackSettingKeys.SKIP_BACK_MS.name))
            } finally {
                db.close()
            }
        }

    @Test
    fun aLocalSettingChangeRecordsItsLiteralIntentBeforeDataStore() =
        runTest {
            val db = TestDb.inMemory()
            try {
                link(db)
                val port = FakeSettingsSyncPort()
                SettingsCapture(db, port).install()

                db.withWriteTransaction {
                    port.recordLocal(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L)
                }
                val row = db.syncOutboxDao().get("setting", PlaybackSettingKeys.SKIP_BACK_MS.name, "v")
                assertEquals("20000", row?.value)
            } finally {
                db.close()
            }
        }

    @Test
    fun aPendingAuthInstallsThroughTheStoreWithoutACapture() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = dbBackedSecrets(db)
            try {
                link(db)
                seedPodcast(db)
                applier(db, FakeSettingsSyncPort(), secrets).applyPage(authPage(), roundId = now)

                effects(db, FakeSettingsSyncPort(), secrets).drainPasswords()

                // The credential landed under the feed origin, linked and unflagged.
                val stored = secrets.get("https://example.com/private")
                assertNotNull(stored)
                assertEquals("anna", stored.username)
                assertEquals("hunter2", stored.secret.concatToString())
                val podcast = db.podcastDao().bySyncId(PODCAST_RID)
                assertNotNull(podcast)
                assertNotNull(podcast.credentialId)
                assertEquals(false, podcast.needsCredentials)
                assertNull(rawOf(db, PODCAST_RID)?.pendingAuth)

                // AC8: the recovered effect never echoes as a fresh local edit.
                assertEquals(0, db.syncOutboxDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun aPendingAuthWaitsForThePodcastAndInstallsWhenItArrives() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = dbBackedSecrets(db)
            try {
                link(db)
                // A dead record (unknown and unsubscribed) journals its clocks and the auth
                // effect without a podcast row: the credential parks until it arrives.
                applier(db, FakeSettingsSyncPort(), secrets).applyPage(authPage(subscribed = false), roundId = now)

                effects(db, FakeSettingsSyncPort(), secrets).drainPasswords()
                assertNotNull(rawOf(db, PODCAST_RID)?.pendingAuth)
                assertNull(db.podcastDao().bySyncId(PODCAST_RID)?.credentialId)

                seedPodcast(db)
                effects(db, FakeSettingsSyncPort(), secrets).drainPasswords()
                assertNotNull(db.podcastDao().bySyncId(PODCAST_RID)?.credentialId)
                assertNull(rawOf(db, PODCAST_RID)?.pendingAuth)
            } finally {
                db.close()
            }
        }

    @Test
    fun aPendingAuthWaitsForTheSecretStoreToLoad() =
        runTest {
            val db = TestDb.inMemory()
            val loadBarrier = kotlinx.coroutines.CompletableDeferred<Unit>()
            val secrets = FakeSecretStore(loadBarrier, dbRowWriter(db))
            try {
                link(db)
                seedPodcast(db)
                // The page's staging runs through the loaded store; only the drain waits.
                applier(db, FakeSettingsSyncPort()).applyPage(authPage(), roundId = now)
                val stagedKey = rawOf(db, PODCAST_RID)!!.pendingAuth!!.reference
                secrets.put(stagedKey, "anna", "hunter2".toCharArray())

                val drain = effects(db, FakeSettingsSyncPort(), secrets)
                val draining =
                    launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        drain.drainPasswords()
                    }
                kotlinx.coroutines.yield()
                assertTrue(!draining.isCompleted, "a locked store must keep the drain waiting")
                assertNotNull(rawOf(db, PODCAST_RID)?.pendingAuth)

                loadBarrier.complete(Unit)
                draining.join()
                assertNull(rawOf(db, PODCAST_RID)?.pendingAuth)
                assertNotNull(db.podcastDao().bySyncId(PODCAST_RID)?.credentialId)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLostStagedSnapshotAbandonsTheEffectAndFlagsTheFeed() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = FakeSecretStore()
            try {
                link(db)
                seedPodcast(db)
                applier(db, FakeSettingsSyncPort(), secrets).applyPage(authPage(), roundId = now)
                val stagedKey = rawOf(db, PODCAST_RID)!!.pendingAuth!!.reference
                secrets.remove(stagedKey)

                effects(db, FakeSettingsSyncPort(), secrets).drainPasswords()

                assertNull(rawOf(db, PODCAST_RID)?.pendingAuth)
                assertEquals(true, db.podcastDao().bySyncId(PODCAST_RID)?.needsCredentials)
                assertNull(db.podcastDao().bySyncId(PODCAST_RID)?.credentialId)
            } finally {
                db.close()
            }
        }

    @Test
    fun anOrphanStagingEntryIsSweptWhileAReferencedOneStays() =
        runTest {
            val db = TestDb.inMemory()
            val secrets = FakeSecretStore()
            try {
                link(db)
                seedPodcast(db)
                applier(db, FakeSettingsSyncPort(), secrets).applyPage(authPage(), roundId = now)
                val referenced = rawOf(db, PODCAST_RID)!!.authReference!!.storeKey
                val orphan = pendingAuthOrigin("acc-1", PODCAST_RID, "ffffffffffffffff-0000000000000009")
                secrets.put(orphan, "anna", "stale".toCharArray())

                effects(db, FakeSettingsSyncPort(), secrets).sweepOrphanStaging("acc-1")

                assertNull(secrets.get(orphan))
                assertNotNull(secrets.get(referenced))
            } finally {
                db.close()
            }
        }

    /** A podcast row under [PODCAST_RID], seeded without captures. */
    private suspend fun seedPodcast(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                db
                    .podcastDao()
                    .insertPodcast(
                        podcastEntity(
                            syncId = PODCAST_RID,
                            feedUrl = "https://example.com/private",
                            feedKey = "https://example.com/private",
                        ),
                    )
            }
        }
    }

    /** Writes a real `credential` row per put, so `podcast.credentialId` links stay FK-consistent. */
    private fun dbRowWriter(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
    ): suspend (String, String) -> Long? =
        { origin, username ->
            db
                .credentialDao()
                .putByOrigin(
                    ch.lkmc.neutrodyne.core.database.CredentialEntity(
                        origin = origin,
                        username = username,
                        secretCipher = null,
                        iv = null,
                        createdAt = 1,
                    ),
                )
        }

    /** A fake over real `credential` rows (the Android store shape). */
    private fun dbBackedSecrets(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase): FakeSecretStore =
        FakeSecretStore(rowWriter = dbRowWriter(db))

    /** A podcast record whose `auth` a sharing device sent, staged through the applier. */
    private fun authPage(subscribed: Boolean = true): List<RecordDto> =
        listOf(
            RecordDto(
                coll = "podcast",
                id = JsonPrimitive(PODCAST_RID),
                seq = 1,
                by = deviceId,
                fields =
                    mapOf(
                        "subscribed" to FieldValue(JsonPrimitive(subscribed), Hlc.of(now, 1, remote).toString()),
                        "feedUrl" to
                            FieldValue(JsonPrimitive("https://example.com/private"), Hlc.of(now, 2, remote).toString()),
                        "needsCredentials" to FieldValue(JsonPrimitive(true), Hlc.of(now, 3, remote).toString()),
                        "credentialOrigin" to
                            FieldValue(JsonPrimitive("https://example.com/private"), Hlc.of(now, 4, remote).toString()),
                        "auth" to
                            FieldValue(
                                buildJsonObject {
                                    put("u", "anna")
                                    put("p", "hunter2")
                                },
                                Hlc.of(now, 5, remote).toString(),
                            ),
                    ),
            ),
        )

    private suspend fun rawOf(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        rid: String,
    ): RawSyncRecord? =
        db.syncClockDao().get("podcast", rid)?.let {
            runCatching { SyncJson.decodeFromString<RawSyncRecord>(it.clocks) }.getOrNull()
        }

    private companion object {
        const val PODCAST_RID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }

    private fun effects(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        port: FakeSettingsSyncPort,
        secrets: ch.lkmc.neutrodyne.core.domain.SecretStore = FakeSecretStore(),
    ): SyncApplyEffects =
        SyncApplyEffects(
            db = db,
            settings = port,
            secrets = secrets,
            coordinator =
                ch.lkmc.neutrodyne.core.testing
                    .FakeCredentialCommitCoordinator(),
        )

    private fun applier(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        port: FakeSettingsSyncPort,
        secrets: FakeSecretStore = FakeSecretStore(),
    ): SyncApplier =
        SyncApplier(
            db = db,
            clock = TestClock(now),
            secrets = secrets,
            settings = port,
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

    private suspend fun link(db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(SyncStateEntity(enabled = true, nodeId = "0000000000000001", hlc = 1L shl 16))
        }
    }

    private fun settingPage(
        key: String,
        value: String,
    ): List<RecordDto> =
        listOf(
            RecordDto(
                coll = "setting",
                id = JsonPrimitive(key),
                seq = 1,
                by = deviceId,
                fields = mapOf("v" to FieldValue(JsonPrimitive(value), Hlc.of(now, 1, remote).toString())),
            ),
        )

    private suspend fun pendingSetting(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        key: String,
    ): String? {
        val row = db.syncClockDao().get("setting", key) ?: return null
        val raw = runCatching { SyncJson.decodeFromString<RawSyncRecord>(row.clocks) }.getOrNull()
        return raw?.pendingSetting?.value
    }
}
