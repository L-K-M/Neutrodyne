// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.core.testing.sync.SequencedSyncRecord
import ch.lkmc.neutrodyne.core.testing.sync.SyncAddress
import ch.lkmc.neutrodyne.core.testing.sync.SyncPage
import ch.lkmc.neutrodyne.core.testing.sync.SyncServerAdapter
import ch.lkmc.neutrodyne.sync.protocol.ProtocolException
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordMerger
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncErrorCode
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncRequest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** The generator talks only to W1's seam; all writes and pages use production services. */
internal class SqliteServerAdapter(
    wallMs: () -> Long,
    directory: Path = convergenceDirectory(),
) : SyncServerAdapter,
    AutoCloseable {
    private val directory = directory
    private val store = SqliteSyncStore(directory)
    private val inputs = ServerInputs(wallMs, productionInputs().randomBytes)
    private val writer = StoredRecordWriter(store, inputs)
    private val principal = AccountPrincipal(1, AccountRole.USER, TokenKind.DEVICE, "test-only", DEVICE)

    init {
        runBlocking {
            store.maintenance {
                update(
                    "INSERT INTO account(id,username,role,epoch,created_at) VALUES (1,'convergence','user','1234abcd',?)",
                    inputs.wallMs(),
                )
            }
        }
    }

    override fun push(
        address: SyncAddress,
        state: RecordState,
    ) {
        require(state.mergedInto == null)
        // Rekey can leave an empty destination. Its replay has no wire operation or data to send.
        if (state.fields.isEmpty() && state.growSets.isEmpty() && state.match == null) return
        val dto = state.toDto(address.id, 0, null)
        send(ChangeDto(dto.coll, dto.id, match = dto.match, fields = dto.fields, matchClocks = dto.matchClocks))
    }

    override fun rekey(
        from: RecordId.Episode,
        to: RecordId.Episode,
    ) {
        send(ChangeDto(SyncCollection.EPISODE.wire, from.toWire(), op = "rekey", to = to.toWire()))
    }

    private fun send(change: ChangeDto) =
        runBlocking {
            val result = writer.push(principal, SyncRequest(null, listOf(change))).results.single()
            if (result.status == "rejected") {
                throw ProtocolException(
                    SyncErrorCode.entries.single {
                        it.wire == result.code
                    },
                    "real writer rejected generated change: ${result.code}",
                )
            }
        }

    override fun page(
        cursor: String?,
        limit: Int,
    ): SyncPage =
        runBlocking {
            val page = writer.changes(principal, cursor, limit)
            SyncPage(
                page.records.map {
                    SequencedSyncRecord(SyncAddress(it.key.collection, it.key.id), it.raw, it.seq)
                },
                page.cursor,
                page.hasMore,
            )
        }

    override fun snapshot(): Map<SyncAddress, RecordState> =
        runBlocking {
            store.read(1) { records().associate { SyncAddress(it.key.collection, it.key.id) to it.raw } }
        }

    override fun expire(restore: Map<SyncAddress, RecordState>?) =
        runBlocking {
            store.write(1) {
                if (restore != null) {
                    records().map { it.key }.toList().forEach(::remove)
                    restore.entries.sortedBy { it.key.key }.forEach { (address, raw) ->
                        val key = RecordKey(address.coll, address.id)
                        put(
                            StoredRecord(
                                key,
                                raw,
                                nextSequence(),
                                null,
                                inputs.wallMs(),
                                inputs.wallMs().takeIf { RecordMerger.isDead(raw) },
                            ),
                        )
                        when (key.collection) {
                            SyncCollection.PODCAST -> setPodcastKeys(key, RecordChanges.podcastKeys(raw))
                            SyncCollection.GROUP -> setGroupName(key, RecordChanges.groupName(raw))
                            else -> Unit
                        }
                    }
                }
                val cursor = account.cursor
                setAccount(
                    account.copy(
                        cursor = cursor.copy(epoch = (cursor.epoch.toLong(16) + 1).toString(16).padStart(8, '0')),
                    ),
                )
            }
        }

    override fun close() {
        store.close()
        Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

// Keep FULL/WAL and the real writer; tmpfs removes filesystem latency from the seed budget.
private fun convergenceDirectory(): Path {
    ConvergenceNative.ready()
    val ram = Path.of("/dev/shm")
    val parent =
        if (Files.isDirectory(ram) &&
            Files.isWritable(ram)
        ) {
            ram
        } else {
            Path.of(System.getProperty("java.io.tmpdir"))
        }
    return Files.createTempDirectory(parent, "nd-real-convergence-")
}

private object ConvergenceNative {
    init {
        // /dev/shm is commonly noexec. Bootstrap JNI on the normal executable temp filesystem.
        if (System.getProperty("org.sqlite.tmpdir") == null) {
            val native = Files.createTempDirectory("nd-convergence-jni-")
            System.setProperty("org.sqlite.tmpdir", native.toString())
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    Files.walk(native).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                },
            )
        }
        check(org.sqlite.SQLiteJDBCLoader.initialize()) { "SQLite JNI initialization failed" }
    }

    fun ready() = Unit
}
