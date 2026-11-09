// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room3.withWriteTransaction
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangesPage
import ch.lkmc.neutrodyne.sync.protocol.dto.SyncResponse
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 10 Scheduling on Android's WorkManager half: `sync-now`/`sync-push`/`sync-periodic` carry the
 * documented constraints and policies, `KEEP` coalesces repeats, the foreground observer flips
 * the interactive flag and enqueues only while linked, an unlink clears every name, and the
 * driven `SyncWorker` runs the engine — retrying failures, cancelling on a revoked token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulingTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private val playback = FakePlaybackSyncPort()
    private lateinit var db: NeutrodyneDatabase
    private lateinit var scheduler: WorkManagerSyncScheduler

    private val workManager get() = WorkManager.getInstance(app)
    private val testDriver: TestDriver
        get() = checkNotNull(WorkManagerTestInitHelper.getTestDriver(app))

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `sync-now rides KEEP with connected constraints and the expedited flag`() =
        runTest {
            db = TestDb.inMemory(app)
            initWorkManager()
            scheduler = scheduler()

            scheduler.enqueueSyncNow()
            scheduler.enqueueSyncNow()

            val infos = awaitWorks(SyncWorker.WORK_NOW)
            assertThat(infos).hasSize(1)
            val spec = specOf(infos.single().id.toString())
            assertThat(spec.input.getString(SyncWorker.KEY_WORK)).isEqualTo(SyncWorker.WORK_NOW)
            assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
            assertThat(spec.expedited).isTrue()
        }

    @Test
    fun `sync-push rides KEEP with the ten-second coalescing delay`() =
        runTest {
            db = TestDb.inMemory(app)
            initWorkManager()
            scheduler = scheduler()

            scheduler.schedulePush(SyncRoundReason.OUTBOX)
            scheduler.schedulePush(SyncRoundReason.OUTBOX)

            val infos = awaitWorks(SyncWorker.WORK_PUSH)
            assertThat(infos).hasSize(1)
            val spec = specOf(infos.single().id.toString())
            assertThat(spec.input.getString(SyncWorker.KEY_WORK)).isEqualTo(SyncWorker.WORK_PUSH)
            assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
            assertThat(spec.initialDelay).isEqualTo(PUSH_DELAY_MS)
        }

    @Test
    fun `sync-periodic arms the hourly pull only while linked`() =
        runTest {
            db = TestDb.inMemory(app)
            initWorkManager()
            scheduler = scheduler()

            link()
            scheduler.reschedulePeriodic()
            val periodic = awaitWorks(SyncWorker.WORK_PERIODIC)
            assertThat(periodic).hasSize(1)
            val spec = specOf(periodic.single().id.toString())
            assertThat(spec.intervalDuration).isEqualTo(TimeUnit.MINUTES.toMillis(60))
            assertThat(spec.flexDuration).isEqualTo(TimeUnit.MINUTES.toMillis(8))
            assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)

            // Unlinked: every sync work name disappears.
            db.syncStateDao().unlink()
            scheduler.reschedulePeriodic()
            awaitWorksGone(SyncWorker.WORK_PERIODIC)
        }

    @Test
    fun `onUnlinked cancels every sync work name`() =
        runTest {
            db = TestDb.inMemory(app)
            initWorkManager()
            scheduler = scheduler()

            link()
            scheduler.enqueueSyncNow()
            scheduler.schedulePush(SyncRoundReason.OUTBOX)
            scheduler.reschedulePeriodic()
            awaitWorks(SyncWorker.WORK_NOW)
            awaitWorks(SyncWorker.WORK_PUSH)
            awaitWorks(SyncWorker.WORK_PERIODIC)

            scheduler.onUnlinked()

            awaitWorksGone(SyncWorker.WORK_NOW)
            awaitWorksGone(SyncWorker.WORK_PUSH)
            awaitWorksGone(SyncWorker.WORK_PERIODIC)
        }

    @Test
    fun `the foreground observer enqueues sync-now only while linked`() =
        runTest {
            db = TestDb.inMemory(app)
            initWorkManager()
            scheduler = scheduler()
            val observer = observer()

            // Unlinked: the flag flips — later changes still get the in-process path — but no
            // work goes out.
            observer.onStart(owner)
            advanceUntilIdle()
            awaitQuiet()
            assertThat(scheduler.uiVisible.value).isTrue()
            assertThat(workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NOW).get()).isEmpty()

            link()
            observer.onStart(owner)
            val infos = awaitWorks(SyncWorker.WORK_NOW)
            assertThat(infos).hasSize(1)

            observer.onStop(owner)
            assertThat(scheduler.uiVisible.value).isFalse()
        }

    @Test
    fun `a driven sync-now runs the engine round`() =
        runTest {
            db = TestDb.inMemory(app)
            link()
            val client = ScriptedSyncClient()
            val engine = engine(client)
            scheduler = scheduler()
            installWorkerFactory(engine)

            scheduler.enqueueSyncNow()
            val work = awaitWorks(SyncWorker.WORK_NOW).single()

            // Constraints gate the launch until TestDriver satisfies them.
            assertThat(client.calls).isEmpty()
            testDriver.setAllConstraintsMet(work.id)
            awaitInfo(work.id) { it.state == WorkInfo.State.SUCCEEDED }

            assertThat(client.calls).isEqualTo(listOf("sync"))
        }

    @Test
    fun `a transport failure reschedules the work`() =
        runTest {
            db = TestDb.inMemory(app)
            link()
            val client = ScriptedSyncClient(failWith = SyncTransportException(500, null, "boom"))
            val engine = engine(client)
            scheduler = scheduler()
            installWorkerFactory(engine)

            scheduler.enqueueSyncNow()
            val work = awaitWorks(SyncWorker.WORK_NOW).single()
            testDriver.setAllConstraintsMet(work.id)

            awaitInfo(work.id) { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }
        }

    @Test
    fun `a rejected token revokes the link and cancels every sync work`() =
        runTest {
            db = TestDb.inMemory(app)
            link()
            val client = ScriptedSyncClient(failWith = SyncTransportException(401, null, "nope"))
            val engine = engine(client)
            scheduler = scheduler()
            installWorkerFactory(engine)

            scheduler.enqueueSyncNow()
            scheduler.reschedulePeriodic()
            awaitWorks(SyncWorker.WORK_PERIODIC)
            val work = awaitWorks(SyncWorker.WORK_NOW).single()

            testDriver.setAllConstraintsMet(work.id)
            // The worker's own cancel lands inside doWork — the run ends cancelled or failed,
            // either way finished; the revocation and the dropped periodic work are the point.
            awaitInfo(work.id) { it.state.isFinished }

            assertThat(db.syncStateDao().get()?.lastError).isEqualTo(SYNC_ERROR_REVOKED)
            awaitWorksGone(SyncWorker.WORK_PERIODIC)
        }

    // --- plumbing ---------------------------------------------------------------------------------

    private val owner =
        object : LifecycleOwner {
            private val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }

    private fun TestScope.scheduler() =
        WorkManagerSyncScheduler(
            application = app,
            db = db,
            playback = playback,
        )

    private fun TestScope.observer() =
        SyncForegroundObserver(
            workScheduler = Provider { scheduler },
            db = Provider { db },
            appScope = this,
        )

    /** A plain test WorkManager (no workers needed for the enqueue-shape tests). */
    private fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    }

    /** The `MetroWorkerFactory` equivalent: a `SyncWorker` bound to the test's engine. */
    private fun TestScope.installWorkerFactory(engine: SyncEngine) {
        // The worker's 401 path calls `SyncScheduler.onUnlinked` — wire it to the same
        // `WorkManagerSyncScheduler` hooks so the test sees the real cancellation.
        val syncScheduler =
            SyncScheduler(
                db = db,
                round = { reason -> engine.round(reason) },
                playback = playback,
                network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                clock = clock,
                scope = backgroundScope,
                hooks = scheduler,
            )
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? =
                            if (workerClassName == SyncWorker::class.qualifiedName) {
                                SyncWorker(appContext, workerParameters, engine, syncScheduler)
                            } else {
                                null
                            }
                    },
                ).build(),
        )
    }

    private suspend fun link() {
        db.withWriteTransaction {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    serverUrl = "https://sync.example",
                    accountId = "acc",
                    deviceId = "dev",
                    nodeId = "0000000000000001",
                    linkedAt = clock.now(),
                ),
            )
        }
    }

    /** The real engine over the scripted client — what the production worker wraps. */
    private suspend fun engine(client: SyncClient): SyncEngine {
        val secrets = FakeSecretStore()
        val tokens = SyncTokenStore(secrets)
        tokens.save("sync.example", "nds_test")
        val applier =
            SyncApplier(
                db = db,
                clock = clock,
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
            )
        return SyncEngine(
            db = db,
            client = client,
            applier = applier,
            clock = clock,
            secrets = secrets,
            sharePasswords = { false },
            address = FakeSyncServerAddress(serverUrl = "https://sync.example"),
            tokens = tokens,
            activity = SyncActivityState(),
            gate = FakeLocalNetworkGate(),
            effects = SyncApplyEffects(db, FakeSettingsSyncPort(), TestSettingsWriteCoordinator()) { true },
            capture = SettingsCapture(db, FakeSettingsSyncPort(), TestSettingsWriteCoordinator()) { true },
        )
    }

    private fun TestScope.awaitWorks(
        name: String,
        deadlineMs: Long = AWAIT_MS,
    ): List<WorkInfo> {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            val infos = workManager.getWorkInfosForUniqueWork(name).get()
            if (infos.isNotEmpty()) return infos
            Thread.sleep(POLL_MS)
        }
        error("no work enqueued for $name within ${deadlineMs}ms")
    }

    /** Cancelled work stays in the list — "gone" means nothing live remains. */
    private fun TestScope.awaitWorksGone(
        name: String,
        deadlineMs: Long = AWAIT_MS,
    ) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (workManager.getWorkInfosForUniqueWork(name).get().none { !it.state.isFinished }) return
            Thread.sleep(POLL_MS)
        }
        error("work $name still enqueued after ${deadlineMs}ms")
    }

    private fun TestScope.awaitInfo(
        id: UUID,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            workManager.getWorkInfoById(id).get()?.let { if (predicate(it)) return it }
            Thread.sleep(POLL_MS)
        }
        error("work $id stuck (awaited ${AWAIT_MS}ms)")
    }

    /** Lets the observer's launched coroutine settle when no work is expected. */
    private suspend fun TestScope.awaitQuiet() {
        val deadline = System.currentTimeMillis() + QUIET_MS
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(POLL_MS)
        }
    }

    /** The persisted spec: `WorkInfo` hides the input data and constraints. */
    private suspend fun specOf(id: String): WorkSpec =
        withContext(Dispatchers.IO) {
            checkNotNull((workManager as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(id))
        }

    private companion object {
        const val PUSH_DELAY_MS = 10_000L
        const val AWAIT_MS = 5_000L
        const val QUIET_MS = 300L
        const val POLL_MS = 15L
    }
}

/** A `SyncClient` scripted for `sync`; every other call is unused here. */
private class ScriptedSyncClient(
    private val failWith: Throwable? = null,
) : SyncClient {
    val calls = mutableListOf<String>()

    override suspend fun sync(
        since: String?,
        changes: List<ChangeDto>,
    ): SyncResponse {
        calls += "sync"
        failWith?.let { throw it }
        return SyncResponse(emptyList(), emptyList(), "cursor-1", hasMore = false, serverTime = "2026-10-04T00:00:00Z")
    }

    override suspend fun changes(
        since: String?,
        limit: Int,
    ): ChangesPage = ChangesPage(emptyList(), "cursor-1", hasMore = false, serverTime = "2026-10-04T00:00:00Z")

    override suspend fun discover(baseUrl: String) = error("unused")

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        device: ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto,
    ) = error("unused")

    override suspend fun redeemInvite(
        baseUrl: String,
        code: String,
        device: ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto,
    ) = error("unused")

    override suspend fun linkStart(
        baseUrl: String,
        device: ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto,
    ) = error("unused")

    override suspend fun linkToken(
        baseUrl: String,
        deviceCode: String,
    ) = error("unused")

    override suspend fun linkApprove(
        baseUrl: String,
        token: String,
        userCode: String,
    ) = error("unused")

    override suspend fun linkConfirm(
        baseUrl: String,
        token: String,
        userCode: String,
        approve: Boolean,
    ) = Unit

    override suspend fun logout() = Unit

    override suspend fun devices() = error("unused")

    override suspend fun renameDevice(
        deviceId: String,
        name: String,
    ) = error("unused")

    override suspend fun revokeDevice(deviceId: String) = Unit

    override suspend fun account() = error("unused")

    override suspend fun reset(
        confirm: String,
        mode: String,
        beforeCursor: String?,
    ) = error("unused")
}
