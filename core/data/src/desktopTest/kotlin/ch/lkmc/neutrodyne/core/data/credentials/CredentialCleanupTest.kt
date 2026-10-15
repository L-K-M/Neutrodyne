// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `removeUnreferenced` is the failure cleanup of a credential commit (03): it runs while the
 * caller is already handling a failure, so it must never throw over that failure.
 */
class CredentialCleanupTest {
    @Test
    fun `a failing reference check neither throws nor removes the credential`() =
        runTest {
            val secrets = FakeSecretStore()
            val id = assertNotNull(secrets.put(ORIGIN, "u", "p".toCharArray()))
            // The commit failed because the database is gone: its reference check throws too.
            val db = TestDb.inMemory()
            db.close()

            secrets.removeUnreferenced(db.credentialDao(), ORIGIN, id)

            // Unknown references keep the credential; the maintenance sweep removes true orphans.
            assertNotNull(secrets.get(ORIGIN))
        }

    @Test
    fun cleanupNeverLogsSecretExceptionMessagesOrCauses() =
        runTest {
            val db = TestDb.inMemory()
            val messages = mutableListOf<String>()
            val secrets =
                object : SecretStore by FakeSecretStore() {
                    override suspend fun remove(origin: String): Unit =
                        throw IllegalStateException(
                            "synthetic-cleanup-password",
                            IllegalArgumentException("secret-cause"),
                        )
                }
            Log.install(LogSink { _, _, message -> messages += message })
            try {
                secrets.removeUnreferenced(db.credentialDao(), ORIGIN, 1L)
                assertTrue(messages.single().contains("IllegalStateException"))
                assertFalse(messages.any { "synthetic-cleanup-password" in it || "secret-cause" in it })
            } finally {
                Log.install()
                db.close()
            }
        }

    private companion object {
        const val ORIGIN = "https://a.test"
    }
}
