// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.LibraryCountsDto
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DisconnectLifetimeTest {
    @Test fun cancellingTheCallerAfterLogoutDoesNotAbandonCleanup() = runTest { cancelledCommit(purge = false) }

    @Test fun cancellingTheCallerAfterPurgeDoesNotAbandonCleanup() = runTest { cancelledCommit(purge = true) }

    private suspend fun TestScope.cancelledCommit(purge: Boolean) {
        val h = harness()
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.seedLinked()
            // This case isolates post-logout ownership. The separate happy-path test covers
            // a successful push; an offline best-effort push must still reach logout here.
            h.client.syncHandler = { _, _ -> throw java.io.IOException("offline push") }
            h.client.logoutHandler = {
                committed.complete(Unit)
                release.await()
            }
            h.client.resetHandler = { _, _, _ ->
                committed.complete(Unit)
                release.await()
                emptyAccount()
            }
            val caller =
                async {
                    if (purge) h.controller.deleteServerData("DELETE") else h.controller.unlink()
                }
            committed.await()
            caller.cancel()
            release.complete(Unit)
            caller.join()
            await(h) {
                h.controller.status.value == SyncStatus.NotConfigured &&
                    !h.tokenStoreContains(HOST) && "unlinked" in h.schedulerCalls
            }
            assertFalse(h.tokenStoreContains(HOST))
            assertEquals(
                null,
                h.db
                    .syncStateDao()
                    .get()
                    ?.deviceId,
            )
            assertTrue("unlinked" in h.schedulerCalls)
        } finally {
            release.complete(Unit)
            h.close()
        }
    }

    @Test fun cancellingBeforeThePushFinishesStillAbortsTheUnlink() =
        runTest {
            val h = harness()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                h.seedLinked()
                h.client.syncHandler = { _, changes ->
                    entered.complete(Unit)
                    release.await()
                    defaultSyncResponse(changes)
                }
                val caller = async { h.controller.unlink() }
                entered.await()
                caller.cancel()
                release.complete(Unit)
                caller.join()
                assertFalse("logout" in h.client.calls)
                assertTrue(h.tokenStoreContains(HOST))
                assertTrue(
                    h.db
                        .syncStateDao()
                        .get()
                        ?.enabled == true,
                )
            } finally {
                release.complete(Unit)
                h.close()
            }
        }

    @Test fun aRelinkDuringThePushCannotBeUnlinkedByTheOldConfirmation() =
        runTest {
            val h = harness()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                h.seedLinked()
                h.client.syncHandler = { _, changes ->
                    entered.complete(Unit)
                    release.await()
                    defaultSyncResponse(changes)
                }
                val caller = async { h.controller.unlink() }
                entered.await()
                h.tokens.save(HOST, "nds_relinked")
                h.db.syncStateDao().upsert(checkNotNull(h.db.syncStateDao().get()).copy(accountId = OTHER_ACCOUNT))
                release.complete(Unit)
                assertEquals(Outcome.Failure(SyncProblem.Revoked), caller.await())
                assertFalse("logout" in h.client.calls)
                assertEquals("nds_relinked", h.tokens.load(HOST))
                assertEquals(
                    OTHER_ACCOUNT,
                    h.db
                        .syncStateDao()
                        .get()
                        ?.accountId,
                )
            } finally {
                release.complete(Unit)
                h.close()
            }
        }

    @Test fun aNewAuthorizationWaitsForTheAdmittedPurgeAndCleanup() =
        runTest {
            val h =
                Harness(
                    this,
                    1_800_000_000_000L,
                    controllerDispatcher = StandardTestDispatcher(testScheduler),
                )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val purge =
                async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    h.controller.deleteServerData("DELETE")
                }
            var loggedIn = false
            try {
                h.seedLinked()
                h.client.resetHandler = { _, _, _ ->
                    entered.complete(Unit)
                    release.await()
                    emptyAccount()
                }
                h.client.login = { _, _ ->
                    loggedIn = true
                    TokenResponse("nds_second", OTHER_ACCOUNT, OTHER_DEVICE, "bob")
                }
                h.client.summary = emptyAccount()
                purge.start()
                entered.await()
                h.controller.startLink(OTHER_SERVER, LinkMethod.PASSWORD)
                await(h) { h.linkFlow.state.value is LinkState.AwaitingInput }
                h.controller.submitPassword("bob", "test-password".toCharArray())
                await(h) { loggedIn }
                h.tick()
                assertEquals(
                    SERVER,
                    h.address.serverUrl(),
                    "a new identity must wait until the old purge finishes",
                )
                release.complete(Unit)
                assertTrue(purge.await() is Outcome.Success)
                await(h) { (h.controller.status.value as? SyncStatus.Linked)?.accountId == OTHER_ACCOUNT }
                assertEquals("nds_second", h.tokens.load("second.example"))
            } finally {
                release.complete(Unit)
                purge.cancel()
                purge.join()
                h.close()
            }
        }

    private fun TestScope.harness() = Harness(this, 1_800_000_000_000L)

    private suspend fun await(
        h: Harness,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "disconnect condition did not become true: ${h.controller.status.value}"
            }
            h.tick()
            Thread.sleep(2)
        }
    }

    private fun emptyAccount() = AccountSummary("bob", LibraryCountsDto(0, 0, 0, 0, 0), 1, 0, 0)

    private companion object {
        const val HOST = "sync.example"
        const val SERVER = "https://sync.example"
        const val OTHER_SERVER = "https://second.example"
        const val OTHER_ACCOUNT = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val OTHER_DEVICE = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    }
}
