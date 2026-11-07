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
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
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

    private suspend fun withWorker(
        failure: Exception,
        block: suspend (SyncWorker) -> Unit,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val db = TestDb.inMemory(app)
        try {
            // The real round fails at its first suspending address read, before any HTTP call.
            val address =
                object : SyncServerAddress {
                    override suspend fun serverUrl(): String = throw failure

                    override suspend fun username(): String? = null

                    override suspend fun shareFeedPasswords(): Boolean = false

                    override suspend fun syncPlaybackSettings(): Boolean = true
                }
            val client =
                Proxy.newProxyInstance(SyncClient::class.java.classLoader, arrayOf(SyncClient::class.java)) { _, _, _ ->
                    error("The cancelled round must not reach the HTTP client")
                } as SyncClient
            val engine =
                SyncEngine(
                    db,
                    client,
                    TestClock(),
                    FakeSecretStore(),
                    FakeSettingsSyncPort(),
                    NoPlaybackSync,
                    SyncIngestHook.None,
                    FakeGroupChannelSync(),
                    sharePasswords = { false },
                    address,
                    SyncTokenStore(FakeSecretStore()),
                    SyncActivityState(),
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
