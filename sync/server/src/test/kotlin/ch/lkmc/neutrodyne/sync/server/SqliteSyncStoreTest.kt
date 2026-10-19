// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.MatchHints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SqliteSyncStoreTest {
    @Test fun `fresh schema reopens and preserves complete raw state and nullable clocks`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            val clock = Hlc.of(1_800_000_000_000, 1, NodeId.SERVER)
            val raw =
                RecordState(
                    SyncCollection.SETTING,
                    mapOf("v" to FieldState(JsonNull, clock, true)),
                    mapOf("extension" to setOf("first", "second")),
                    MatchHints(title = "hint"),
                    matchClocks = mapOf("title" to clock),
                )
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                store.write(1) {
                    put(fixtureRecord("a", nextSequence(), raw))
                    put(fixtureRecord("b", nextSequence()))
                    setAccount(account.copy(clockPacked = clock.packed + 1))
                }
            }
            SqliteSyncStore(dir).use { store ->
                store.read(1) {
                    assertEquals(raw, record(settingKey("a"))?.raw)
                    assertEquals(null, record(settingKey("b"))?.maxClock)
                    assertEquals(clock, maximumClock())
                    assertEquals(clock.packed + 1, account.clockPacked)
                    assertEquals(listOf(1L, 2L), page(0, 10).map { it.seq })
                    val seeded = ServerInputs({ 0 }, { ByteArray(it) }).clock(account, maximumClock())
                    assertTrue(seeded.tick().packed > clock.packed)
                }
            }
        }

    @Test fun `rollback includes sequence records watermark redirect indexes and events`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            val published = mutableListOf<ServerEvent>()
            SqliteSyncStore(dir, published::add).use { store ->
                accountFixture(dir)
                assertFailsWith<IllegalStateException> {
                    store.write(1) {
                        put(fixtureRecord("a", nextSequence()))
                        assignRedirect(settingKey("a"), settingKey("b"))
                        setAccount(account.copy(clockPacked = 100))
                        emit(ServerEvent.Changed(1, "c:00000001:1", null))
                        error("rollback")
                    }
                }
                store.read(1) {
                    assertEquals(0, account.cursor.seq)
                    assertEquals(0, account.clockPacked)
                    assertTrue(records().none())
                    assertTrue(redirects().isEmpty())
                }
                assertTrue(published.isEmpty())
                store.write(1) {
                    assertEquals(1, nextSequence())
                    emit(ServerEvent.Changed(1, "c:00000001:1", null))
                }
                assertEquals(1, published.size)
            }
        }

    @Test fun `concurrent writers allocate unique sequences and publish in commit order`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            val committed = mutableListOf<Long>()
            SqliteSyncStore(dir, { event ->
                committed += (event as ServerEvent.Changed).cursor.substringAfterLast(':').toLong()
            }).use { store ->
                accountFixture(dir)
                (1..100)
                    .map { index ->
                        async(Dispatchers.Default) {
                            store.write(1) {
                                val seq = nextSequence()
                                put(fixtureRecord("key$index", seq))
                                emit(ServerEvent.Changed(1, "c:00000001:$seq", null))
                            }
                        }
                    }.awaitAll()
                assertEquals((1L..100L).toList(), committed)
                assertEquals(100, store.read(1) { records().count() })
            }
        }

    @Test fun `four readers retain snapshots while writer commits`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                val entered = CountDownLatch(4)
                val release = CountDownLatch(1)
                val reads =
                    (1..4).map {
                        async(Dispatchers.Default) {
                            store.read(1) {
                                assertEquals(0, account.cursor.seq)
                                entered.countDown()
                                assertTrue(release.await(10, TimeUnit.SECONDS))
                                assertEquals(0, account.cursor.seq)
                                assertTrue(records().none())
                            }
                        }
                    }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    store.write(1) { put(fixtureRecord("a", nextSequence())) }
                } finally {
                    release.countDown()
                }
                reads.awaitAll()
                assertEquals(1, store.read(1) { account.cursor.seq })
            }
        }

    @Test fun `detached snapshot streams stable records beyond later commits`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                store.write(1) { put(fixtureRecord("a", nextSequence())) }
                store.snapshot(1).use { snapshot ->
                    store.write(1) { put(fixtureRecord("b", nextSequence())) }
                    assertEquals(listOf("a"), snapshot.records.map { it.key.id.rid }.toList())
                    assertTrue(snapshot.installationId.isNotBlank())
                    assertEquals(1, snapshot.schemaVersion)
                }
            }
        }

    @Test fun `dedupe indexes and redirect authority are account qualified and survive record GC`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                accountFixture(dir, 2)
                val podcast = RecordKey(SyncCollection.PODCAST, RecordId.fromRid(SyncCollection.PODCAST, UUID_A))
                val group = RecordKey(SyncCollection.GROUP, RecordId.fromRid(SyncCollection.GROUP, UUID_A))
                store.write(1) {
                    put(StoredRecord(podcast, RecordState(SyncCollection.PODCAST), nextSequence(), null, 1, 1))
                    setPodcastKeys(podcast, setOf("feed"))
                    put(StoredRecord(group, RecordState(SyncCollection.GROUP), nextSequence(), null, 1, null))
                    setGroupName(group, "group")
                    assignRedirect(settingKey("a"), settingKey("b"))
                }
                store.read(1) {
                    assertEquals(setOf(podcast), podcastOwners("feed"))
                    assertEquals(group, groupOwner("group"))
                }
                store.read(2) {
                    assertTrue(podcastOwners("feed").isEmpty())
                    assertEquals(null, groupOwner("group"))
                }
                assertFailsWith<IllegalStateException> {
                    store.write(
                        1,
                    ) { assignRedirect(settingKey("a"), settingKey("c")) }
                }
                store.write(1) {
                    remove(podcast)
                    remove(group)
                }
                store.read(1) {
                    assertTrue(podcastOwners("feed").isEmpty())
                    assertEquals(null, groupOwner("group"))
                    assertEquals(settingKey("b"), redirects()[settingKey("a")])
                }
            }
        }

    @Test fun `acknowledgement persists applied request cursor rather than response cursor`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                dbFixture(dir) { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "INSERT INTO device(id,account_id,name,platform,node_id,created_at) " +
                                "VALUES ('dev',1,'name','linux','0000000000000001',0)",
                        )
                    }
                }
                store.write(1) {
                    nextSequence()
                    nextSequence()
                    acknowledge("dev", "c:00000001:1", 42)
                }
                dbFixture(dir) { connection ->
                    connection.createStatement().executeQuery("SELECT last_cursor,last_seen_at FROM device").use {
                        assertTrue(it.next())
                        assertEquals(1, it.getLong(1))
                        assertEquals(42, it.getLong(2))
                    }
                }
                assertFailsWith<IllegalArgumentException> { store.write(1) { acknowledge("dev", "c:00000002:1", 43) } }
            }
        }

    @Test fun `backup copies live WAL and leaves destination intact on failure`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                store.write(1) { put(fixtureRecord("a", nextSequence())) }
                val output = dir.resolve("backup.db")
                store.backup(output)
                DriverManager.getConnection("jdbc:sqlite:$output").use { connection ->
                    quickCheck(connection)
                    connection.createStatement().executeQuery("SELECT COUNT(*) FROM record").use {
                        assertTrue(it.next())
                        assertEquals(1, it.getInt(1))
                    }
                }
                val bytes = Files.readAllBytes(output)
                val nonDirectory = dir.resolve("not-directory")
                Files.writeString(nonDirectory, "file")
                assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                    store.backup(
                        nonDirectory.resolve("copy.db"),
                    )
                }
                assertTrue(bytes.contentEquals(Files.readAllBytes(output)))
            }
        }

    @Test fun `settings and account foreign keys enforce durable invariants`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                assertTrue(store.writeProbe())
                dbFixture(dir) { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("PRAGMA foreign_keys=ON")
                        assertFailsWith<java.sql.SQLException> {
                            statement.execute(
                                "INSERT INTO token(hash,account_id,device_id,kind,created_at) VALUES ('hash',1,'missing','device',0)",
                            )
                        }
                        statement.executeQuery("PRAGMA journal_mode").use {
                            assertTrue(it.next())
                            assertEquals("wal", it.getString(1))
                        }
                        statement.executeQuery("PRAGMA auto_vacuum").use {
                            assertTrue(it.next())
                            assertEquals(2, it.getInt(1))
                        }
                    }
                }
                // An external writer makes the real readiness write fail.
                dbFixture(dir) { connection ->
                    connection.createStatement().execute("BEGIN IMMEDIATE")
                    assertFalse(store.writeProbe())
                    connection.createStatement().execute("ROLLBACK")
                }
                assertTrue(store.writeProbe())
            }
        }

    @Test fun `only graceful serve shutdown marks clean and unclean open rotates epoch`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            ServerStorage.open(dir).use { storage ->
                accountFixture(dir)
                storage.store.write(1) { setAccount(account.copy(clockPacked = 123)) }
                storage.finishGracefully()
            }
            assertTrue(Files.exists(dir.resolve(ServerStorage.CLEAN_MARKER)))
            ServerStorage.open(dir).use { storage ->
                assertEquals("00000001", storage.store.read(1) { account.cursor.epoch })
                assertFalse(Files.exists(dir.resolve(ServerStorage.CLEAN_MARKER)))
                // close without finishGracefully simulates a process which did not complete graceful stop.
            }
            ServerStorage.open(dir).use { storage ->
                storage.store.read(1) {
                    assertNotEquals("00000001", account.cursor.epoch)
                    assertEquals(123, account.clockPacked)
                }
                dbFixture(dir) { connection ->
                    connection.createStatement().executeQuery("SELECT event FROM audit").use {
                        assertTrue(it.next())
                        assertEquals("unclean_start", it.getString(1))
                    }
                }
                storage.finishGracefully()
            }
        }

    @Test
    fun `every pooled connection uses required durability and safety settings`() {
        val dir = tempDataDir()
        SqliteSyncStore(dir).use { store ->
            val writer =
                store.javaClass
                    .getDeclaredField(
                        "writer",
                    ).apply { isAccessible = true }
                    .get(store) as Connection

            @Suppress("UNCHECKED_CAST")
            val readers =
                store.javaClass
                    .getDeclaredField("readers")
                    .apply { isAccessible = true }
                    .get(store) as Collection<Connection>
            assertEquals(4, readers.size)
            for (connection in readers + writer) {
                for ((pragma, expected) in mapOf("synchronous" to 2, "foreign_keys" to 1, "busy_timeout" to 5000)) {
                    connection.createStatement().executeQuery("PRAGMA $pragma").use {
                        assertTrue(it.next())
                        assertEquals(expected, it.getInt(1), pragma)
                    }
                }
            }
        }
    }

    @Test
    fun `transaction callbacks cannot escape their connection scope`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                val escapedRead = store.read(1) { this }
                val escapedWrite = store.write(1) { this }
                assertFailsWith<IllegalStateException> { escapedRead.records() }
                assertFailsWith<IllegalStateException> { escapedWrite.nextSequence() }
            }
        }

    @Test
    fun `shutdown drains writes already queued before closing connections`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            val store = SqliteSyncStore(dir)
            accountFixture(dir)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first =
                async(Dispatchers.Default) {
                    store.write(1) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        put(fixtureRecord("a", nextSequence()))
                    }
                }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second =
                async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    store.write(1) { put(fixtureRecord("b", nextSequence())) }
                }
            val close = async(Dispatchers.IO) { store.close() }
            release.countDown()
            first.await()
            second.await()
            close.await()
            dbFixture(dir) { connection ->
                connection.createStatement().executeQuery("SELECT seq FROM account WHERE id=1").use {
                    assertTrue(it.next())
                    assertEquals(2, it.getLong(1))
                }
            }
        }

    @Test
    fun `backup refuses to replace the live database`() =
        runBlocking<Unit> {
            val dir = tempDataDir()
            SqliteSyncStore(dir).use { store ->
                accountFixture(dir)
                assertFailsWith<IllegalArgumentException> {
                    store.backup(dir.resolve(SqliteSyncStore.DATABASE_FILE))
                }
            }
        }

    companion object {
        private const val UUID_A = "00000000-0000-4000-8000-000000000001"
    }
}

internal fun settingKey(id: String) = RecordKey(SyncCollection.SETTING, RecordId.Setting(id))

internal fun fixtureRecord(
    id: String,
    seq: Long,
    raw: RecordState = RecordState(SyncCollection.SETTING),
) = StoredRecord(settingKey(id), raw, seq, null, 10, null)

internal fun accountFixture(
    dir: Path,
    id: Long = 1,
) = dbFixture(dir) { connection ->
    connection.createStatement().use {
        it.execute("INSERT INTO account(id,username,role,epoch,created_at) VALUES ($id,'user$id','user','00000001',0)")
    }
}

internal inline fun <T> dbFixture(
    dir: Path,
    block: (Connection) -> T,
): T = DriverManager.getConnection("jdbc:sqlite:${dir.resolve(SqliteSyncStore.DATABASE_FILE)}").use(block)
