// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.integration

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
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
 *
 * A `status()` that throws (registry, IO or JNA error) never fails construction or the
 * toggle: it is logged, the initial read reports `UNSUPPORTED` (nothing is known about the
 * entry) and later reads keep the last state the flow could see (11 Start at login's OS
 * entry is the truth; inventing a state the OS did not report would lie about it).
 *
 * The links half (MD2's links slice) delegates to [DesktopLinkRegistration] — the same
 * OS-truth rule: `linkHandlers` reads what the OS says, the actions return success. A
 * `handled()` that throws (registry/JNA or filesystem read) is logged and treated like a
 * failed `status()`: unknown at construction, last-known afterwards.
 */
@SingleIn(AppScope::class)
class DesktopIntegrationImpl
    @Inject
    internal constructor(
        private val registrar: LoginItemRegistrar,
        private val links: DesktopLinkRegistration,
        private val settings: SettingsRepository,
    ) : DesktopIntegration {
        private val state = MutableStateFlow(readStatus(initial = true))
        override val loginItem: StateFlow<LoginItemState> = state

        /** Seeded from the OS and re-read after every link action (the OS entry is the truth). */
        private val linkState = MutableStateFlow(readHandled(initial = true))
        override val linkHandlers: StateFlow<Boolean?> = linkState

        override val canAddToApplicationsMenu: Boolean
            get() = links.canAddToApplicationsMenu

        override suspend fun setStartAtLogin(enabled: Boolean): Outcome<Unit, StartAtLoginError> {
            if (readStatus() == LoginItemState.UNSUPPORTED) {
                return Outcome.Failure(StartAtLoginError.Unsupported)
            }
            try {
                if (enabled) registrar.register() else registrar.unregister()
            } catch (e: LoginItemException) {
                state.value = readStatus()
                return Outcome.Failure(StartAtLoginError.Failed(e.message.orEmpty()))
            }
            // Intent survives installs; the row itself always reads the OS entry back.
            settings.set(DesktopSettingKeys.START_AT_LOGIN, enabled)
            state.value = readStatus()
            return Outcome.Success(Unit)
        }

        override suspend fun registerLinkHandlers(): Boolean = applyLinks { links.register() }

        override suspend fun addToApplicationsMenu(): Boolean = applyLinks { links.addToMenu() }

        /** Runs the action, then reports the OS state either way — like the login toggle. */
        private fun applyLinks(action: () -> Boolean): Boolean {
            val succeeded = action()
            linkState.value = readHandled()
            return succeeded
        }

        /** The OS link truth, or the documented fallback when the OS cannot answer the read. */
        private fun readHandled(initial: Boolean = false): Boolean? {
            if (!links.applicable) return null
            return runCatching { links.handled() }
                .onFailure { failure ->
                    Log.w(
                        TAG,
                        failure,
                    ) { "link handlers read failed${if (initial) " at construction" else ""}" }
                }.fold(
                    onSuccess = { it },
                    onFailure = { if (initial) null else linkState.value },
                )
        }

        /** The OS entry, or the documented fallback when the OS cannot answer the read. */
        private fun readStatus(initial: Boolean = false): LoginItemState =
            runCatching { registrar.status() }
                .onFailure { failure ->
                    Log.w(
                        TAG,
                        failure,
                    ) { "login item status read failed${if (initial) " at construction" else ""}" }
                }.fold(
                    onSuccess = { it },
                    onFailure = { if (initial) LoginItemState.UNSUPPORTED else state.value },
                )
    }

private const val TAG = "DesktopIntegration"

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
