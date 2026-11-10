// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.notifications

import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.DesktopNotifier
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.sync.api.MassChangePrompt
import ch.lkmc.neutrodyne.sync.api.SyncActivity
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Locale

/**
 * 10 Scheduling on the desktop's held-batch notice over a scripted [FakeSyncController] and a
 * fake [DesktopNotifier] (fakes only — nothing OS-facing posts): the window-hidden gate, the
 * service/notifier laziness behind the database gate, the single-slot post/cancel contract
 * and the profile-keyed dedupe.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopSyncHeldNotifierTest {
    private val originalLocale = Locale.getDefault()

    @Before
    fun english() {
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun aClosedGateResolvesNeitherFactory() =
        runTest {
            val h = Harness()
            // Held input parked behind a closed gate still resolves nothing.
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            h.hidden.value = true
            val job = h.collectOn(this)
            runCurrent()
            assertThat(h.syncCalls).isEqualTo(0)
            assertThat(h.notifierCalls).isEqualTo(0)
            job.cancel()
        }

    @Test
    fun aVisibleHeldBatchPostsNothing() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.hidden.value = false
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            h.gate.value = true
            await { h.syncCalls > 0 && h.notifierCalls > 0 }
            runCurrent()
            assertThat(h.ops).isEmpty()
            job.cancel()
        }

    @Test
    fun hiddenWithoutABatchOrALinkPostsNothing() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.hidden.value = true
            h.gate.value = true
            await { h.notifierCalls > 0 }

            h.sync.status.value = SyncStatus.NotConfigured
            h.sync.heldChanges.value = listOf(PROMPT)
            runCurrent()
            h.sync.status.value = SyncStatus.Revoked(LINKED.serverUrl)
            runCurrent()
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = emptyList()
            runCurrent()
            assertThat(h.ops).isEmpty()
            job.cancel()
        }

    @Test
    fun aHiddenLinkedBatchPostsSyncHeld() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = true
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            await { h.posted.isNotEmpty() }

            val n = h.posted.single()
            assertThat(n.id).isEqualTo("sync-held")
            assertThat(n.kind).isEqualTo(NotificationKind.SYNC_HELD)
            assertThat(n.title).isEqualTo("Sync is waiting for you")
            assertThat(n.body).isEqualTo("Pixel 8 removed 3 podcasts and 1 group. Apply here too?")
            assertThat(n.route).isEqualTo("neutrodyne://open/settings/sync")
            job.cancel()
        }

    @Test
    fun anUnnamedDeviceFallsBackToTheGenericWording() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = true
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT.copy(deviceName = null))
            await { h.posted.isNotEmpty() }

            assertThat(h.posted.single().body)
                .isEqualTo("Another device removed 3 podcasts and 1 group. Apply here too?")
            job.cancel()
        }

    @Test
    fun aReEmittedBatchDoesNotPostTwice() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = true
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            await { h.posted.size == 1 }

            // The same prompt set emitted again (here through an unrelated Linked field).
            h.sync.status.value = LINKED.copy(activity = SyncActivity.SYNCING)
            h.sync.heldChanges.value = listOf(PROMPT)
            runCurrent()
            assertThat(h.posted).hasSize(1)
            job.cancel()
        }

    @Test
    fun aBatchThatArrivedVisibleNotifiesOnceHidden() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = false
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            await { h.notifierCalls > 0 }
            runCurrent()
            assertThat(h.posted).isEmpty()

            h.hidden.value = true
            await { h.posted.isNotEmpty() }
            assertThat(h.posted).hasSize(1)
            job.cancel()
        }

    @Test
    fun aNewBatchNotifiesAgainUnderTheSameId() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = true
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            await { h.posted.size == 1 }

            // A second held batch behind the first: the slot refreshes, still oldest-first.
            h.sync.heldChanges.value = listOf(PROMPT, PROMPT.copy(id = 8, deviceName = "Pixel 9"))
            await { h.posted.size == 2 }
            assertThat(h.posted[1].id).isEqualTo("sync-held")
            assertThat(h.posted[1].body).isEqualTo("Pixel 8 removed 3 podcasts and 1 group. Apply here too?")
            job.cancel()
        }

    @Test
    fun theSameIdsUnderAnotherProfileAreANewNotice() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = true
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            await { h.posted.size == 1 }

            // The same prompt ids under a different account never dedupe against the old
            // profile's notice: the old post is retracted before the new one lands.
            h.sync.status.value = OTHER_PROFILE
            await { h.posted.size == 2 }
            assertThat(h.ops)
                .containsExactly("post:sync-held", "cancel:sync-held", "post:sync-held")
                .inOrder()
            job.cancel()
        }

    @Test
    fun aResolvedBatchRetractsTheNotice() =
        runTest {
            val h = Harness()
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = true
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            await { h.posted.size == 1 }

            h.sync.heldChanges.value = emptyList()
            await { h.cancelled.isNotEmpty() }
            assertThat(h.cancelled).containsExactly("sync-held")
            assertThat(h.posted).hasSize(1)
            job.cancel()
        }

    @Test
    fun aClosedGateDuringAnInFlightPostRetracts() =
        runTest {
            val h = Harness()
            h.postGate = CompletableDeferred()
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = true
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            await { h.postStarted.isCompleted }

            // The collector is cancelled while the post is suspended: teardown still
            // retracts whatever the attempt may have delivered.
            h.gate.value = false
            await { h.cancelled.isNotEmpty() }
            assertThat(h.cancelled).containsExactly("sync-held")
            assertThat(h.posted).isEmpty()
            job.cancel()
        }

    @Test
    fun aFailedPostCountsNothingAndRetries() =
        runTest {
            val h = Harness()
            h.postFailure = IOException("the notification server is gone")
            val job = h.collectOn(this)
            h.gate.value = true
            h.hidden.value = true
            h.sync.status.value = LINKED
            h.sync.heldChanges.value = listOf(PROMPT)
            await { h.ops.isNotEmpty() }
            assertThat(h.posted).isEmpty()

            h.postFailure = null
            // Any later emission retries the delivery the failure never confirmed.
            h.sync.status.value = LINKED.copy(lastSyncAtMs = 1)
            await { h.posted.isNotEmpty() }
            assertThat(h.posted).hasSize(1)
            job.cancel()
        }

    private fun TestScope.await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for the collector" }
            runCurrent()
            Thread.sleep(2)
        }
    }

    /** The window-side harness: scripted flows, a recording notifier and the real holder. */
    private class Harness {
        val gate = MutableStateFlow(false)
        val hidden = MutableStateFlow(false)
        val sync = FakeSyncController()
        val ops = mutableListOf<String>()
        val posted = mutableListOf<DesktopNotification>()
        val cancelled = mutableListOf<String>()
        val postStarted = CompletableDeferred<Unit>()
        var postGate: CompletableDeferred<Unit>? = null
        var postFailure: Throwable? = null
        var syncCalls = 0
        var notifierCalls = 0

        val notifier =
            object : DesktopNotifier {
                override suspend fun post(n: DesktopNotification) {
                    ops += "post:${n.id}"
                    postStarted.complete(Unit)
                    postGate?.await()
                    postFailure?.let { throw it }
                    posted += n
                }

                override fun cancel(id: String) {
                    ops += "cancel:$id"
                    cancelled += id
                }
            }

        val held = DesktopSyncHeldNotifier(notifier)

        fun collectOn(scope: CoroutineScope): Job =
            scope.launch {
                observeSyncHeldNotifications(
                    gate = gate,
                    hidden = hidden,
                    syncController = {
                        syncCalls++
                        sync
                    },
                    notifier = {
                        notifierCalls++
                        held
                    },
                )
            }
    }

    private companion object {
        val LINKED =
            SyncStatus.Linked(
                serverUrl = "https://sync.test",
                accountId = "account-1",
                deviceId = "device-1",
                username = null,
                nodeId = "node-1",
                lastSyncAtMs = null,
                activity = SyncActivity.IDLE,
                problem = null,
            )
        val OTHER_PROFILE = LINKED.copy(serverUrl = "https://other.test", accountId = "account-2")
        val PROMPT =
            MassChangePrompt(
                id = 7,
                deviceName = "Pixel 8",
                podcastTitles = listOf("A", "B", "C"),
                groupNames = listOf("tech"),
            )
    }
}
