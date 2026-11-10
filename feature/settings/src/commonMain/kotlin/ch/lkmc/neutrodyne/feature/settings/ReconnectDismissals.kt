// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.ui.root.ReconnectProfile
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The in-memory "Not now" dismissals for the reconnect banner, scoped to the app graph so it
 * survives activity and window recreation but resets with a new process. Deliberately not
 * persisted: 08 hides a dismissed reconnect only for this process run.
 */
@Inject
@SingleIn(AppScope::class)
public class ReconnectDismissals {
    private val _dismissed = MutableStateFlow<Set<ReconnectProfile>>(emptySet())

    /** The profiles dismissed this process run; immutable snapshots. */
    public val dismissed: StateFlow<Set<ReconnectProfile>> = _dismissed.asStateFlow()

    public fun dismiss(profile: ReconnectProfile) {
        _dismissed.update { it + profile }
    }

    public fun isDismissed(profile: ReconnectProfile): Boolean = profile in _dismissed.value
}
