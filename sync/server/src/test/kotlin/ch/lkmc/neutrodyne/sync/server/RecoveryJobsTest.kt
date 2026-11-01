// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okio.source
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecoveryJobsTest {
    @Test fun archiveHasExactReceiptsAndSparseState() =
        authServices { f ->
            val account = f.account()
            val principal = f.principal(f.grant(account))
            val fixture = WriterFixture()
            val stamp = fixture.clock()
            f.services.records.push(
                principal,
                SyncRequest(
                    null,
                    listOf(
                        fixture.podcast(),
                        fixture.group(),
                        fixture.change(member(), mapOf("in" to JsonPrimitive(true), "ok" to JsonPrimitive("a0"))),
                        fixture.write(episode(), "fav", JsonPrimitive(true)),
                        fixture.write(setting, "v", JsonPrimitive(1.5)),
                    ),
                ),
            )
            val path = f.services.backups.export(account.id)
            ZipFile(path.toFile()).use { zip ->
                val codec = BackupCodec()
                val manifest =
                    zip
                        .getInputStream(
                            zip.getEntry("manifest.json"),
                        ).use { codec.decodeManifest(it.source()) }
                assertEquals(6, zip.size())
                assertEquals(1, manifest.counts.episodeLines)
                assertEquals("server", manifest.app.abi)
                assertFalse(manifest.includesCredentials)
                val library = zip.getInputStream(zip.getEntry("library.json")).use { codec.decodeLibrary(it.source()) }
                assertEquals(
                    library.podcasts.single().key,
                    library.groups
                        .single()
                        .members
                        .single()
                        .podcastKey,
                )
                zip.getInputStream(zip.getEntry("episodes.jsonl")).use { stream ->
                    codec.episodeLines(stream.source()).use { lines ->
                        val line = lines.next()
                        assertEquals("Untitled episode", line.t)
                        assertEquals(stamp.millis, line.ts)
                        assertTrue(line.fav)
                        assertEquals(null, line.fc)
                    }
                }
                manifest.entries.forEach { receipt ->
                    val bytes = zip.getInputStream(zip.getEntry(receipt.name)).use { it.readAllBytes() }
                    assertEquals(receipt.bytes, bytes.size.toLong())
                    assertEquals(
                        receipt.sha256,
                        java.security.MessageDigest
                            .getInstance(
                                "SHA-256",
                            ).digest(bytes)
                            .joinToString("") {
                                it.toUByte().toString(16).padStart(2, '0')
                            },
                    )
                }
            }
        }

    @Test fun interruptedArchiveRetainsLastGoodCopy() =
        authServices { f ->
            val account = f.account()
            val path = f.services.backups.export(account.id)
            val bytes = Files.readAllBytes(path)
            val broken = AccountArchiveWriter(f.inputs, ArchiveWriterFactory { _, _ -> error("interrupted") })
            f.store.snapshot(account.id).use { assertFailsWith<IllegalStateException> { broken.export(it, path) } }
            assertTrue(bytes.contentEquals(Files.readAllBytes(path)))
        }

    @Test fun nightlyRetentionIsDateBasedAndPreservesYoungMigrationCopies() =
        authServices { f ->
            val account = f.account()
            val config = f.config.copy(backupsKeep = 2, accountBackupsKeep = 2)
            val backups = AccountBackups(f.store, config, f.inputs)
            val job = BackupJob(f.store, config, backups, f.services.metrics, f.inputs)
            val protected = config.dataDir.resolve("backups/neutrodyne-server-pre-migration-v1-test.db")
            Files.createDirectories(protected.parent)
            Files.writeString(protected, "fixture")
            repeat(4) {
                job.run()
                job.run()
                f.now += DAY_MS
            }
            Files.list(protected.parent).use { assertEquals(3, it.count()) }
            Files.list(config.dataDir.resolve("account-backups/${account.id}")).use { assertEquals(2, it.count()) }
            assertTrue(Files.exists(protected))
        }

    @Test fun gcRequiresAppliedCursorAndExpiresTheBoundaryOnly() =
        authServices { f ->
            val account = f.account()
            val grant = f.grant(account)
            val principal = f.principal(grant)
            val fixture = WriterFixture()
            f.services.records.push(principal, SyncRequest(null, listOf(fixture.podcast())))
            val dead =
                f.services.records.push(
                    principal,
                    SyncRequest(
                        null,
                        listOf(fixture.write(podcastA, "subscribed", JsonPrimitive(false), fixture.clock(1))),
                    ),
                )
            val before = dead.page.cursor
            f.now += 366 * DAY_MS
            // Keep a recent device with no applied cursor; the response cursor is not acknowledgement.
            f.store.maintenance { update("UPDATE device SET last_seen_at=?,last_cursor=NULL", f.now) }
            assertEquals(0L, f.services.gcJob.run())
            f.store.maintenance { update("UPDATE device SET last_cursor=?", dead.page.records.maxOf { it.seq }) }
            assertEquals(1L, f.services.gcJob.run())
            assertEquals(null, f.store.read(account.id) { record(podcastA) })
            // Equality remains a usable cursor: it has already applied the record just purged.
            f.services.records.changes(principal, before, 1000)
            val stale = before.substringBeforeLast(':') + ":0"
            assertFailsWith<ch.lkmc.neutrodyne.sync.protocol.ProtocolException> {
                f.services.records.changes(principal, stale, 1000)
            }
        }

    @Test fun gcWithoutActiveDevicesPurgesMembersWithDeadParentAndKeepsLedger() =
        authServices { f ->
            val account = f.account()
            val fixture = WriterFixture()
            f.store.write(account.id) {
                fun seed(
                    key: RecordKey,
                    raw: RecordState,
                    dead: Long? = null,
                ) = put(StoredRecord(key, raw, nextSequence(), null, f.now, dead))
                seed(podcastA, fixture.state(podcastA, "subscribed", JsonPrimitive(false)), f.now)
                seed(member(), fixture.state(member(), "in", JsonPrimitive(true)))
                assignRedirect(key(SyncCollection.PODCAST, B), podcastA)
            }
            f.now += 366 * DAY_MS
            assertEquals(2L, f.services.gcJob.run())
            assertTrue(f.store.read(account.id) { records().none() && redirects().isNotEmpty() })
        }

    @Test fun purgeWaitsForExportAndCannotRecreateAccountBackup() =
        authServices { f ->
            val account = f.account()
            val grant = f.grant(account)
            val principal = f.principal(grant)
            coroutineScope {
                val opened = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val download =
                    async {
                        f.services.backups.download(principal) {
                            opened.complete(Unit)
                            finish.await()
                        }
                    }
                opened.await()
                val purge = async { f.services.accounts.purge(principal, "DELETE") }
                assertFalse(purge.isCompleted)
                finish.complete(Unit)
                download.await()
                purge.await()
            }
            assertFalse(Files.exists(f.config.dataDir.resolve("account-backups/${account.id}")))
            assertFailsWith<ch.lkmc.neutrodyne.sync.protocol.ProtocolException> {
                f.services.backups.download(
                    principal,
                ) {}
            }
        }

    @Test fun restoreCarriesRevocationsNewAccountsAndIdentityAuthority() =
        runBlocking {
            val directory = tempDataDir()
            val backup = directory.resolve("test-backup.db")
            var oldEpoch = ""
            var id = 0L
            var hash = ""
            val fixture = WriterFixture()
            AuthFixture(loadValidConfig().copy(dataDir = directory)).use { f ->
                val account = f.account()
                id = account.id
                val grant = f.grant(account)
                val principal = f.principal(grant)
                hash = principal.tokenHash
                f.services.records.push(
                    principal,
                    SyncRequest(null, listOf(fixture.podcast(), fixture.write(episode(), "fav", JsonPrimitive(true)))),
                )
                f.store.backup(backup)
                oldEpoch = f.store.read(id) { this.account.cursor.epoch }
                // This authority was assigned AFTER the backup; its child content must move too.
                f.store.write(id) { assignRedirect(podcastA, key(SyncCollection.PODCAST, B)) }
                f.services.tokens.logout(principal)
                f.account("later")
            }
            val retained = ServerRecovery(directory).restore(backup)
            assertTrue(Files.exists(retained))
            SqliteSyncStore(directory).use { store ->
                assertNotEquals(oldEpoch, store.read(id) { account.cursor.epoch })
                assertTrue(store.readAuth { checkNotNull(token(hash)).revokedAt != null })
                assertEquals(2, store.readAuth { accounts().size })
                assertEquals(key(SyncCollection.PODCAST, B), store.read(id) { redirects()[podcastA] })
                assertTrue(store.read(id) { checkNotNull(record(episode(B))).bool("fav") == true })
                assertEquals(episode(B).id, store.read(id) { checkNotNull(record(episode())).raw.mergedInto })
            }
        }

    @Test fun invalidOrCorruptAuthorityRestoreLeavesCurrentBytesUntouched() =
        runBlocking {
            val directory = tempDataDir()
            val backup = directory.resolve("backup.db")
            SqliteSyncStore(directory).use { it.backup(backup) }
            val current = directory.resolve(SqliteSyncStore.DATABASE_FILE)
            Files.writeString(current, "corrupt authority")
            val before = Files.readAllBytes(current)
            assertFailsWith<Exception> { ServerRecovery(directory).restore(backup) }
            assertTrue(before.contentEquals(Files.readAllBytes(current)))
        }
}
