// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.StartAtLoginError
import ch.lkmc.neutrodyne.core.model.LoginItemState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FakeDesktopIntegrationTest {
    @Test fun theFlowMirrorsTheOsStateTheFakeApplies() =
        runTest {
            val fake = FakeDesktopIntegration()
            assertEquals(LoginItemState.NOT_REGISTERED, fake.loginItem.value)

            assertIs<Outcome.Success<Unit>>(fake.setStartAtLogin(true))
            assertEquals(true, fake.lastEnabled)
            assertEquals(LoginItemState.REGISTERED, fake.loginItem.value)

            assertIs<Outcome.Success<Unit>>(fake.setStartAtLogin(false))
            assertEquals(false, fake.lastEnabled)
            assertEquals(LoginItemState.NOT_REGISTERED, fake.loginItem.value)
        }

    @Test fun aSeededErrorFailsWithoutChangingTheState() =
        runTest {
            val fake = FakeDesktopIntegration()
            fake.nextError = StartAtLoginError.Unsupported

            assertEquals(Outcome.Failure(StartAtLoginError.Unsupported), fake.setStartAtLogin(true))
            assertEquals(LoginItemState.NOT_REGISTERED, fake.loginItem.value)
        }

    @Test fun emitDrivesExternalChanges() =
        runTest {
            val fake = FakeDesktopIntegration()
            fake.emit(LoginItemState.DISABLED_BY_OS)
            assertEquals(LoginItemState.DISABLED_BY_OS, fake.loginItem.value)
        }
}
