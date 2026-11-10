// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 10 Episode-state rules rule 4 ("pull before play"): linked + online + no recent round is the
 * only case that spawns a round; the whole wait — storage read, coalescing mutex and join —
 * caps at 1.5 s while the same round keeps running in the app scope; concurrent UI waits
 * coalesce; failures and caller cancellation never block or kill the round.
 *
 * Room's query context shares the test scheduler, so the virtual timeout can never advance past
 * a real in-flight query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PrePlaySyncImplTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = TestClock()
    private var db: NeutrodyneDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    // The test scope stands in for the app scope: its children are not the caller's, so a
    // cancelled or timed-out wait leaves the round running — and this scheduler drives it.
    private fun TestScope.holder(
        network: FakeNetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
        database: suspend () -> NeutrodyneDatabase = { db!! },
        round: suspend (SyncRoundReason) -> Unit,
    ) = PrePlaySyncImpl(database, round, network, clock, this)

    private fun TestScope.inMemoryDb(): NeutrodyneDatabase =
        TestDb.inMemory(app, queryContext = UnconfinedTestDispatcher(testScheduler)).also { db = it }

    private suspend fun link(
        lastSyncAt: Long? = null,
        enabled: Boolean = true,
        lastError: String? = null,
    ) {
        db!!.withWriteTransaction {
            db!!.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = enabled,
                    serverUrl = "https://sync.example",
                    accountId = "acc",
                    deviceId = "dev",
                    nodeId = "0000000000000001",
                    linkedAt = clock.now() - 60_000,
                    lastSyncAt = lastSyncAt,
                    lastError = lastError,
                ),
            )
        }
    }

    @Test
    fun linkedAndOnlineRunsOnePrePlayRound() =
        runTest {
            inMemoryDb()
            link()
            val reasons = mutableListOf<SyncRoundReason>()
            holder { reasons += it }.beforeStart()

            assertThat(reasons).containsExactly(SyncRoundReason.PRE_PLAY)
        }

    @Test
    fun unlinkedSpawnsNoRound() =
        runTest {
            inMemoryDb()
            var rounds = 0
            holder { rounds++ }.beforeStart()

            assertThat(rounds).isEqualTo(0)
        }

    @Test
    fun disabledSwitchSpawnsNoRound() =
        runTest {
            inMemoryDb()
            link(enabled = false)
            var rounds = 0
            holder { rounds++ }.beforeStart()

            assertThat(rounds).isEqualTo(0)
        }

    @Test
    fun revokedLinkSpawnsNoRound() =
        runTest {
            inMemoryDb()
            link(lastError = SYNC_ERROR_REVOKED)
            var rounds = 0
            holder { rounds++ }.beforeStart()

            assertThat(rounds).isEqualTo(0)
        }

    @Test
    fun offlineNeverTouchesTheDatabase() =
        runTest {
            // The provider would throw if the read ran: offline must bypass it entirely.
            val sync =
                holder(
                    round = { error("must not run") },
                    network = FakeNetworkMonitor(FakeNetworkMonitor.OFFLINE),
                    database = { error("offline must not read") },
                )

            sync.beforeStart()
        }

    @Test
    fun aRoundCompletedUnderFifteenSecondsAgoSkipsThePull() =
        runTest {
            inMemoryDb()
            link(lastSyncAt = clock.now() - 14_999)
            var rounds = 0
            holder { rounds++ }.beforeStart()

            assertThat(rounds).isEqualTo(0)
        }

    @Test
    fun aRoundCompletedExactlyFifteenSecondsAgoStillPulls() =
        runTest {
            inMemoryDb()
            link(lastSyncAt = clock.now() - 15_000)
            var rounds = 0
            holder { rounds++ }.beforeStart()

            assertThat(rounds).isEqualTo(1)
        }

    @Test
    fun aFutureLastSyncAtIsNotARecentRound() =
        runTest {
            inMemoryDb()
            link(lastSyncAt = clock.now() + 60_000)
            var rounds = 0
            holder { rounds++ }.beforeStart()

            assertThat(rounds).isEqualTo(1)
        }

    @Test
    fun theWaitCapsAtTheBudgetWhileTheRoundKeepsRunning() =
        runTest {
            inMemoryDb()
            link()
            var completed = false
            val startAt = testScheduler.currentTime
            holder {
                delay(5_000)
                completed = true
            }.beforeStart()

            assertThat(testScheduler.currentTime - startAt).isAtMost(1_600L)
            assertThat(completed).isFalse()

            advanceUntilIdle()
            assertThat(completed).isTrue()
        }

    @Test
    fun aBlockedStorageReadStillReturnsInsideTheBudget() =
        runTest {
            // A resolver that never answers hangs the open; the call's own budget must still
            // release the start — storage cannot stretch the UI wait.
            val startAt = testScheduler.currentTime
            holder(
                round = { error("must not run") },
                database = { CompletableDeferred<NeutrodyneDatabase>().await() },
            ).beforeStart()

            assertThat(testScheduler.currentTime - startAt).isAtMost(1_600L)
        }

    @Test
    fun aHungQueryStillReturnsInsideTheBudget() =
        runTest {
            // A Room query context nobody advances hangs the read; same budget, deeper in.
            val deadDb =
                TestDb
                    .inMemory(
                        app,
                        queryContext = StandardTestDispatcher(TestCoroutineScheduler()),
                    ).also { db = it }
            val startAt = testScheduler.currentTime
            holder(
                round = { error("must not run") },
                database = { deadDb },
            ).beforeStart()

            assertThat(testScheduler.currentTime - startAt).isAtMost(1_600L)
        }

    @Test
    fun concurrentWaitsCoalesceOntoTheOneInflightRoundWithinOneBudget() =
        runTest {
            inMemoryDb()
            link()
            var rounds = 0
            val sync =
                holder {
                    delay(5_000)
                    rounds++
                }
            val startAt = testScheduler.currentTime
            val first = async { sync.beforeStart() }
            val second = async { sync.beforeStart() }
            first.await()
            second.await()

            // Both waits end inside one 1.5 s budget, not two serialized ones.
            assertThat(testScheduler.currentTime - startAt).isAtMost(1_600L)
            assertThat(rounds).isEqualTo(0)
            advanceUntilIdle()
            assertThat(rounds).isEqualTo(1)
        }

    @Test
    fun aFailedRoundNeverBlocksTheStart() =
        runTest {
            inMemoryDb()
            link()
            val sync = holder { error("transport down") }

            sync.beforeStart() // must return, not throw
        }

    @Test
    fun callerCancellationPropagatesAndLeavesTheRoundRunning() =
        runTest {
            inMemoryDb()
            link()
            var completed = false
            val sync =
                holder {
                    delay(5_000)
                    completed = true
                }
            var thrown: Throwable? = null
            val job =
                launch {
                    try {
                        sync.beforeStart()
                    } catch (failure: Throwable) {
                        thrown = failure
                    }
                }
            advanceTimeBy(400)
            job.cancel()
            advanceUntilIdle()

            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            assertThat(completed).isTrue()
        }

    @Test
    fun aDatabaseFailureCostsNoWait() =
        runTest {
            val sync =
                holder(
                    round = { error("must not run") },
                    database = { error("db not open") },
                )

            sync.beforeStart() // treated as not-linked: returns at once
        }
}
