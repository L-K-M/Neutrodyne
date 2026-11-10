// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class SyncIdentityLockTest {
    @Test
    fun aReaderCannotSeeHalfOfAnIdentityWrite() =
        runTest {
            val identity = SyncIdentityLock()
            val tokenWritten = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()
            var token = "a"
            var account = "a"
            val writer =
                backgroundScope.async {
                    identity.withLock {
                        token = "b"
                        tokenWritten.complete(Unit)
                        finishWrite.await()
                        account = "b"
                    }
                }
            tokenWritten.await()
            val reader = backgroundScope.async { identity.withLock { token to account } }
            runCurrent()
            assertFalse(reader.isCompleted)
            finishWrite.complete(Unit)
            writer.await()
            assertEquals("b" to "b", reader.await())
        }

    @Test
    fun theWriterCanTakeItsOwnFirstLinkSnapshot() =
        runTest {
            val identity = SyncIdentityLock()
            val result =
                withTimeout(1_000) {
                    identity.withLock { identity.withLock { "complete" } }
                }
            assertEquals("complete", result)
        }

    @Test
    fun anExpiredInheritedMarkerCannotBypassAnotherWriter() =
        runTest {
            val identity = SyncIdentityLock()
            var copied: CoroutineContext = EmptyCoroutineContext
            identity.withLock { copied = currentCoroutineContext().minusKey(Job) }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer =
                backgroundScope.async {
                    identity.withLock {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            val staleChild = backgroundScope.async(copied) { identity.withLock { "read" } }
            runCurrent()
            assertFalse(staleChild.isCompleted)
            release.complete(Unit)
            writer.await()
            assertEquals("read", staleChild.await())
        }

    @Test
    fun aFailedIdentityWriteReleasesOwnership() =
        runTest {
            val identity = SyncIdentityLock()
            assertFailsWith<IllegalStateException> {
                identity.withLock { error("fixture failure") }
            }
            assertEquals("next", withTimeout(1_000) { identity.withLock { "next" } })
        }
}
