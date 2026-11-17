// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMaintenance
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository

@Composable
internal fun M10ScreenContent(variant: Int) {
    when (variant) {
        // The Playback page shows the panel of counters, the dial and switches (PO-55).
        1 -> {
            PlaybackPage(
                PlatformKind.ANDROID,
                remember { PlaybackSettingsViewModel(FakeSettingsRepository(), FakePlaybackMaintenance()) },
            )
        }

        else -> {
            SettingsHomeRoute(remember { FakeSettingsRepository() })
        }
    }
}
