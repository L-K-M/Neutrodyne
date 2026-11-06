// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.integration

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.StartAtLoginError
import ch.lkmc.neutrodyne.core.model.LoginItemState
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeLoginItemRegistrar
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `DesktopIntegrationImpl` over the registrar fake (11 Start at login): the OS entry is the
 * truth the flow re-reads, the `desktop.start_at_login` key records intent, and a refusal
 * surfaces without lying about the state.
 */
class DesktopIntegrationImplTest {
    private val registrar = FakeLoginItemRegistrar()
    private val settings = FakeSettingsRepository()
    private val integration = DesktopIntegrationImpl(registrar, settings)

    @Test
    fun `the flow starts at the registrar's state`() {
        val unsupported =
            DesktopIntegrationImpl(
                FakeLoginItemRegistrar(LoginItemState.UNSUPPORTED),
                settings,
            )
        assertThat(integration.loginItem.value).isEqualTo(LoginItemState.NOT_REGISTERED)
        assertThat(unsupported.loginItem.value).isEqualTo(LoginItemState.UNSUPPORTED)
    }

    @Test
    fun `enabling registers the OS entry and records intent`() =
        runTest {
            val outcome = integration.setStartAtLogin(true)

            assertThat(outcome).isEqualTo(Outcome.Success(Unit))
            assertThat(registrar.registerCalls).isEqualTo(1)
            assertThat(integration.loginItem.value).isEqualTo(LoginItemState.REGISTERED)
            assertThat(settings.get(DesktopSettingKeys.START_AT_LOGIN)).isTrue()
        }

    @Test
    fun `disabling removes the OS entry`() =
        runTest {
            integration.setStartAtLogin(true)
            val outcome = integration.setStartAtLogin(false)

            assertThat(outcome).isEqualTo(Outcome.Success(Unit))
            assertThat(registrar.unregisterCalls).isEqualTo(1)
            assertThat(integration.loginItem.value).isEqualTo(LoginItemState.NOT_REGISTERED)
            assertThat(settings.get(DesktopSettingKeys.START_AT_LOGIN)).isFalse()
        }

    @Test
    fun `a refused write reports the failure and leaves the true state`() =
        runTest {
            registrar.registerError =
                ch.lkmc.neutrodyne.core.common
                    .LoginItemException("registry write denied")

            val outcome = integration.setStartAtLogin(true)

            assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
            assertThat((outcome as Outcome.Failure).error).isInstanceOf(StartAtLoginError.Failed::class.java)
            assertThat(integration.loginItem.value).isEqualTo(LoginItemState.NOT_REGISTERED)
        }

    @Test
    fun `an unsupported registrar never reaches register`() =
        runTest {
            val unsupported =
                DesktopIntegrationImpl(
                    FakeLoginItemRegistrar(LoginItemState.UNSUPPORTED),
                    settings,
                )
            val outcome = unsupported.setStartAtLogin(true)

            assertThat(outcome)
                .isEqualTo(Outcome.Failure(StartAtLoginError.Unsupported))
            assertThat(unsupported.loginItem.value).isEqualTo(LoginItemState.UNSUPPORTED)
        }

    @Test
    fun `a failing status read reports unsupported instead of failing construction`() {
        val registrar = FakeLoginItemRegistrar()
        registrar.statusError = IllegalStateException("registry read failed")

        val integration = DesktopIntegrationImpl(registrar, settings)

        assertThat(integration.loginItem.value).isEqualTo(LoginItemState.UNSUPPORTED)
    }

    @Test
    fun `a failing status read never fails the toggle and keeps the last known state`() =
        runTest {
            integration.setStartAtLogin(true)
            assertThat(integration.loginItem.value).isEqualTo(LoginItemState.REGISTERED)
            registrar.statusError = IllegalStateException("registry read failed")

            val outcome = integration.setStartAtLogin(false)

            // The OS write itself succeeded, so the outcome is honest; only the read-back is
            // blind, and the flow keeps the last state it could see.
            assertThat(outcome).isEqualTo(Outcome.Success(Unit))
            assertThat(registrar.unregisterCalls).isEqualTo(1)
            assertThat(integration.loginItem.value).isEqualTo(LoginItemState.REGISTERED)
            assertThat(settings.get(DesktopSettingKeys.START_AT_LOGIN)).isFalse()
        }

    @Test
    fun `a failing status read still reports a refused write`() =
        runTest {
            registrar.statusError = IllegalStateException("registry read failed")
            registrar.registerError =
                ch.lkmc.neutrodyne.core.common
                    .LoginItemException("registry write denied")

            val outcome = integration.setStartAtLogin(true)

            assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
            assertThat((outcome as Outcome.Failure).error).isInstanceOf(StartAtLoginError.Failed::class.java)
        }
}
