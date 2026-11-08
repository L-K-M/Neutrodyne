// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {
    @Test
    fun aCancelledRoundPropagatesCancellation() =
        runBlocking {
            val cancellation = CancellationException("sync stopped")
            withWorker(cancellation) { worker ->
                assertSame(cancellation, assertFailsWith<CancellationException> { worker.doWork() })
            }
        }

    @Test
    fun aFailedRoundStillRequestsARetry() =
        runBlocking {
            withWorker(IllegalStateException("server address unavailable")) { worker ->
                assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            }
        }

    @Test
    fun aRevokedRoundFailsInsteadOfRetrying() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val db = TestDb.inMemory(app)
            try {
                db.syncStateDao().upsert(
                    ch.lkmc.neutrodyne.core.database.SyncStateEntity(
                        enabled = true,
                        serverUrl = "https://sync.example",
                        accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                        nodeId = "0000000000000001",
                        linkedAt = 1L,
                    ),
                )
                val secrets = FakeSecretStore()
                SyncTokenStore(secrets).save("sync.example", "nds_test")
                val clock = TestClock()
                val settings = FakeSettingsSyncPort()
                val unscripted =
                    Proxy.newProxyInstance(
                        SyncClient::class.java.classLoader,
                        arrayOf(SyncClient::class.java),
                    ) { _, method, _ ->
                        error("unscripted ${method.name}")
                    } as SyncClient
                val client =
                    object : SyncClient by unscripted {
                        override suspend fun sync(
                            since: String?,
                            changes: List<ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto>,
                        ): ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse =
                            throw SyncTransportException(401, "unauthorized", "")
                    }
                val engine =
                    SyncEngine(
                        db = db,
                        client = client,
                        applier =
                            SyncApplier(
                                db = db,
                                clock = clock,
                                secrets = secrets,
                                settings = settings,
                                playback = NoPlaybackSync,
                                syncIngestHook = SyncIngestHook.None,
                                channelSync = FakeGroupChannelSync(),
                                sharePasswords = { false },
                            ),
                        clock = clock,
                        secrets = secrets,
                        sharePasswords = { false },
                        address = FakeSyncServerAddress(serverUrl = "https://sync.example"),
                        tokens = SyncTokenStore(secrets),
                        activity = SyncActivityState(),
                        gate = FakeLocalNetworkGate(),
                    )
                val worker =
                    TestListenableWorkerBuilder<SyncWorker>(app)
                        .setWorkerFactory(
                            object : WorkerFactory() {
                                override fun createWorker(
                                    appContext: Context,
                                    workerClassName: String,
                                    workerParameters: WorkerParameters,
                                ): ListenableWorker = SyncWorker(appContext, workerParameters, engine)
                            },
                        ).build()
                // A rejected token is not transient: the worker must not re-enqueue dead
                // credentials, and the engine records the revocation for the status.
                assertEquals(ListenableWorker.Result.failure(), worker.doWork())
                val state = db.syncStateDao().get()
                assertEquals("Revoked", state?.lastError)
                assertEquals(false, state?.enabled)
            } finally {
                db.close()
            }
        }

    private suspend fun withWorker(
        failure: Exception,
        block: suspend (SyncWorker) -> Unit,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val db = TestDb.inMemory(app)
        try {
            // The real round fails at its first suspending address read, before any HTTP call.
            val address =
                object : SyncServerAddress by FakeSyncServerAddress() {
                    override suspend fun serverUrl(): String = throw failure
                }
            val client =
                Proxy.newProxyInstance(SyncClient::class.java.classLoader, arrayOf(SyncClient::class.java)) { _, _, _ ->
                    error("The cancelled round must not reach the HTTP client")
                } as SyncClient
            val clock = TestClock()
            val secrets = FakeSecretStore()
            val settings = FakeSettingsSyncPort()
            val engine =
                SyncEngine(
                    db = db,
                    client = client,
                    applier =
                        SyncApplier(
                            db = db,
                            clock = clock,
                            secrets = secrets,
                            settings = settings,
                            playback = NoPlaybackSync,
                            syncIngestHook = SyncIngestHook.None,
                            channelSync = FakeGroupChannelSync(),
                            sharePasswords = { false },
                        ),
                    clock = clock,
                    secrets = secrets,
                    sharePasswords = { false },
                    address = address,
                    tokens = SyncTokenStore(secrets),
                    activity = SyncActivityState(),
                    gate = FakeLocalNetworkGate(),
                )
            val worker =
                TestListenableWorkerBuilder<SyncWorker>(app)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = SyncWorker(appContext, workerParameters, engine)
                        },
                    ).build()
            block(worker)
        } finally {
            db.close()
        }
    }
}
