// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.integration

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.DesktopIntegration
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.StartAtLoginError
import ch.lkmc.neutrodyne.core.model.LoginItemState
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * `DesktopIntegration` over the OS's [LoginItemRegistrar] (08's frozen sketch, 11 Start at
 * login). [loginItem] is seeded from the registrar and re-read after every toggle — the OS
 * entry stays the truth, so a Task-Manager-disabled or user-removed entry shows exactly that.
 */
@SingleIn(AppScope::class)
@Inject
class DesktopIntegrationImpl(
    private val registrar: LoginItemRegistrar,
    private val settings: SettingsRepository,
) : DesktopIntegration {
    private val state = MutableStateFlow(registrar.status())
    override val loginItem: StateFlow<LoginItemState> = state

    override suspend fun setStartAtLogin(enabled: Boolean): Outcome<Unit, StartAtLoginError> {
        if (registrar.status() == LoginItemState.UNSUPPORTED) {
            return Outcome.Failure(StartAtLoginError.Unsupported)
        }
        try {
            if (enabled) registrar.register() else registrar.unregister()
        } catch (e: LoginItemException) {
            state.value = registrar.status()
            return Outcome.Failure(StartAtLoginError.Failed(e.message.orEmpty()))
        }
        // Intent survives installs; the row itself always reads the OS entry back.
        settings.set(DesktopSettingKeys.START_AT_LOGIN, enabled)
        state.value = registrar.status()
        return Outcome.Success(Unit)
    }
}

@ContributesTo(AppScope::class)
@BindingContainer
object DesktopIntegrationBindings {
    /**
     * 08's S8 optional binding: `:feature:settings` asks for `DesktopIntegration?` — a nullable
     * type is its own binding — so the desktop's impl is provided under the nullable type and
     * Android binds `null`.
     */
    @Provides
    fun desktopIntegration(impl: DesktopIntegrationImpl): DesktopIntegration? = impl
}
