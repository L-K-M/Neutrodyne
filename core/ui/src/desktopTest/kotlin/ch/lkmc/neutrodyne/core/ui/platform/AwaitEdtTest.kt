// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The EDT bridge behind the desktop file dialogs (D3 fix): an AWT throw inside the block must
 * resume the awaiting coroutine exceptionally instead of dying on the EDT and stranding the
 * continuation, and a cancelled caller must skip a still-queued block and run [awaitEdt]'s
 * cancel hook so a parked modal dialog is disposed. Real `SwingUtilities` EDT — no fake.
 */
class AwaitEdtTest {
    @Test
    fun anEdtThrowResumesTheAwaiterExceptionally() =
        runBlocking {
            val failure =
                assertFailsWith<IllegalStateException> {
                    withTimeout(5_000) {
                        awaitEdt<Any?> { throw IllegalStateException("dialog blew up on the EDT") }
                    }
                }
            assertEquals("dialog blew up on the EDT", failure.message)
        }

    @Test
    fun aDeadCallerSkipsTheQueuedBlock() =
        runBlocking {
            // Park the EDT so the block runnable can only execute after the caller is cancelled.
            val edtParked = CountDownLatch(1)
            val releaseEdt = CountDownLatch(1)
            SwingUtilities.invokeLater {
                edtParked.countDown()
                releaseEdt.await(5, TimeUnit.SECONDS)
            }
            assertTrue(edtParked.await(5, TimeUnit.SECONDS), "EDT never parked")

            var ran = false
            val job =
                launch {
                    awaitEdt {
                        ran = true
                        null
                    }
                }
            // Let the coroutine post its EDT runnable, then cancel before the EDT reaches it.
            yield()
            job.cancel()
            releaseEdt.countDown()
            SwingUtilities.invokeAndWait {} // flush: the queued block runnable has been processed

            assertFalse(ran, "a cancelled caller's block still ran on the EDT")
            job.join()
        }

    @Test
    fun aDeadCallerRunsTheCancelHookOnTheEdt() =
        runBlocking {
            // The block parks like a modal dialog; the cancel hook (a `dispose`) queued on the
            // EDT must run after the dialog returns — not before, not never.
            val blockEntered = CountDownLatch(1)
            val releaseBlock = CountDownLatch(1)
            val disposed = AtomicBoolean(false)
            val job =
                launch {
                    awaitEdt(onCancel = { disposed.set(true) }) {
                        blockEntered.countDown()
                        releaseBlock.await(5, TimeUnit.SECONDS)
                        "result"
                    }
                }
            yield() // let the coroutine post its EDT runnable before the thread parks below
            assertTrue(blockEntered.await(5, TimeUnit.SECONDS), "EDT block never entered")
            job.cancel()
            releaseBlock.countDown()

            withTimeout(5_000) {
                while (!disposed.get()) delay(10)
            }
            assertTrue(job.isCancelled)
        }
}
