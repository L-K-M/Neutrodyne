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
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
                    effects = SyncApplyEffects(db, settings, secrets, FakeCredentialCommitCoordinator()),
                    notices = SyncNoticeBus(),
                )
            val scheduler =
                SyncScheduler(
                    db = db,
                    round = {},
                    playback = NoPlaybackSync,
                    network = FakeNetworkMonitor(),
                    clock = clock,
                    scope = CoroutineScope(currentCoroutineContext()),
                    hooks =
                        object : SyncScheduleHooks {
                            override fun interactive(): Boolean = false

                            override fun schedulePush(reason: ch.lkmc.neutrodyne.sync.api.SyncRoundReason) = Unit

                            override suspend fun onLinked() = Unit

                            override suspend fun onUnlinked() = Unit
                        },
                )
            val worker =
                TestListenableWorkerBuilder<SyncWorker>(app)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker = SyncWorker(appContext, workerParameters, engine, scheduler)
                        },
                    ).build()
            block(worker)
        } finally {
            db.close()
        }
    }
}
