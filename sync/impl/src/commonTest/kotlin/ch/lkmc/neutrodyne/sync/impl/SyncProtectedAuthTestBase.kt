// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 10 Passwords: the protected feed-auth boundary — wire `{u,p}` never reaches raw state, the
 * snapshot stages before Room writes, and the durable `pendingAuth` marker drives the
 * `SecretStore`/`credentialId` commit under the credential coordinator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SyncProtectedAuthTestBase {
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

    @Test
    fun wireAuthNeverReachesRawState() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val clock = Hlc.of(now, 10, remote)

                assertEquals(1, h.applier.applyPage(listOf(authRecord()), roundId = 1).applied)

                val row = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid)!!
                assertFalse(row.clocks.contains("s3ntinel"))
                assertFalse(row.clocks.contains(user))
                val raw = decodeRawClocks(row.clocks)!!
                assertNull(raw.state.fields["auth"])
                assertEquals(JsonPrimitive(origin), raw.state.fields["credentialOrigin"]?.v)

                val key = pendingAuthKey(account, rid, clock.toString())
                assertEquals(AuthReference(key, origin, clock.toString()), raw.authReference)
                assertEquals(PendingAuth(key, origin, clock.toString()), raw.pendingAuth)
                val staged = h.secrets.get(key)
                assertNotNull(staged)
                assertEquals(user, staged.username)
                assertEquals(pass, staged.secret.concatToString())
            } finally {
                db.close()
            }
        }

    @Test
    fun aStoreFailureBeforeStagingDropsNoRawState() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.secrets.failure = IllegalStateException("store locked")

                assertFailsWith<IllegalStateException> {
                    h.applier.applyPage(listOf(authRecord()), roundId = 1)
                }

                assertEquals(0, db.syncClockDao().count())
                assertNull(db.podcastDao().bySyncId(rid))
                assertTrue(h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX).isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun drainInstallsTheCredentialAndClearsTheMarker() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                val staged = decodeRawClocks(clocksJson(db))!!.pendingAuth!!.reference
                seedCredential(db, h.secrets, origin)

                h.effects.drainPasswords()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertNotNull(podcast.credentialId)
                assertFalse(podcast.needsCredentials)
                val stored = h.secrets.get(origin)
                assertNotNull(stored)
                assertEquals(user, stored.username)
                assertEquals(pass, stored.secret.concatToString())

                val raw = decodeRawClocks(clocksJson(db))!!
                assertNull(raw.pendingAuth)
                val ref = raw.authReference!!
                // The accepted snapshot stays the reference — the exact bytes accepted at the
                // winning complete clock, immune to later edits of the live credential.
                assertEquals(staged, ref.storeKey)
                assertEquals(origin, ref.credentialOrigin)
                assertEquals(Hlc.of(now, 10, remote).toString(), ref.clock)
                val retained = h.secrets.get(staged)
                assertNotNull(retained)
                assertEquals(pass, retained.secret.concatToString())
                assertEquals(setOf(staged), h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX))
            } finally {
                db.close()
            }
        }

    @Test
    fun aCrashBetweenTheStoresRepeatsIdempotently() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                // Crash after the `put` committed but before `credentialId`: the secret is at
                // its origin, unreferenced. The next drain binds the same row, not a duplicate.
                val firstId = seedCredential(db, h.secrets, origin)

                h.effects.drainPasswords()

                assertEquals(firstId, db.podcastDao().bySyncId(rid)!!.credentialId)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)

                val commits = h.credentials.commits
                h.effects.drainPasswords()
                assertEquals(firstId, db.podcastDao().bySyncId(rid)!!.credentialId)
                // Nothing pending: only the orphan sweep still enters the coordinator.
                assertEquals(commits + 1, h.credentials.commits)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLockedStoreKeepsTheMarkerPending() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                seedCredential(db, h.secrets, origin)

                h.secrets.failure = IllegalStateException("store locked")
                assertFailsWith<IllegalStateException> { h.effects.drainPasswords() }
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
                assertNull(db.podcastDao().bySyncId(rid)!!.credentialId)

                h.secrets.failure = null
                h.effects.drainPasswords()
                assertNotNull(db.podcastDao().bySyncId(rid)!!.credentialId)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    @Test
    fun aMissingPodcastKeepsTheMarkerUntilItArrives() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val clock = Hlc.of(now, 10, remote).toString()
                val key = pendingAuthKey(account, rid, clock)
                h.secrets.put(key, user, pass.toCharArray())
                seedRaw(db, key, clock)
                seedCredential(db, h.secrets, origin)

                h.effects.drainPasswords()
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
                assertNotNull(h.secrets.get(key))

                db
                    .podcastDao()
                    .insertPodcast(
                        podcastEntity(syncId = rid) { copy(needsCredentials = true) },
                    )
                h.effects.drainPasswords()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertNotNull(podcast.credentialId)
                assertFalse(podcast.needsCredentials)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
                // The snapshot is the reference now: it stays while raw state owns it.
                assertNotNull(h.secrets.get(key))
                assertEquals(key, decodeRawClocks(clocksJson(db))!!.authReference!!.storeKey)
            } finally {
                db.close()
            }
        }

    @Test
    fun aMissingSnapshotFlagsNeedsCredentialsAndKeepsTheMarker() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                val key = decodeRawClocks(clocksJson(db))!!.pendingAuth!!.reference
                h.secrets.remove(key)

                h.effects.drainPasswords()

                assertTrue(db.podcastDao().bySyncId(rid)!!.needsCredentials)
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    @Test
    fun equalClockRedeliveryKeepsOneSnapshotAndMarker() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val page = listOf(authRecord())
                h.applier.applyPage(page, roundId = 1)
                val second = h.applier.applyPage(page, roundId = 2)

                assertEquals(1, second.stale)
                assertEquals(1, h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX).size)
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)

                seedCredential(db, h.secrets, origin)
                h.effects.drainPasswords()
                assertNotNull(db.podcastDao().bySyncId(rid)!!.credentialId)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLosingRemoteAuthDropsItsStagedSnapshot() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord(counter = 20)), roundId = 1)
                h.applier.applyPage(
                    listOf(authUpdate(counter = 10)),
                    roundId = 2,
                )

                val winner = pendingAuthKey(account, rid, Hlc.of(now, 20, remote).toString())
                assertEquals(setOf(winner), h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX))
                assertEquals(
                    winner,
                    decodeRawClocks(clocksJson(db))!!.pendingAuth!!.reference,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerLocalAuthIsNotClobberedByRemote() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                db.podcastDao().insertPodcast(podcastEntity(syncId = rid))
                val h = harness(db)
                val local = Hlc.of(now + 60_000, 0, localNode)
                val localOrigin = "https://local.example"
                captureStored(db, rid, "auth", local, "{\"u\":\"x\",\"p\":\"y\"}")
                captureStored(db, rid, "credentialOrigin", local, "\"$localOrigin\"")

                h.applier.applyPage(listOf(authUpdate(counter = 10)), roundId = 1)

                val raw = decodeRawClocks(clocksJson(db))!!
                assertNull(raw.pendingAuth)
                // The local intent is only pending: the reference keeps the last accepted
                // state until the server's acknowledgement binds the exact sent snapshot.
                assertNull(raw.authReference)
                assertNotNull(
                    db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth"),
                )
                assertTrue(h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX).isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerRemoteAuthReplacesTheInstalledCredential() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val newOrigin = "https://b.example"
                seedCredential(db, h.secrets, origin)
                seedCredential(db, h.secrets, newOrigin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val oldCredential = db.podcastDao().bySyncId(rid)!!.credentialId

                h.applier.applyPage(
                    listOf(authUpdate(origin = newOrigin, pass = "p-two", counter = 20)),
                    roundId = 2,
                )
                h.effects.drainPasswords()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertNotNull(podcast.credentialId)
                assertTrue(podcast.credentialId != oldCredential)
                assertEquals(
                    "p-two",
                    h.secrets
                        .get(newOrigin)
                        ?.secret
                        ?.concatToString(),
                )
                val raw = decodeRawClocks(clocksJson(db))!!
                assertNull(raw.pendingAuth)
                val winner = pendingAuthKey(account, rid, Hlc.of(now, 20, remote).toString())
                val ref = raw.authReference!!
                assertEquals(winner, ref.storeKey)
                assertEquals(newOrigin, ref.credentialOrigin)
                assertEquals(Hlc.of(now, 20, remote).toString(), ref.clock)
                assertEquals(setOf(winner), h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX))
            } finally {
                db.close()
            }
        }

    @Test
    fun anAcknowledgementBindsTheCompleteOriginalClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()

                val acked = Hlc.of(now + 5, 7, remote)
                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to
                                        FieldValue(authValue(user, pass), acked.toString()),
                                    "credentialOrigin" to
                                        FieldValue(JsonPrimitive(origin), acked.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )
                var ref = decodeRawClocks(clocksJson(db))!!.authReference!!
                assertEquals(acked.toString(), ref.clock)
                // The bound key is the staged snapshot of the exact bytes that were sent.
                val boundKey = pendingAuthKey(account, rid, acked.toString())
                assertEquals(boundKey, ref.storeKey)
                assertNotNull(h.secrets.get(boundKey))

                val older = Hlc.of(now, 3, remote)
                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to
                                        FieldValue(authValue(user, pass), older.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )
                ref = decodeRawClocks(clocksJson(db))!!.authReference!!
                assertEquals(acked.toString(), ref.clock)
                assertEquals(boundKey, ref.storeKey)
            } finally {
                db.close()
            }
        }

    /**
     * 10 Passwords / exact accepted-clock replay: after the remote credential installs, a
     * later local edit of the live credential must not change what a replay at the accepted
     * clock resends — the immutable snapshot bound at acceptance is the truth.
     */
    @Test
    fun anInstalledAuthReplaysItsAcceptedBytesNotTheLiveCredential() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val accepted = Hlc.of(now, 10, remote)

                // The live credential changes after acceptance — a local password rotation.
                h.secrets.put(origin, user, "s3ntinel-rotated-4a1d".toCharArray())
                captureStored(db, rid, "auth", accepted, "\"replay\"")

                val pushed =
                    ChangeBuilder(db, h.secrets) { true }
                        .build(
                            db.syncOutboxDao().rows().filter {
                                it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                            },
                        ).single { it.fields.containsKey("auth") }
                val value = pushed.fields.getValue("auth")
                assertEquals(accepted.toString(), value.c)
                // The replay reasserts the accepted snapshot, not the rotated credential.
                assertEquals(authValue(user, pass), value.v)
            } finally {
                db.close()
            }
        }

    /**
     * An acknowledgement binds the exact bytes that were sent — staged under the acknowledged
     * complete clock — even when a newer local edit raced the HTTP round. The newer outbox
     * intent survives the same atomic commit and the delayed ack cannot replace it.
     */
    @Test
    fun anAcknowledgedSendBindsTheSentBytesUnderANewerLocalEdit() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val sent = Hlc.of(now + 30, 2, localNode)
                val newer = Hlc.of(now + 90, 0, localNode)
                db.withWriteTransaction {
                    db.syncOutboxDao().upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = SyncCollection.PODCAST.wire,
                            rid = rid,
                            field = "auth",
                            hlc = newer.packed,
                            nodeId = localNode.value,
                        ),
                    )
                }
                // The credential rotated while the push was in flight: what the server
                // accepted is the older pair, carried by the acknowledged change itself.
                h.secrets.put(origin, user, "s3ntinel-rotated-6b2e".toCharArray())

                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to FieldValue(authValue(user, pass), sent.toString()),
                                    "credentialOrigin" to
                                        FieldValue(JsonPrimitive(origin), sent.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )

                val key = pendingAuthKey(account, rid, sent.toString())
                val raw = decodeRawClocks(clocksJson(db))!!
                assertEquals(AuthReference(key, origin, sent.toString()), raw.authReference)
                val staged = h.secrets.get(key)
                assertNotNull(staged)
                assertEquals(pass, staged.secret.concatToString())

                // The newer intent outlives the old ack, and a replay at the acked clock sends
                // the bytes that were accepted then — not the rotated live credential.
                assertNotNull(
                    db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth"),
                )
                db.withWriteTransaction {
                    db.syncOutboxDao().deleteField(SyncCollection.PODCAST.wire, rid, "auth")
                }
                captureStored(db, rid, "auth", sent, "\"replay\"")
                val rows =
                    db.syncOutboxDao().rows().filter {
                        it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                    }
                val replayed =
                    ChangeBuilder(db, h.secrets) { true }.build(rows).single {
                        it.fields["auth"]?.c == sent.toString()
                    }
                assertEquals(authValue(user, pass), replayed.fields.getValue("auth").v)
            } finally {
                db.close()
            }
        }

    /**
     * A snapshot the ack path staged is owned by the still-pending outbox intent until the
     * acknowledgement's atomic commit binds it — the sweep derives that ownership from the
     * row's account, rid and complete clock.
     */
    @Test
    fun aPendingOutboxIntentKeepsItsStagedSnapshotFromTheSweep() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val clock = Hlc.of(now + 30, 2, localNode)
                val key = pendingAuthKey(account, rid, clock.toString())
                h.secrets.put(key, user, pass.toCharArray())
                db.withWriteTransaction {
                    db.syncOutboxDao().upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = SyncCollection.PODCAST.wire,
                            rid = rid,
                            field = "auth",
                            hlc = clock.packed,
                            nodeId = localNode.value,
                        ),
                    )
                }

                h.effects.drainPasswords()
                assertNotNull(h.secrets.get(key), "the pending intent owns its snapshot")

                db.withWriteTransaction {
                    db.syncOutboxDao().deleteAcked(
                        SyncCollection.PODCAST.wire,
                        rid,
                        "auth",
                        clock.packed,
                        localNode.value,
                    )
                }
                h.effects.drainPasswords()
                assertNull(h.secrets.get(key), "unowned after the intent is acknowledged")
            } finally {
                db.close()
            }
        }

    @Test
    fun theShareGateControlsTheHydratedPush() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val clock = Hlc.of(now, 10, remote)
                captureStored(db, rid, "auth", clock, "\"replay\"")
                val rows =
                    db.syncOutboxDao().rows().filter {
                        it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                    }

                val gated = ChangeBuilder(db, h.secrets) { false }.build(rows)
                assertTrue(gated.isEmpty())

                val pushed =
                    ChangeBuilder(db, h.secrets) { true }.build(rows).single {
                        it.fields.containsKey("auth")
                    }
                val value = pushed.fields.getValue("auth")
                assertEquals(clock.toString(), value.c)
                assertEquals(
                    JsonObject(
                        mapOf("u" to JsonPrimitive(user), "p" to JsonPrimitive(pass)),
                    ),
                    value.v,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aFeedWithOnlyAnOriginFlagsNeedsCredentials() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val result = h.applier.applyPage(listOf(authRecord(includeAuth = false)), roundId = 1)

                assertEquals(1, result.applied)
                val podcast = db.podcastDao().bySyncId(rid)!!
                assertTrue(podcast.needsCredentials)
                assertEquals(listOf(podcast.id), result.feedsNeedingCredentials)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    @Test
    fun orphanSweepKeepsReferencedKeysAndLiveStagingFiles() =
        runTest {
            val db = newTestDb()
            val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "nd-auth-${rid.take(8)}"
            try {
                link(db)
                val h = harness(db, cacheDir = dir)
                // A pending marker whose podcast never arrived keeps its snapshot referenced.
                val clock = Hlc.of(now, 10, remote).toString()
                val referenced = pendingAuthKey(account, rid, clock)
                h.secrets.put(referenced, user, pass.toCharArray())
                seedRaw(db, referenced, clock)
                val orphan = pendingAuthKey(account, "orphan", clock)
                h.secrets.put(orphan, "u", "p".toCharArray())

                // A live first-link staging file is itself a consumer: the sweep is skipped.
                FileSystem.SYSTEM.createDirectories(dir)
                FileSystem.SYSTEM.write(dir / "sync-link-$deviceId.jsonl") { writeUtf8("{}\n") }
                h.effects.drainPasswords()
                assertEquals(
                    setOf(referenced, orphan),
                    h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX),
                )

                FileSystem.SYSTEM.delete(dir / "sync-link-$deviceId.jsonl")
                h.effects.drainPasswords()
                assertEquals(
                    setOf(referenced),
                    h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX),
                )
            } finally {
                FileSystem.SYSTEM.deleteRecursively(dir)
                db.close()
            }
        }

    @Test
    fun prefixEnumerationAndUnlinkSweepOnlyTouchStagingOrigins() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.secrets.put("https://feed.example", "u", "p".toCharArray())
                h.secrets.put("sync:example.test", "t", "s".toCharArray())
                val staged = pendingAuthKey(account, rid, "0-0000000000000000")
                h.secrets.put(staged, "u", "p".toCharArray())

                assertEquals(
                    setOf(staged),
                    h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX),
                )
                h.secrets.dropStagedAuthSnapshots()
                assertTrue(h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX).isEmpty())
                assertNotNull(h.secrets.get("https://feed.example"))
                assertNotNull(h.secrets.get("sync:example.test"))
            } finally {
                db.close()
            }
        }

    @Test
    fun aDrainWaitsForTheCredentialCoordinator() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                seedCredential(db, h.secrets, origin)

                val gate = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val holder =
                    launch {
                        h.credentials.withCredentialCommit {
                            entered.complete(Unit)
                            gate.await()
                        }
                    }
                entered.await()
                val drain = launch { h.effects.drainPasswords() }
                runCurrent()

                assertTrue(drain.isActive)
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
                assertNull(db.podcastDao().bySyncId(rid)!!.credentialId)

                gate.complete(Unit)
                drain.join()
                holder.join()
                assertNotNull(db.podcastDao().bySyncId(rid)!!.credentialId)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    // --- fixture -----------------------------------------------------------------------------------

    protected abstract fun newTestDb(): NeutrodyneDatabase

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

    private class Harness(
        val applier: SyncApplier,
        val effects: SyncApplyEffects,
        val secrets: FakeSecretStore,
        val credentials: FakeCredentialCommitCoordinator,
    )

    private fun harness(
        db: NeutrodyneDatabase,
        secrets: FakeSecretStore = FakeSecretStore(),
        credentials: FakeCredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
        cacheDir: okio.Path = "__absent_cache__".toPath(),
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
                    deviceNames = DeviceNames { emptyList() },
                ),
            effects =
                SyncApplyEffects(
                    db,
                    FakeSettingsSyncPort(),
                    TestSettingsWriteCoordinator(),
                    { true },
                    secrets,
                    credentials,
                    cacheDir,
                ),
            secrets = secrets,
            credentials = credentials,
        )

    @Test
    fun rootProbeANewerOriginMustNotReceiveAnOlderAuthSnapshot() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val movedOrigin = "https://moved.example"
                val localSecret =
                    kotlin.uuid.Uuid
                        .random()
                        .toString()
                seedCredential(db, h.secrets, movedOrigin, localSecret)
                val newer = Hlc.of(now, 20, remote).toString()
                // Valid server history: auth at10 for the former origin, then a public feed/origin
                // move at20 from a client whose password sharing is OFF. The full record retains
                // the old auth field; it is not permission to use that password at the new origin.
                val original = authRecord()
                val mixed =
                    original.copy(
                        fields =
                            original.fields +
                                mapOf(
                                    "feedUrl" to FieldValue(JsonPrimitive("$movedOrigin/feed"), newer),
                                    "credentialOrigin" to FieldValue(JsonPrimitive(movedOrigin), newer),
                                ),
                    )
                assertEquals(1, h.applier.applyPage(listOf(mixed), roundId = 1).applied)
                h.effects.drainPasswords()
                val after = h.secrets.get(movedOrigin)!!
                assertTrue(
                    after.secret.contentEquals(localSecret.toCharArray()),
                    "an auth snapshot older than the origin move must not replace the new origin's credential",
                )
            } finally {
                db.close()
            }
        }

    /**
     * The offline-client order of the same defect: a client still at origin A shares its auth
     * at 30 after the feed already moved to B at 20 — the wire record keeps A's stale
     * credentialOrigin at 10 next to the newer bare auth. The stale companion cannot prove
     * the pair: nothing installs at B, the accepted A reference stays put and the snapshot
     * the write staged is dropped.
     */
    @Test
    fun anUnpairedNewerAuthDoesNotInstallAtTheMergedOrigin() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val newOrigin = "https://b.example"
                val bSecret = "b-secret-1a2b"
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val acceptedClock = Hlc.of(now, 10, remote)
                val aCredential = db.podcastDao().bySyncId(rid)!!.credentialId
                seedCredential(db, h.secrets, newOrigin, bSecret)
                h.applier.applyPage(listOf(originMove(newOrigin, counter = 20)), roundId = 2)

                val stale = Hlc.of(now, 10, remote).toString()
                val offline = Hlc.of(now, 30, remote).toString()
                val record =
                    RecordDto(
                        coll = SyncCollection.PODCAST.wire,
                        id = JsonPrimitive(rid),
                        seq = 30,
                        by = deviceId,
                        fields =
                            mapOf(
                                "credentialOrigin" to FieldValue(JsonPrimitive(origin), stale),
                                "auth" to FieldValue(authValue(user, "offline-pass"), offline),
                            ),
                    )
                h.applier.applyPage(listOf(record), roundId = 3)
                h.effects.drainPasswords()

                val raw = decodeRawClocks(clocksJson(db))!!
                assertNull(raw.pendingAuth)
                val ref = raw.authReference!!
                assertEquals(origin, ref.credentialOrigin)
                assertEquals(acceptedClock.toString(), ref.clock)
                assertEquals(
                    setOf(pendingAuthKey(account, rid, acceptedClock.toString())),
                    h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX),
                )
                val bStored = h.secrets.get(newOrigin)
                assertNotNull(bStored)
                assertTrue(bStored.secret.contentEquals(bSecret.toCharArray()))
                val podcast = db.podcastDao().bySyncId(rid)!!
                assertEquals(aCredential, podcast.credentialId)
                assertTrue(podcast.needsCredentials)
            } finally {
                db.close()
            }
        }

    /**
     * The positive side of the proof: an auth that arrives with its companion origin at the
     * same complete clock wins and installs at that origin, even when it rewrote a newer
     * independent origin.
     */
    @Test
    fun aPairedAuthAndOriginWriteInstallsAtItsOwnOrigin() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val newOrigin = "https://b.example"
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                seedCredential(db, h.secrets, newOrigin)
                h.applier.applyPage(listOf(originMove(newOrigin, counter = 20)), roundId = 2)

                val paired = Hlc.of(now, 30, remote)
                h.applier.applyPage(
                    listOf(authUpdate(origin = newOrigin, pass = "p-three", counter = 30)),
                    roundId = 3,
                )
                h.effects.drainPasswords()

                val raw = decodeRawClocks(clocksJson(db))!!
                assertNull(raw.pendingAuth)
                val ref = raw.authReference!!
                assertEquals(newOrigin, ref.credentialOrigin)
                assertEquals(paired.toString(), ref.clock)
                val stored = h.secrets.get(newOrigin)
                assertNotNull(stored)
                assertTrue(stored.secret.contentEquals("p-three".toCharArray()))
                assertFalse(db.podcastDao().bySyncId(rid)!!.needsCredentials)
            } finally {
                db.close()
            }
        }

    /**
     * A pending effect proven for origin A must not install after the winning origin moved
     * to B: the drain never sets the A credential on the feed, and the flag the pending
     * marker suppressed now reports the feed still needs a credential for B.
     */
    @Test
    fun aPendingEffectForTheOldOriginCannotInstallAfterTheMove() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val newOrigin = "https://b.example"
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.applier.applyPage(listOf(originMove(newOrigin, counter = 20)), roundId = 2)

                h.effects.drainPasswords()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertNull(podcast.credentialId)
                assertTrue(podcast.needsCredentials)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    /**
     * 10 Passwords / origin association, producer side: a local `auth` intent pushed while
     * sharing is on carries its credential's origin as a companion at the same complete
     * clock, so a receiver can prove the pair.
     */
    @Test
    fun anAuthPushCarriesItsOwnOriginAtTheSameClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val podcastId =
                    db.podcastDao().insertPodcast(podcastEntity(syncId = rid))
                val credentialId = seedCredential(db, h.secrets, origin)
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        db.podcastDao().setCredentialId(podcastId, credentialId)
                    }
                }
                val clock = Hlc.of(now + 30, 2, localNode)
                db.withWriteTransaction {
                    db.syncOutboxDao().upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = SyncCollection.PODCAST.wire,
                            rid = rid,
                            field = "auth",
                            hlc = clock.packed,
                            nodeId = localNode.value,
                        ),
                    )
                }

                val pushed =
                    ChangeBuilder(db, h.secrets) { true }
                        .build(db.syncOutboxDao().rows())
                        .single { it.fields.containsKey("auth") }
                val auth = pushed.fields.getValue("auth")
                assertEquals(clock.toString(), auth.c)
                assertEquals(authValue(user, pass), auth.v)
                val companion = pushed.fields["credentialOrigin"]
                assertNotNull(companion)
                assertEquals(clock.toString(), companion.c)
                assertEquals(JsonPrimitive(origin), companion.v)
            } finally {
                db.close()
            }
        }

    /**
     * A replay re-asserts the accepted pair, not whatever the live credential became: the
     * companion rides at the reference's own complete clock and carries its proven origin.
     */
    @Test
    fun aReplayCarriesTheAcceptedOriginAtTheAcceptedClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val accepted = Hlc.of(now, 10, remote)

                h.secrets.put(origin, user, "rotated-live".toCharArray())
                captureStored(db, rid, "auth", accepted, "\"replay\"")

                val pushed =
                    ChangeBuilder(db, h.secrets) { true }
                        .build(
                            db.syncOutboxDao().rows().filter {
                                it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                            },
                        ).single { it.fields.containsKey("auth") }
                val auth = pushed.fields.getValue("auth")
                assertEquals(accepted.toString(), auth.c)
                assertEquals(authValue(user, pass), auth.v)
                val companion = pushed.fields["credentialOrigin"]
                assertNotNull(companion)
                assertEquals(accepted.toString(), companion.c)
                assertEquals(JsonPrimitive(origin), companion.v)
            } finally {
                db.close()
            }
        }

    /**
     * A replay row at a clock the bound reference does not own must not hydrate the live
     * pair or a mismatched snapshot under a different write's clock — the field is omitted.
     */
    @Test
    fun aReplayAtAnUnboundClockOmitsAuth() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val bound = Hlc.of(now, 10, remote)
                val key = pendingAuthKey(account, rid, bound.toString())
                h.secrets.put(key, user, pass.toCharArray())
                seedRawReference(db, key, origin, bound.toString())

                val other = Hlc.of(now, 40, remote)
                captureStored(db, rid, "auth", other, "\"replay\"")
                val pushed =
                    ChangeBuilder(db, h.secrets) { true }
                        .build(
                            db.syncOutboxDao().rows().filter {
                                it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                            },
                        )
                val change = pushed.singleOrNull { it.coll == SyncCollection.PODCAST.wire }
                assertTrue(change == null || !change.fields.containsKey("auth"))
            } finally {
                db.close()
            }
        }

    /**
     * A newer pending credentialOrigin outbox row is not acknowledged by the pair's clock:
     * the companion stamps the auth's proven origin at the auth's clock in the change, and
     * the sent-clock ACK leaves the newer origin intent pending for the next round.
     */
    @Test
    fun aCoalescedNewerOriginSurvivesThePairsAck() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val bound = Hlc.of(now, 10, remote)
                val key = pendingAuthKey(account, rid, bound.toString())
                h.secrets.put(key, user, pass.toCharArray())
                seedRawReference(db, key, origin, bound.toString())
                val newerOrigin = "https://d.example"
                val newer = Hlc.of(now, 40, remote)
                captureStored(db, rid, "auth", bound, "\"replay\"")
                db.withWriteTransaction {
                    db.syncOutboxDao().upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = SyncCollection.PODCAST.wire,
                            rid = rid,
                            field = "credentialOrigin",
                            hlc = newer.packed,
                            nodeId = localNode.value,
                            value = "\"$newerOrigin\"",
                        ),
                    )
                }

                val pushed =
                    ChangeBuilder(db, h.secrets) { true }
                        .build(
                            db.syncOutboxDao().rows().filter {
                                it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                            },
                        ).single { it.fields.containsKey("auth") }
                // The sent origin is the auth's proven companion at the auth's clock — the
                // newer pending intent is not lent out under the pair's clock.
                val companion = pushed.fields["credentialOrigin"]
                assertNotNull(companion)
                assertEquals(bound.toString(), companion.c)
                assertEquals(JsonPrimitive(origin), companion.v)

                h.effects.acknowledge(listOf(pushed), listOf(ChangeResultDto(0, "applied")))

                val pending = db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "credentialOrigin")
                assertNotNull(pending)
                assertEquals(newer.packed, pending.hlc)
                assertNull(db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth"))
            } finally {
                db.close()
            }
        }

    /**
     * An acknowledgement binds only the proven sent pair — the companion the change carried
     * at the auth's own clock — never the merged or live origin it finds around it.
     */
    @Test
    fun anAckWithoutAProvenPairBindsNothing() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val bound = Hlc.of(now, 10, remote)
                val key = pendingAuthKey(account, rid, bound.toString())
                h.secrets.put(key, user, pass.toCharArray())
                seedRawReference(db, key, origin, bound.toString())

                val unpaired = Hlc.of(now, 20, remote)
                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to
                                        FieldValue(authValue(user, pass), unpaired.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )
                var ref = decodeRawClocks(clocksJson(db))!!.authReference!!
                assertEquals(key, ref.storeKey)
                assertEquals(origin, ref.credentialOrigin)
                assertEquals(bound.toString(), ref.clock)

                // The proven pair still governs: the sent companion binds, never the merged
                // or live origin — here the record's winning origin stays A while the ack
                // carried the pair for X.
                val sentOrigin = "https://x.example"
                val sent = Hlc.of(now, 30, remote)
                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to
                                        FieldValue(authValue(user, pass), sent.toString()),
                                    "credentialOrigin" to
                                        FieldValue(JsonPrimitive(sentOrigin), sent.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )
                ref = decodeRawClocks(clocksJson(db))!!.authReference!!
                assertEquals(pendingAuthKey(account, rid, sent.toString()), ref.storeKey)
                assertEquals(sentOrigin, ref.credentialOrigin)
                assertEquals(sent.toString(), ref.clock)
            } finally {
                db.close()
            }
        }

    /**
     * One push ACKs both collections: the settings merge (sent value, baseline, metadata) and
     * the auth binding land in the same Room commit, and neither outbox row survives.
     */
    @Test
    fun anAuthAndASettingAcknowledgeInOnePushWithoutMetadataLoss() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()

                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val sentSetting = Hlc.of(now + 40, 2, localNode)
                val sentAuth = Hlc.of(now + 41, 3, localNode)
                db.withWriteTransaction {
                    db.syncOutboxDao().captureStored(
                        SyncCollection.SETTING.wire,
                        key.name,
                        "v",
                        sentSetting.packed,
                        sentSetting.nodeId.value,
                        "20000",
                    )
                    db
                        .syncOutboxDao()
                        .upsert(
                            ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                                coll = SyncCollection.PODCAST.wire,
                                rid = rid,
                                field = "auth",
                                hlc = sentAuth.packed,
                                nodeId = sentAuth.nodeId.value,
                            ),
                        )
                }
                db
                    .syncClockDao()
                    .upsert(
                        ch.lkmc.neutrodyne.core.database.SyncClockEntity(
                            SyncCollection.SETTING.wire,
                            key.name,
                            SyncJson.encodeToString(
                                RawSyncRecord(
                                    state = RecordState(SyncCollection.SETTING),
                                    by = "tester",
                                    settingIntent =
                                        SettingIntent(
                                            clock = sentSetting.toString(),
                                            priorReceipt = "receipt",
                                            priorHash = settingBaselineHash("15000"),
                                        ),
                                    pendingSetting =
                                        PendingSetting(
                                            "99999",
                                            Hlc.of(now + 90, 5, remote).toString(),
                                        ),
                                ),
                            ),
                        ),
                    )

                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.SETTING.wire,
                            id = JsonPrimitive(key.name),
                            fields =
                                mapOf(
                                    "v" to FieldValue(JsonPrimitive(20000), sentSetting.toString()),
                                ),
                        ),
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to
                                        FieldValue(authValue(user, pass), sentAuth.toString()),
                                    "credentialOrigin" to
                                        FieldValue(JsonPrimitive(origin), sentAuth.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied"), ChangeResultDto(1, "applied")),
                )

                assertNull(db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v"))
                assertNull(db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth"))

                val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)!!
                val raw = SyncJson.decodeFromString<RawSyncRecord>(row.clocks)
                assertEquals(JsonPrimitive(20000), raw.state.fields["v"]?.v)
                assertEquals(
                    sentSetting.toString(),
                    raw.state.fields["v"]
                        ?.c
                        ?.toString(),
                )
                assertEquals(settingBaselineHash("20000"), raw.settingHash?.hash)
                assertEquals(sentSetting.toString(), raw.settingHash?.clock)
                assertEquals("tester", raw.by)
                assertEquals("receipt", raw.settingIntent?.priorReceipt)
                assertEquals("99999", raw.pendingSetting?.value)

                val authRaw = decodeRawClocks(clocksJson(db))!!
                val bound = pendingAuthKey(account, rid, sentAuth.toString())
                assertEquals(AuthReference(bound, origin, sentAuth.toString()), authRaw.authReference)
                assertNotNull(h.secrets.get(bound))
            } finally {
                db.close()
            }
        }

    /**
     * The staging phase runs before the Room write: a SecretStore failure while staging the
     * sent auth pair rolls back before any outbox delete, so the setting intent and its raw
     * marker survive for the retry.
     */
    @Test
    fun aFailedAuthStageCannotPartiallyDeleteTheSettingsAck() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()

                val key = PlaybackSettingKeys.SKIP_BACK_MS
                val sentSetting = Hlc.of(now + 40, 2, localNode)
                val sentAuth = Hlc.of(now + 41, 3, localNode)
                db.withWriteTransaction {
                    db.syncOutboxDao().captureStored(
                        SyncCollection.SETTING.wire,
                        key.name,
                        "v",
                        sentSetting.packed,
                        sentSetting.nodeId.value,
                        "20000",
                    )
                    db
                        .syncOutboxDao()
                        .upsert(
                            ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                                coll = SyncCollection.PODCAST.wire,
                                rid = rid,
                                field = "auth",
                                hlc = sentAuth.packed,
                                nodeId = sentAuth.nodeId.value,
                            ),
                        )
                }

                val changes =
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to
                                        FieldValue(authValue(user, pass), sentAuth.toString()),
                                    "credentialOrigin" to
                                        FieldValue(JsonPrimitive(origin), sentAuth.toString()),
                                ),
                        ),
                        ChangeDto(
                            coll = SyncCollection.SETTING.wire,
                            id = JsonPrimitive(key.name),
                            fields =
                                mapOf(
                                    "v" to FieldValue(JsonPrimitive(20000), sentSetting.toString()),
                                ),
                        ),
                    )
                val results = listOf(ChangeResultDto(0, "applied"), ChangeResultDto(1, "applied"))

                h.secrets.failure = IllegalStateException("store locked")
                assertFailsWith<IllegalStateException> { h.effects.acknowledge(changes, results) }
                assertNotNull(
                    db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v"),
                    "the setting intent survives a failed auth stage",
                )
                assertNotNull(db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth"))
                assertNull(db.syncClockDao().get(SyncCollection.SETTING.wire, key.name))

                h.secrets.failure = null
                h.effects.acknowledge(changes, results)
                assertNull(db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v"))
                assertNull(db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth"))
            } finally {
                db.close()
            }
        }

    private suspend fun clocksJson(db: NeutrodyneDatabase): String? =
        db.syncClockDao().get(SyncCollection.PODCAST.wire, rid)?.clocks

    /**
     * A credential already at [origin]: the fake's `put` id and the `credential` row a real
     * `SecretStore.put` writes inside Room — `podcast.credentialId` is its FK child.
     */
    private suspend fun seedCredential(
        db: NeutrodyneDatabase,
        secrets: FakeSecretStore,
        origin: String,
        secret: String = pass,
    ): Long {
        val id = secrets.put(origin, user, secret.toCharArray())!!
        db
            .credentialDao()
            .insert(
                CredentialEntity(
                    id = id,
                    origin = origin,
                    username = user,
                    secretCipher = null,
                    iv = null,
                    createdAt = now,
                ),
            )
        return id
    }

    private suspend fun captureStored(
        db: NeutrodyneDatabase,
        rid: String,
        field: String,
        clock: Hlc,
        literal: String,
    ) {
        db.withWriteTransaction {
            db
                .syncOutboxDao()
                .captureStored(
                    SyncCollection.PODCAST.wire,
                    rid,
                    field,
                    clock.packed,
                    clock.nodeId.value,
                    literal,
                )
        }
    }

    /** A raw marker as a crashed or pre-arrival page left it: reference and marker, no podcast. */
    private suspend fun seedRaw(
        db: NeutrodyneDatabase,
        key: String,
        clock: String,
    ) {
        SyncClockStore(db)
            .put(
                SyncCollection.PODCAST.wire,
                rid,
                RawSyncRecord(
                    state =
                        RecordState(
                            SyncCollection.PODCAST,
                            fields =
                                mapOf(
                                    "credentialOrigin" to
                                        FieldState(JsonPrimitive(origin), Hlc.parse(clock)),
                                ),
                        ),
                    authReference = AuthReference(key, origin, clock),
                    pendingAuth = PendingAuth(key, origin, clock),
                ),
            )
    }

    /**
     * A raw reference with no pending marker: the accepted snapshot's immutable binding as a
     * completed install (or an acknowledged send) left it.
     */
    private suspend fun seedRawReference(
        db: NeutrodyneDatabase,
        key: String,
        credentialOrigin: String,
        clock: String,
    ) {
        SyncClockStore(db)
            .put(
                SyncCollection.PODCAST.wire,
                rid,
                RawSyncRecord(
                    state =
                        RecordState(
                            SyncCollection.PODCAST,
                            fields =
                                mapOf(
                                    "credentialOrigin" to
                                        FieldState(JsonPrimitive(credentialOrigin), Hlc.parse(clock)),
                                ),
                        ),
                    authReference = AuthReference(key, credentialOrigin, clock),
                ),
            )
    }

    /** A feed/origin move without an `auth`: the bare origin write at [counter]'s clock. */
    private fun originMove(
        newOrigin: String,
        counter: Long,
    ): RecordDto {
        val c = Hlc.of(now, counter, remote).toString()
        return RecordDto(
            coll = SyncCollection.PODCAST.wire,
            id = JsonPrimitive(rid),
            seq = counter,
            by = deviceId,
            fields =
                mapOf(
                    "feedUrl" to FieldValue(JsonPrimitive("$newOrigin/feed"), c),
                    "credentialOrigin" to FieldValue(JsonPrimitive(newOrigin), c),
                ),
        )
    }

    private fun authRecord(
        counter: Long = 10,
        includeAuth: Boolean = true,
    ): RecordDto {
        val c = Hlc.of(now, counter, remote).toString()
        val fields =
            mutableMapOf(
                "subscribed" to FieldValue(JsonPrimitive(true), c),
                "feedUrl" to FieldValue(JsonPrimitive(feedUrl), c),
                "title" to FieldValue(JsonPrimitive("Feed $rid"), c),
                "subscribedAt" to FieldValue(JsonPrimitive(now), c),
                "credentialOrigin" to FieldValue(JsonPrimitive(origin), c),
            )
        if (includeAuth) {
            fields["auth"] = FieldValue(authValue(user, pass), c)
        }
        return RecordDto(
            coll = SyncCollection.PODCAST.wire,
            id = JsonPrimitive(rid),
            seq = counter,
            by = deviceId,
            fields = fields,
        )
    }

    /** An auth-only update on an existing record: a credential origin plus the `{u,p}` pair. */
    private fun authUpdate(
        origin: String = this.origin,
        pass: String = this.pass,
        counter: Long,
    ): RecordDto {
        val c = Hlc.of(now, counter, remote).toString()
        return RecordDto(
            coll = SyncCollection.PODCAST.wire,
            id = JsonPrimitive(rid),
            seq = counter,
            by = deviceId,
            fields =
                mapOf(
                    "credentialOrigin" to FieldValue(JsonPrimitive(origin), c),
                    "auth" to FieldValue(authValue(user, pass), c),
                ),
        )
    }

    private fun authValue(
        u: String,
        p: String,
    ): JsonObject = JsonObject(mapOf("u" to JsonPrimitive(u), "p" to JsonPrimitive(p)))
}
