// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.credentials.CredentialCommitCoordinatorImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 03 Basic auth and CredentialStore: `withCredentialCommit` runs mutually excluded with every
 * other holder — including the credential deletes — and the lock survives a block that throws
 * or is cancelled. It is not reentrant: a holder that calls it again deadlocks, which the last
 * test demonstrates with a timeout rather than by hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialCommitCoordinatorTest {
    @Test
    fun aSecondCommitWaitsUntilTheFirstReleases() =
        runTest {
            val coordinator = CredentialCommitCoordinatorImpl()
            val firstHeld = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondRan = CompletableDeferred<Unit>()

            launch {
                coordinator.withCredentialCommit {
                    firstHeld.complete(Unit)
                    releaseFirst.await()
                }
            }
            firstHeld.await()

            launch {
                coordinator.withCredentialCommit { secondRan.complete(Unit) }
            }
            testScheduler.runCurrent()

            assertFalse(secondRan.isCompleted)

            releaseFirst.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertTrue(secondRan.isCompleted)
        }

    @Test
    fun aThrowingBlockReleasesTheLock() =
        runTest {
            val coordinator = CredentialCommitCoordinatorImpl()

            val thrown =
                runCatching {
                    coordinator.withCredentialCommit { throw IllegalStateException("boom") }
                }
            assertIs<IllegalStateException>(thrown.exceptionOrNull())

            // A leaked lock would leave this suspended until the timeout cancels it.
            withTimeout(REACQUIRE_TIMEOUT_MS) {
                coordinator.withCredentialCommit { }
            }
        }

    @Test
    fun aCancelledHolderReleasesTheLock() =
        runTest {
            val coordinator = CredentialCommitCoordinatorImpl()
            val held = CompletableDeferred<Unit>()
            val holder =
                launch {
                    coordinator.withCredentialCommit {
                        held.complete(Unit)
                        suspendCancellableCoroutine { }
                    }
                }
            held.await()

            holder.cancelAndJoin()

            withTimeout(REACQUIRE_TIMEOUT_MS) {
                coordinator.withCredentialCommit { }
            }
        }

    @Test
    fun theMutexIsNotReentrant() =
        runTest {
            val coordinator = CredentialCommitCoordinatorImpl()
            val afterRelease = CompletableDeferred<Unit>()

            coordinator.withCredentialCommit {
                // The contract forbids a holder calling another acquiring path: the nested
                // acquire suspends on the held mutex and only the timeout ends it.
                val nested =
                    runCatching {
                        withTimeout(REACQUIRE_TIMEOUT_MS) {
                            coordinator.withCredentialCommit { }
                        }
                    }
                assertIs<TimeoutCancellationException>(nested.exceptionOrNull())
            }

            coordinator.withCredentialCommit { afterRelease.complete(Unit) }
            assertTrue(afterRelease.isCompleted)
        }

    private companion object {
        /** Virtual time; fires whenever a lock that should be free is not. */
        const val REACQUIRE_TIMEOUT_MS = 10_000L
    }
}
