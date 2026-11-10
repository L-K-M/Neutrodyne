// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStaleRevocationTest {
    @Test fun aLateDeviceList401CannotRevokeAReplacementLink() = runTest { stale401("devices") }

    @Test fun aLateRename401CannotRevokeAReplacementLink() = runTest { stale401("rename") }

    @Test fun aLateDeviceRevoke401CannotRevokeAReplacementLink() = runTest { stale401("revoke") }

    @Test fun aLateApproval401CannotRevokeAReplacementLink() = runTest { stale401("approve") }

    @Test fun aLateConfirmation401CannotRevokeAReplacementLink() = runTest { stale401("confirm") }

    private suspend fun TestScope.stale401(action: String) {
        val h = Harness(this, TestClock.DEFAULT_NOW, controllerDispatcher = StandardTestDispatcher(testScheduler))
        try {
            h.seedLinked()
            val entered = CompletableDeferred<Unit>()
            val reply = CompletableDeferred<Unit>()

            suspend fun rejected(): Nothing {
                entered.complete(Unit)
                reply.await()
                throw SyncTransportException(401, "unauthorized", "old account rejected the request")
            }
            h.client.devicesHandler = { rejected() }
            h.client.rename = { _, _ -> rejected() }
            h.client.revoke = { rejected() }
            h.client.approve = { rejected() }
            h.client.confirm = { _, _ -> rejected() }
            val pending =
                async {
                    when (action) {
                        "devices" -> h.controller.devices()
                        "rename" -> h.controller.renameDevice("other-device", "Desk")
                        "revoke" -> h.controller.revokeDevice("other-device")
                        "approve" -> h.controller.approveLink("ABCD-EFGH")
                        "confirm" -> h.controller.confirmLink("ABCD-EFGH", true)
                        else -> error("Unknown action")
                    }
                }
            entered.await()
            h.identity.withLock {
                val previous = requireNotNull(h.db.syncStateDao().get())
                h.db.syncStateDao().upsert(
                    previous.copy(
                        accountId = "replacement-account",
                        deviceId = "replacement-device",
                        nodeId = "0000000000000003",
                        linkedAt = previous.linkedAt!! + 1,
                        enabled = true,
                        lastError = null,
                    ),
                )
                h.tokens.save("sync.example", "replacement-token")
            }
            reply.complete(Unit)
            val result = pending.await()
            assertEquals(SyncProblem.Revoked, (result as Outcome.Failure).error)
            val current = requireNotNull(h.db.syncStateDao().get())
            assertEquals("replacement-account", current.accountId)
            assertTrue(current.enabled, "An old request must not disable the replacement account")
            assertNull(current.lastError, "An old rejection must not mark the replacement revoked")
        } finally {
            h.close()
        }
    }
}
