// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.playback.api.MarkedPlayedElsewhere
import ch.lkmc.neutrodyne.playback.api.MarkedPlayedElsewhereSource
import kotlinx.coroutines.flow.MutableStateFlow

/** Test-owned retained mark; assign [markedPlayedElsewhere] directly. */
class FakeMarkedPlayedElsewhereSource : MarkedPlayedElsewhereSource {
    override val markedPlayedElsewhere = MutableStateFlow<MarkedPlayedElsewhere?>(null)
}
