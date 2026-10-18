// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.PrivacyCounts
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import ch.lkmc.neutrodyne.core.model.SnapshotStatus
import ch.lkmc.neutrodyne.core.model.SnapshotWrite
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

/**
 * `SnapshotNowReceiver` (05 Testing with bmgr): `goAsync` bounds the broadcast while the real
 * `writeSnapshotNow` runs on the application scope; the 8 s wait never cancels the write, the
 * ordered-broadcast line answers `ok at=… bytes=… level=…` only on a proven publication, and
 * `disabled`/`guarded`/`pending`/`error …` cover every other outcome. `PendingResult.finish()`
 * runs on all of them. The `AndroidAppGraph` here is a proxy answering only `inject` — the
 * receiver's own member-injection seam — with test doubles; every other member would throw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = SnapshotNowReceiverTest.TestApplication::class, sdk = [34])
class SnapshotNowReceiverTest {
    private val application = RuntimeEnvironment.getApplication() as TestApplication

    @Test
    fun `a publication answers ok with the after status`() =
        runTest {
            application.repository =
                StubBackupRepository {
                    Outcome.Success(
                        SnapshotWrite(
                            before = status(enabled = true),
                            after =
                                status(enabled = true, lastWrittenAt = 42L, bytes = 7_000L, level = 1),
                        ),
                    )
                }
            val pending = receive()

            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_OK)
            assertThat(pending.resultData).isEqualTo("ok at=42 bytes=7000 level=1")
            assertThat(pending.isFinished()).isTrue()
        }

    @Test
    fun `an unchanged after status answers guarded`() =
        runTest {
            // A previous publication the receiver did not make must not read as `ok`.
            val written = status(enabled = true, lastWrittenAt = 42L, bytes = 7_000L, level = 0)
            application.repository =
                StubBackupRepository { Outcome.Success(SnapshotWrite(written, written)) }
            val pending = receive()

            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            assertThat(pending.resultData).isEqualTo("guarded")
            assertThat(pending.isFinished()).isTrue()
        }

    @Test
    fun `a disabled lane answers disabled`() =
        runTest {
            application.repository =
                StubBackupRepository {
                    Outcome.Success(SnapshotWrite(status(enabled = false), status(enabled = false)))
                }
            val pending = receive()

            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            assertThat(pending.resultData).isEqualTo("disabled")
            assertThat(pending.isFinished()).isTrue()
        }

    @Test
    fun `a typed failure answers error`() =
        runTest {
            application.repository =
                StubBackupRepository { Outcome.Failure(BackupError.NewerFormat(3)) }
            val pending = receive()

            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            assertThat(pending.resultData).isEqualTo("error newer_format")
            assertThat(pending.isFinished()).isTrue()
        }

    @Test
    fun `an unexpected throw answers error and still finishes`() =
        runTest {
            application.repository = StubBackupRepository { error("producer blew up") }
            val pending = receive()

            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            assertThat(pending.resultData).isEqualTo("error io")
            assertThat(pending.isFinished()).isTrue()
        }

    @Test
    fun `a cancelled write still finishes the broadcast`() =
        runTest {
            application.repository = StubBackupRepository { throw CancellationException("write cancelled") }

            val pending = receive()

            assertThat(pending.isFinished()).isTrue()
            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            assertThat(pending.resultData).isEqualTo("error io")
        }

    @Test
    fun `an already cancelled application scope still finishes the broadcast`() =
        runTest {
            var writeStarted = false
            application.repository =
                StubBackupRepository {
                    writeStarted = true
                    Outcome.Success(SnapshotWrite(status(), status()))
                }
            val stopped = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            stopped.cancel()

            val pending = receive(stopped)

            assertThat(pending.isFinished()).isTrue()
            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            assertThat(writeStarted).isFalse()
        }

    @Test
    fun `an injection failure answers error and finishes the broadcast`() =
        runTest {
            application.repository = StubBackupRepository { error("write must not start") }
            application.injectionFailure = IllegalStateException("graph unavailable")

            val pending = receive()

            assertThat(pending.isFinished()).isTrue()
            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            assertThat(pending.resultData).isEqualTo("error io")
        }

    @Test
    fun `the eight second wait answers pending without cancelling the write`() =
        runTest {
            val gate = CompletableDeferred<Outcome<SnapshotWrite, BackupError>>()
            application.repository = StubBackupRepository { gate.await() }
            val pending = receive()

            // The wait budget elapsed; the application-scope write is still suspended.
            assertThat(pending.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            assertThat(pending.resultData).isEqualTo("pending")
            assertThat(pending.isFinished()).isTrue()
            assertThat(gate.isActive).isTrue()

            // The write completes afterwards on its own — the timeout only ended the wait.
            gate.complete(Outcome.Success(SnapshotWrite(status(), status())))
            advanceUntilIdle()
        }

    @Test
    fun `the manifest export is DUMP-gated`() {
        // 05 Testing with bmgr: explicit broadcasts only (the receiver declares no
        // intent-filter), and only from a DUMP holder — the shell user. `exported` and
        // `permission` read the manifest's merged values.
        val info =
            application.packageManager.getReceiverInfo(
                android.content.ComponentName(application, SnapshotNowReceiver::class.java),
                0,
            )
        assertThat(info.exported).isTrue()
        assertThat(info.permission).isEqualTo(android.Manifest.permission.DUMP)
    }

    // -- Fixtures --------------------------------------------------------------------------------

    /**
     * A broadcast against a receiver instance with its framework pending result: `goAsync`
     * returns it, the launched report sets the ordered-broadcast line, and `finish()` completes
     * its shadow's future. `wentAsync` proves the broadcast stayed alive for the launch.
     */
    private suspend fun TestScope.receive(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
    ): BroadcastReceiver.PendingResult {
        // The bound `@ApplicationScope` is a SupervisorJob scope (CoreBindings): a failed
        // write stays inside its Deferred for `await` to report — it never cancels the
        // reporting launch. Mirror that here on the shared test scheduler.
        application.scope = scope
        val pending = newPendingResult()
        val receiver = SnapshotNowReceiver()
        // `setPendingResult` is framework-hidden; the real dispatch calls it before onReceive.
        ReflectionHelpers.callInstanceMethod<Unit>(
            receiver,
            "setPendingResult",
            ReflectionHelpers.ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending),
        )
        receiver.onReceive(application, Intent())
        advanceUntilIdle()
        assertThat(shadowOf(receiver).wentAsync()).isTrue()
        return pending
    }

    private fun newPendingResult(): BroadcastReceiver.PendingResult =
        ReflectionHelpers.callStaticMethod(
            ShadowBroadcastPendingResult::class.java,
            "create",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Bundle::class.java, null),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )

    private fun BroadcastReceiver.PendingResult.isFinished(): Boolean =
        Shadow.extract<ShadowBroadcastPendingResult>(this).future.isDone

    private fun status(
        supported: Boolean = true,
        enabled: Boolean = true,
        lastWrittenAt: Long? = null,
        bytes: Long? = null,
        level: Int = 0,
        lastError: String? = null,
        foreignPending: Boolean = false,
    ) = SnapshotStatus(supported, enabled, lastWrittenAt, bytes, level, lastError, foreignPending)

    /**
     * The receiver's `Lazy<BackupRepository>` resolved to a stub: `writeSnapshotNow` is the
     * only member the lane calls; anything else is a programming error, not a typed failure.
     */
    private class StubBackupRepository(
        private val write: suspend () -> Outcome<SnapshotWrite, BackupError>,
    ) : BackupRepository {
        override suspend fun createBackup(
            destinationUri: String,
            includePasswords: Boolean,
        ): Outcome<BackupSummary, BackupError> = error("unused")

        override suspend fun inspect(sessionId: Long): Outcome<BackupPreview, BackupError> = error("unused")

        override suspend fun restore(
            sessionId: Long,
            request: RestoreRequest,
        ): Outcome<Unit, BackupError> = error("unused")

        override fun observeRestore(): Flow<RestoreProgress?> = emptyFlow()

        override fun observeSnapshotStatus(): Flow<SnapshotStatus> = emptyFlow()

        override suspend fun setSnapshotEnabled(enabled: Boolean) = error("unused")

        override suspend fun preflight(includePasswords: Boolean): PrivacyCounts = error("unused")

        override suspend fun restoreAndroidBackup(): Outcome<Long, BackupError> = error("unused")

        override suspend fun discardAndroidBackup() = error("unused")

        override suspend fun writeSnapshotNow(): Outcome<SnapshotWrite, BackupError> = write()
    }

    /**
     * The `GraphHolder` the receiver reaches through `applicationContext`: its `AndroidAppGraph`
     * is a proxy implementing `inject` — the one member the framework-created receiver needs —
     * so the test decides what `Lazy<BackupRepository>` and the `@ApplicationScope` scope hold.
     */
    class TestApplication :
        Application(),
        GraphHolder {
        var repository: BackupRepository? = null
        var injectionFailure: RuntimeException? = null
        lateinit var scope: CoroutineScope

        override val graph: Any by lazy {
            Proxy.newProxyInstance(
                AndroidAppGraph::class.java.classLoader,
                arrayOf(AndroidAppGraph::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "inject" -> {
                        injectionFailure?.let { throw it }
                        (args[0] as SnapshotNowReceiver).apply {
                            appScope = scope
                            backupRepository = lazyOf(checkNotNull(repository))
                        }
                    }

                    else -> {
                        error("unexpected graph member ${method.name}")
                    }
                }
            }
        }
    }
}
