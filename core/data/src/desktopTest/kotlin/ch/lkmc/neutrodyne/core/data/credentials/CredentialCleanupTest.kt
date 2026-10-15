// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

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

    private companion object {
        const val ORIGIN = "https://a.test"
    }
}
