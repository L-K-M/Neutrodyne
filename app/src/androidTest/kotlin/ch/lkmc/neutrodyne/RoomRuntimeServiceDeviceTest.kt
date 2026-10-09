// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device-side proof for the patched `room3-runtime` AAR (PR #56):
 * `invalidationServiceBindsOnDevice` binds `MultiInstanceInvalidationService` through the
 * real Binder transport, which only works if the AAR ships the Java `IMultiInstance…`
 * stubs — the packaging gap this review caught. `foreignTimeoutOnWaitingWriterCompletes`
 * exercises the patched `Pool.acquireWithTimeout` cancellation path on-device via
 * `BundledSQLiteDriver` (`AndroidSQLiteDriver` reports `hasConnectionPool` and routes to
 * the unpatched `PassthroughConnectionPool`). Runs under the app's managed-device groups
 * (`./gradlew :app:api36Check`).
 */
@RunWith(AndroidJUnit4::class)
class RoomRuntimeServiceDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @OptIn(ExperimentalRoomApi::class)
    @Test
    fun invalidationServiceBindsOnDevice() {
        val bound = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val remote = CompletableDeferred<IMultiInstanceInvalidationService>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    remote.complete(IMultiInstanceInvalidationService.Stub.asInterface(service))
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // not expected in this test
                }
            }

        val intent = Intent(context, MultiInstanceInvalidationService::class.java)
        assertTrue(
            "service must be declared and bindable",
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE),
        )
        assertTrue("service must connect", bound.await(10, TimeUnit.SECONDS))
        val service =
            requireNotNull(runBlocking { withTimeoutOrNull(5_000) { remote.await() } }) {
                "asInterface must resolve the Stub"
            }

        // Two distinct Stub objects: RemoteCallbackList keys callbacks by IBinder, so a
        // second register() with the same object would replace the first entry and the
        // broadcast would skip the sole remaining client (the caller's own clientId).
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
        val callbackId = service.registerCallback(callback, "probe.db")
        val otherId = service.registerCallback(other, "probe.db")
        assertTrue("two callbacks must register as distinct clients", callbackId != otherId)
        service.broadcastInvalidation(otherId, arrayOf("probe"))
        assertTrue("broadcast must reach the callback", delivered.await(5, TimeUnit.SECONDS))
        context.unbindService(connection)
    }

    @Test
    fun foreignTimeoutOnWaitingWriterCompletesOnDevice() =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val db =
                Room
                    .inMemoryDatabaseBuilder<ProbeDatabase>(factory = ::ProbeDatabase)
                    .setDriver(BundledSQLiteDriver())
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
                assertEquals("timed-out", outcome)

                holdLatch.complete(Unit)
                assertNotNull(withTimeoutOrNull(5_000) { holder.await() })
                assertEquals(
                    "acquired",
                    withTimeoutOrNull(5_000) {
                        db.withWriteTransaction { }
                        "acquired"
                    },
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
                    identityHash = "device-probe",
                    legacyIdentityHash = "device-probe",
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
            // never called by this test
        }
    }
}
