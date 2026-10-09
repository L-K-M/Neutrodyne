// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import android.content.Intent
import androidx.room3.ExperimentalRoomApi
import androidx.room3.IMultiInstanceInvalidationCallback
import androidx.room3.IMultiInstanceInvalidationService
import androidx.room3.InvalidationTracker
import androidx.room3.MultiInstanceInvalidationService
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.RoomOpenDelegate
import androidx.room3.RoomOpenDelegateMarker
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rebuilt runtime's Android AAR must contain the Java Binder stubs
 * (`IMultiInstanceInvalidationService`, `IMultiInstanceInvalidationCallback` and their
 * `Stub`/`Proxy`/`Default` members) for the Kotlin service/client subclasses to link.
 * `check-parity.sh` guards the archive contents; this proves the classes load and function
 * on an Android runtime (Robolectric). The cancellation case needs Room's patched
 * `Pool.acquireWithTimeout`: `AndroidSQLiteDriver` reports `hasConnectionPool` and routes to
 * the unpatched `PassthroughConnectionPool`, while `BundledSQLiteDriver`'s native library is
 * unavailable on the host JVM — so a no-op stub driver exercises the pool. The real driver
 * path runs on-device via `:app`'s `RoomRuntimeServiceDeviceTest` (androidTest).
 */
@RunWith(RobolectricTestRunner::class)
class RoomRuntimeServiceHostTest {
    @OptIn(ExperimentalRoomApi::class)
    @Test
    fun invalidationServiceBindsAndDeliversBroadcasts() {
        val service =
            Robolectric.buildService(MultiInstanceInvalidationService::class.java).create().get()
        val binder = service.onBind(Intent())
        val remote = IMultiInstanceInvalidationService.Stub.asInterface(binder)
        assertNotNull(remote, "asInterface must resolve the Stub")

        val delivered = CountDownLatch(1)
        // Two distinct Stub objects: RemoteCallbackList keys callbacks by IBinder, so a
        // second register() with the same object replaces the first entry on a real
        // Binder transport (Robolectric's shadow hides that) and the broadcast would
        // skip the sole remaining client — the caller's own clientId is filtered out.
        val callback =
            object : IMultiInstanceInvalidationCallback.Stub() {
                override fun onInvalidation(tables: Array<out String>) {
                    delivered.countDown()
                }

                override fun getInterfaceVersion(): Int = IMultiInstanceInvalidationCallback.VERSION
            }
        val other =
            object : IMultiInstanceInvalidationCallback.Stub() {
                override fun onInvalidation(tables: Array<out String>) = Unit

                override fun getInterfaceVersion(): Int = IMultiInstanceInvalidationCallback.VERSION
            }

        val callbackId = remote.registerCallback(callback, "probe.db")
        val otherId = remote.registerCallback(other, "probe.db")
        assertTrue(
            callbackId > 0 && otherId > callbackId,
            "registerCallback must assign distinct client ids",
        )

        // The broadcaster's own clientId is skipped by the filter; the broadcast as the
        // other client must reach `callback` through the Binder round-trip.
        remote.broadcastInvalidation(otherId, arrayOf("probe"))
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "broadcast must reach the callback")
    }

    @Test
    fun foreignTimeoutOnWaitingWriterCompletes() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val db =
                Room
                    .inMemoryDatabaseBuilder<ProbeDatabase>(factory = ::ProbeDatabase)
                    .setDriver(NoPoolStubDriver)
                    .build()
            try {
                db.withWriteTransaction { }

                val holderEntered = CompletableDeferred<Unit>()
                val holdLatch = CompletableDeferred<Unit>()
                val holder =
                    scope.async {
                        db.withWriteTransaction {
                            holderEntered.complete(Unit)
                            holdLatch.await()
                        }
                    }
                holderEntered.await()
                delay(300)

                val waiter =
                    scope.async {
                        withTimeoutOrNull(200) {
                            db.withWriteTransaction { }
                            "ran"
                        } ?: "timed-out"
                    }
                val outcome = withTimeoutOrNull(2_000) { waiter.await() }
                assertEquals(
                    "timed-out",
                    outcome,
                    "foreign-timeout waiter must complete, not turn zombie",
                )
                assertTrue(waiter.isCompleted && !waiter.isCancelled, "waiter must end normally")

                holdLatch.complete(Unit)
                assertNotNull(withTimeoutOrNull(5_000) { holder.await() })
                assertEquals(
                    "acquired",
                    withTimeoutOrNull(5_000) {
                        db.withWriteTransaction { }
                        "acquired"
                    },
                    "fresh writer must acquire after the holder releases",
                )
            } finally {
                scope.cancel()
                db.close()
            }
        }

    private class ProbeDatabase : RoomDatabase() {
        override fun createOpenDelegate(): RoomOpenDelegateMarker =
            object :
                RoomOpenDelegate(
                    version = 1,
                    identityHash = "service-probe",
                    legacyIdentityHash = "service-probe",
                ) {
                override suspend fun onCreate(connection: SQLiteConnection) {
                    // tables are created by createAllTables during open
                }

                override suspend fun onPreMigrate(connection: SQLiteConnection) {
                    // no migrations in this fixture
                }

                override suspend fun onValidateSchema(connection: SQLiteConnection) =
                    ValidationResult(isValid = true, expectedFoundMsg = null)

                override suspend fun onPostMigrate(connection: SQLiteConnection) {
                    // no migrations in this fixture
                }

                override suspend fun onOpen(connection: SQLiteConnection) {
                    // nothing to seed
                }

                override suspend fun createAllTables(connection: SQLiteConnection) {
                    connection
                        .prepare("CREATE TABLE IF NOT EXISTS probe(id INTEGER PRIMARY KEY)")
                        .use { it.step() }
                }

                override suspend fun dropAllTables(connection: SQLiteConnection) {
                    connection.prepare("DROP TABLE IF EXISTS probe").use { it.step() }
                }
            }

        override fun createInvalidationTracker(): InvalidationTracker =
            InvalidationTracker(this, emptyMap(), emptyMap())

        override suspend fun clearAllTables() {
            // never called by these tests
        }
    }

    /**
     * No-op driver without its own pool: routes transactions through the AAR's real
     * `Pool`/`acquireWithTimeout` without needing a native SQLite on the host JVM.
     */
    private object NoPoolStubDriver : SQLiteDriver {
        override fun open(fileName: String): SQLiteConnection =
            object : SQLiteConnection {
                // Pool.markRecycled queries this; the stub never holds a real transaction.
                override fun inTransaction(): Boolean = false

                override fun prepare(sql: String): SQLiteStatement =
                    object : SQLiteStatement {
                        override fun bindBlob(
                            index: Int,
                            value: ByteArray,
                        ) = Unit

                        override fun bindDouble(
                            index: Int,
                            value: Double,
                        ) = Unit

                        override fun bindLong(
                            index: Int,
                            value: Long,
                        ) = Unit

                        override fun bindText(
                            index: Int,
                            value: String,
                        ) = Unit

                        override fun bindNull(index: Int) = Unit

                        override fun getBlob(index: Int): ByteArray = ByteArray(0)

                        override fun getDouble(index: Int): Double = 0.0

                        override fun getLong(index: Int): Long = 0L

                        override fun getText(index: Int): String = ""

                        override fun isNull(index: Int): Boolean = true

                        override fun getColumnCount(): Int = 0

                        override fun getColumnName(index: Int): String = ""

                        override fun getColumnType(index: Int): Int = 0

                        // false = "no row": the tracker's while(step()) loops would
                        // otherwise hold the pooled connection forever.
                        override fun step(): Boolean = false

                        override fun reset() = Unit

                        override fun clearBindings() = Unit

                        override fun close() = Unit
                    }

                override fun close() = Unit
            }
    }
}
