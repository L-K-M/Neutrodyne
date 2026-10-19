// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.database

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncCaptureKind
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncParkedEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The same transactional assertions run on JVM, Robolectric and both Android drivers. */
object SyncBookkeepingProbe {
    private const val NODE = "0000000000000001"
    private const val PEER = "0000000000000002"
    private const val HISTORICAL = 1_700_000_000_000L

    suspend fun requiresWriter(db: NeutrodyneDatabase) {
        val outbox = db.syncOutboxDao()
        for (call in listOf<suspend () -> Unit>(
            { outbox.captureLiteral("setting", "x", "v", "1") },
            { outbox.captureIntent("setting", "x", "v", "1") },
            { outbox.captureAll("podcast", "x") },
            { outbox.captureAt("episode", "x", "played", HISTORICAL, "false") },
            { outbox.captureStored("setting", "x", "v", 1, NODE, "1") },
            { db.syncStateDao().withApplying {} },
            { outbox.deleteAcked("setting", "x", "v", 1, NODE) },
            { outbox.deleteIfUnchanged("setting", "x", "v", 1, NODE) },
            { outbox.restampAbove(1, NODE) },
            { outbox.moveRecord("setting", "x", "y") },
            { db.syncClockDao().moveRecord("setting", "x", "y", "{}") },
        )) {
            assertFailsWith<IllegalStateException> { call() }
        }
        assertEquals(0, outbox.count())
    }

    suspend fun disabled(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db.syncStateDao().upsert(SyncStateEntity())
            val outbox = db.syncOutboxDao()
            outbox.captureAll("podcast", "x")
            outbox.captureLiteral("setting", "x", "v", "1")
            outbox.captureIntent("setting", "x", "v", "2")
            outbox.captureAt("setting", "x", "v", HISTORICAL, "3")
            outbox.captureStored("setting", "x", "v", 123, PEER, "4")
            assertEquals(SyncStateEntity(), db.syncStateDao().get())
            assertEquals(0, outbox.count())
        }
    }

    suspend fun applying(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            val state = db.syncStateDao()
            val outbox = db.syncOutboxDao()
            assertEquals(
                "result",
                state.withApplying {
                    assertTrue(state.get()!!.applying)
                    state.withApplying { outbox.captureIntent("episode", "e", "pos", "0") }
                    assertTrue(state.get()!!.applying)
                    assertEquals(0, outbox.count())
                    assertEquals(0L, state.get()!!.hlc)
                    outbox.captureLiteral("setting", "x", "v", "1")
                    outbox.captureAll("podcast", "p")
                    outbox.captureAt("episode", "e", "played", HISTORICAL, "false")
                    outbox.captureStored("episode", "e", "fav", 123, PEER, "true")
                    "result"
                },
            )
            assertFalse(state.get()!!.applying)
            assertEquals(4, outbox.count())
            assertFailsWith<IllegalArgumentException> { state.withApplying { errorForTest() } }
            assertFalse(state.get()!!.applying)
            outbox.captureIntent("episode", "e", "pos", "0")
            assertEquals(5, outbox.count())
        }
    }

    suspend fun exceptionRollback(db: NeutrodyneDatabase) {
        assertFailsWith<IllegalArgumentException> {
            db.withWriteTransaction {
                db.syncStateDao().withApplying {
                    db.syncOutboxDao().captureLiteral("setting", "failed", "v", "99")
                    errorForTest()
                }
            }
        }
        assertEquals(0, db.syncOutboxDao().count())
        assertEquals(0L, db.syncStateDao().get()!!.hlc)
        assertFalse(db.syncStateDao().get()!!.applying)
    }

    suspend fun cancellation(db: NeutrodyneDatabase) =
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val job =
                launch {
                    db.withWriteTransaction {
                        db.syncStateDao().withApplying {
                            db.syncOutboxDao().captureLiteral("setting", "cancelled", "v", "1")
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                }
            entered.await()
            job.cancel(CancellationException("test cancellation"))
            job.join()
            assertFalse(db.syncStateDao().get()!!.applying)
            assertEquals(0, db.syncOutboxDao().count())
            assertEquals(0L, db.syncStateDao().get()!!.hlc)
            db.withWriteTransaction { db.syncOutboxDao().captureIntent("setting", "after", "v", "2") }
            assertEquals(1, db.syncOutboxDao().count())
        }

    suspend fun historicalAndReplay(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            val outbox = db.syncOutboxDao()
            outbox.captureAt("setting", "x", "v", HISTORICAL, "1")
            val historical = outbox.rows().single()
            assertEquals(HISTORICAL shl 16, historical.hlc)
            outbox.captureAt("setting", "x", "v", HISTORICAL - 1, "0")
            assertEquals(historical, outbox.rows().single())
            val replayStamp = historical.hlc + 7
            outbox.captureStored("setting", "x", "v", replayStamp, PEER, "2")
            outbox.captureStored("setting", "x", "v", replayStamp - 1, "ffffffffffffffff", "0")
            val replay = outbox.rows().single()
            assertEquals(SyncOutboxEntity("setting", "x", "v", replayStamp, PEER, SyncCaptureKind.REPLAY, "2"), replay)
            outbox.captureStored("setting", "x", "v", replayStamp, PEER, "2")
            assertEquals(replay, outbox.rows().single())
            assertFailsWith<IllegalArgumentException> {
                outbox.captureStored(
                    "setting",
                    "x",
                    "v",
                    replayStamp,
                    PEER,
                    "3",
                )
            }
            outbox.captureAt("setting", "min", "v", Long.MIN_VALUE, "null")
            assertEquals(1_577_836_800_000L shl 16, outbox.get("setting", "min", "v")!!.hlc)
            outbox.captureAt("setting", "max", "v", Long.MAX_VALUE, "null")
            assertTrue(outbox.get("setting", "max", "v")!!.hlc > historical.hlc)
            assertEquals(0L, db.syncStateDao().get()!!.hlc, "historical and replay never tick")
        }
    }

    suspend fun ticksAndAcknowledgements(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            val outbox = db.syncOutboxDao()
            db.syncStateDao().raiseHlc((4_000_000_000_000L shl 16) + 65_535)
            outbox.captureLiteral("setting", "x", "v", "1")
            val first = outbox.rows().single()
            assertEquals(4_000_000_000_001L shl 16, first.hlc, "counter overflow carries into milliseconds")
            outbox.captureLiteral("setting", "x", "v", "2")
            val second = outbox.rows().single()
            assertEquals(first.hlc + 1, second.hlc)
            outbox.deleteAcked("setting", "x", "v", first.hlc, NODE)
            outbox.deleteIfUnchanged("setting", "x", "v", first.hlc, NODE)
            outbox.deleteAcked("setting", "x", "v", second.hlc, "0000000000000000")
            assertEquals(second, outbox.rows().single())
            outbox.deleteAcked("setting", "x", "v", second.hlc, NODE)
            assertEquals(0, outbox.count())
        }
    }

    suspend fun restamp(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            val outbox = db.syncOutboxDao()
            val old = 4_000_000_000_000L shl 16
            for ((field, stamp) in listOf("a" to old, "b" to old, "c" to old + 1)) {
                outbox.upsert(SyncOutboxEntity("setting", "x", field, stamp, NODE, value = field))
            }
            val immutable =
                listOf(
                    SyncOutboxEntity("setting", "x", "same-node", old, NODE, SyncCaptureKind.REPLAY, "true"),
                    SyncOutboxEntity("setting", "x", "foreign", old, PEER, SyncCaptureKind.REPLAY, "false"),
                    SyncOutboxEntity("setting", "x", "accepted", 10, NODE, value = "null"),
                    SyncOutboxEntity("setting", "x", "other-local", old, PEER, value = "42"),
                )
            immutable.forEach { outbox.upsert(it) }
            db.syncStateDao().raiseHlc(old)
            db.syncStateDao().clampHlc(10)
            outbox.restampAbove(10, NODE)
            immutable.forEach { assertEquals(it, outbox.get(it.coll, it.rid, it.field)) }
            val a = outbox.get("setting", "x", "a")!!
            val b = outbox.get("setting", "x", "b")!!
            val c = outbox.get("setting", "x", "c")!!
            assertEquals(a.hlc, b.hlc)
            assertTrue(a.hlc < c.hlc && c.hlc < old)
            assertEquals(listOf("a", "b", "c"), listOf(a.value, b.value, c.value))
        }
    }

    suspend fun rekeyAndOpaqueClocks(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            val outbox = db.syncOutboxDao()
            val clock = db.syncClockDao()
            outbox.upsert(SyncOutboxEntity("episode", "from", "pos", 100, PEER, SyncCaptureKind.REPLAY, "source"))
            outbox.upsert(SyncOutboxEntity("episode", "to", "pos", 100, NODE, value = "target"))
            outbox.upsert(SyncOutboxEntity("episode", "to", "fav", 200, NODE, value = "true"))
            outbox.moveRecord("episode", "from", "to")
            assertEquals(2, outbox.count())
            assertEquals("source", outbox.get("episode", "to", "pos")!!.value)
            val merged = "{\"raw\":{\"unknown\":42},\"pendingAuth\":\"opaque-protected-ref\"}"
            clock.upsert(SyncClockEntity("episode", "from", "{\"raw\":1}"))
            clock.upsert(SyncClockEntity("episode", "to", "{\"pendingSetting\":2}"))
            clock.moveRecord("episode", "from", "to", merged)
            assertEquals(null, clock.get("episode", "from"))
            assertEquals(merged, clock.get("episode", "to")!!.clocks)
            outbox.upsert(SyncOutboxEntity("episode", "from", "pos", 100, PEER, value = "invalid"))
            assertFailsWith<IllegalArgumentException> { outbox.moveRecord("episode", "from", "to") }
            assertEquals(3, outbox.count())
        }
    }

    suspend fun unlink(db: NeutrodyneDatabase) {
        val p = db.podcastDao().insertPodcast(podcastEntity())
        db.withWriteTransaction {
            db.syncStateDao().upsert(db.syncStateDao().get()!!.copy(serverUrl = "https://sync.example"))
            db.syncOutboxDao().captureLiteral("setting", "x", "v", "1")
            db.syncClockDao().upsert(SyncClockEntity("podcast", "p", "{}"))
            db.syncParkedDao().park(
                SyncParkedEntity(podcastSyncId = "p", identityKey = "e", record = "{}", receivedAt = 1),
            )
            db.syncHeldDao().insert(SyncHeldEntity(batch = "[]", summary = "{}", heldAt = 1))
        }
        db.syncStateDao().unlink()
        assertEquals(SyncStateEntity(serverUrl = "https://sync.example"), db.syncStateDao().get())
        assertEquals(0, db.syncOutboxDao().count())
        assertEquals(0, db.syncClockDao().count())
        assertEquals(0, db.syncParkedDao().count())
        assertEquals(0, db.syncHeldDao().count())
        assertTrue(db.podcastDao().byId(p) != null)
    }

    private fun errorForTest(): Nothing = throw IllegalArgumentException("test failure")
}
