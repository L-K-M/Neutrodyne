// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 10 Passwords / 03 Commit coordination: the real-interleaving proofs for the protected
 * feed-auth boundary. The page's stage→commit span is one `CredentialCommitCoordinator`
 * hold, so the orphan sweep cannot remove a snapshot an uncommitted page is about to
 * reference; and 02's credential orphan-delete, run through the production-coordinated
 * path, cannot interleave an install's `put`→`credentialId` sequence.
 *
 * These tests run real coroutines on [Dispatchers.Default] against a file-backed WAL
 * database — the deterministic gates make the interleavings exact rather than sampled.
 * Sentinel secrets are generated values; assertions compare identity, never contents.
 */
class SyncProtectedAuthRaceTest {
    private val now = 1_800_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val localNode = NodeId("0000000000000001")
    private val account = "acct-1"
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val rid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val feedUrl = "https://a.example/feed.xml"
    private val origin = "https://a.example"
    private val user = "sync-user"
    private val pass = "s3ntinel-pass-9f8e"

    /**
     * A page parked between its protected staging `put` and the Room commit that references
     * it must not lose the snapshot to the orphan sweep: the sweep waits for the page's
     * coordinator span, and afterwards the snapshot is the marker's reference, the install
     * lands, and the credential row exists.
     */
    @Test
    fun aPageCommitSpanIsProtectedFromTheOrphanSweep() =
        runBlocking {
            val dir = createTempDirectory("nd-auth-race")
            val db = TestDb.file(dir)
            try {
                link(db)
                // The row-writing store mirrors the platform `put`: the credential row the
                // drain binds to `podcast.credentialId` really exists, like production.
                val secrets = RowWritingStore(FakeSecretStore(), db)
                val credentials = FakeCredentialCommitCoordinator()
                val h = harness(db, secrets, credentials)

                // Park any Room writer the way a slow page transaction or a racing round
                // would; the page blocks acquiring it mid-span.
                val writerHeld = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val holder =
                    launch(Dispatchers.Default) {
                        db.withWriteTransaction {
                            writerHeld.complete(Unit)
                            releaseWriter.await()
                        }
                    }
                writerHeld.await()

                val clock = Hlc.of(now, 10, remote)
                val key = pendingAuthKey(account, rid, clock.toString())
                val page =
                    launch(Dispatchers.Default) { h.applier.applyPage(listOf(authRecord()), roundId = 1) }
                withTimeout(20_000) {
                    while (key !in secrets.originsWithPrefix(PENDING_AUTH_PREFIX)) delay(10)
                }
                assertNull(
                    db.syncClockDao().get(SyncCollection.PODCAST.wire, rid),
                    "the page has not committed its reference yet",
                )
                assertTrue(page.isActive, "the page must be parked between staging and commit")

                val drain = launch(Dispatchers.Default) { h.effects.drainPasswords() }
                delay(200)
                assertTrue(
                    drain.isActive,
                    "the sweep must wait for the page's protected stage-to-commit span",
                )
                assertNotNull(
                    secrets.get(key),
                    "the sweep must not remove a snapshot the page is still committing",
                )

                releaseWriter.complete(Unit)
                page.join()
                drain.join()
                holder.join()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertNotNull(podcast.credentialId, "the staged credential installs once the page commits")
                assertNotNull(secrets.get(key), "the accepted snapshot stays referenced")
                val raw = decodeRawClocks(db.syncClockDao().get(SyncCollection.PODCAST.wire, rid)!!.clocks)!!
                assertNull(raw.pendingAuth)
                assertEquals(key, raw.authReference!!.storeKey)
            } finally {
                db.close()
                dir.toFile().deleteRecursively()
            }
        }

    /**
     * 02's credential orphan-delete, invoked through the production-coordinated cleanup path
     * (`withCredentialCommit` around `MaintenanceDao.deleteUnreferencedCredentials`), cannot
     * run between an install's `put` and its `credentialId` write: it waits on the
     * coordinator, then finds the row referenced and keeps it. The sweep's liveness is
     * proved by an unrelated orphan row it does remove.
     *
     * The row-writing store mirrors the platform boundary honestly: `FakeSecretStore.put`
     * never writes the `credential` row the real stores write, so this adapter writes it —
     * the same row the sweep's SQL sees.
     */
    @Test
    fun aCredentialOrphanSweepCannotInterleaveAnInstallsPutAndCredentialId() =
        runBlocking {
            val dir = createTempDirectory("nd-auth-install")
            val db = TestDb.file(dir)
            try {
                link(db)
                val inner = FakeSecretStore()
                val putCommitted = CompletableDeferred<Unit>()
                val releaseInstall = CompletableDeferred<Unit>()
                val secrets =
                    RowWritingStore(inner, db) { written ->
                        if (written == origin) {
                            putCommitted.complete(Unit)
                            releaseInstall.await()
                        }
                    }
                val credentials = FakeCredentialCommitCoordinator()
                val h = harness(db, secrets, credentials)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                assertNotNull(
                    decodeRawClocks(
                        db.syncClockDao().get(SyncCollection.PODCAST.wire, rid)!!.clocks,
                    )!!.pendingAuth,
                )

                // An unrelated unreferenced feed credential: the sweep must still eat it,
                // proving its delete ran for real and only the coordinator held it off.
                val orphanOrigin = "https://orphan.example"
                secrets.put(orphanOrigin, user, "s3ntinel-orphan-77c2".toCharArray())
                val orphanRow =
                    db
                        .credentialDao()
                        .byOrigin(orphanOrigin)
                        .single()
                        .id

                val drain = launch(Dispatchers.Default) { h.effects.drainPasswords() }
                withTimeout(20_000) { putCommitted.await() }
                val installedRow =
                    db
                        .credentialDao()
                        .byOrigin(origin)
                        .single()
                        .id
                assertNull(
                    db.podcastDao().bySyncId(rid)!!.credentialId,
                    "the install is parked between the put and its credentialId write",
                )

                val sweep =
                    launch(Dispatchers.Default) {
                        credentials.withCredentialCommit {
                            db.withWriteTransaction { db.maintenanceDao().deleteUnreferencedCredentials() }
                        }
                    }
                delay(200)
                assertTrue(
                    sweep.isActive,
                    "the production-coordinated sweep must wait for the install's span",
                )

                releaseInstall.complete(Unit)
                drain.join()
                sweep.join()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertEquals(installedRow, podcast.credentialId, "the install's row is the one bound")
                assertTrue(
                    db.credentialDao().byIds(listOf(installedRow)).isNotEmpty(),
                    "the installed credential row survives the sweep",
                )
                assertTrue(
                    db.credentialDao().byIds(listOf(orphanRow)).isEmpty(),
                    "the same sweep deleted the genuinely unreferenced row",
                )
            } finally {
                db.close()
                dir.toFile().deleteRecursively()
            }
        }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * A `FakeSecretStore` whose `put` also writes the `credential` Room row the platform
     * stores write — the row the orphan-delete statement targets. Feed origins only; `sync:`
     * origins stay rowless like the desktop's.
     */
    private class RowWritingStore(
        private val inner: FakeSecretStore,
        private val db: NeutrodyneDatabase,
        private val onRowWritten: suspend (String) -> Unit = {},
    ) : SecretStore by inner {
        override suspend fun put(
            origin: String,
            username: String,
            secret: CharArray,
            attributes: Map<String, String>,
        ): Long? {
            val id = inner.put(origin, username, secret, attributes)
            if (id != null && !origin.startsWith("sync:")) {
                db.credentialDao().putByOrigin(
                    CredentialEntity(
                        id = id,
                        origin = origin,
                        username = username,
                        secretCipher = null,
                        iv = null,
                        createdAt = 1_800_000_000_000L,
                    ),
                )
                onRowWritten(origin)
            }
            return id
        }
    }

    private class Harness(
        val applier: SyncApplier,
        val effects: SyncApplyEffects,
    )

    private fun harness(
        db: NeutrodyneDatabase,
        secrets: SecretStore,
        credentials: FakeCredentialCommitCoordinator,
    ): Harness =
        Harness(
            applier =
                SyncApplier(
                    db = db,
                    clock = TestClock(now),
                    secrets = secrets,
                    settings = FakeSettingsSyncPort(),
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
                    coordinator = credentials,
                    notices = SyncNoticeBus(),
                    deviceNames = ClientDeviceNames { emptyList() },
                ),
            effects =
                SyncApplyEffects(
                    db,
                    FakeSettingsSyncPort(),
                    TestSettingsWriteCoordinator(),
                    { true },
                    secrets,
                    credentials,
                    "__absent_cache__".toPath(),
                ),
        )

    private suspend fun link(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(
                    SyncStateEntity(
                        enabled = true,
                        accountId = account,
                        deviceId = deviceId,
                        nodeId = localNode.value,
                        hlc = 1L shl 16,
                        linkedAt = now,
                    ),
                )
        }
    }

    private fun authRecord(): RecordDto {
        val c = Hlc.of(now, 10, remote).toString()
        return RecordDto(
            coll = SyncCollection.PODCAST.wire,
            id = JsonPrimitive(rid),
            seq = 10,
            by = deviceId,
            fields =
                mapOf(
                    "subscribed" to FieldValue(JsonPrimitive(true), c),
                    "feedUrl" to FieldValue(JsonPrimitive(feedUrl), c),
                    "title" to FieldValue(JsonPrimitive("Feed $rid"), c),
                    "subscribedAt" to FieldValue(JsonPrimitive(now), c),
                    "credentialOrigin" to FieldValue(JsonPrimitive(origin), c),
                    "auth" to
                        FieldValue(
                            JsonObject(
                                mapOf("u" to JsonPrimitive(user), "p" to JsonPrimitive(pass)),
                            ),
                            c,
                        ),
                ),
        )
    }
}
