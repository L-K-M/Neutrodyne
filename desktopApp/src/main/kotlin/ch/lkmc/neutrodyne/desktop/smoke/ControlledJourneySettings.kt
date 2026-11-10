// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.YouTubeEngineUpdatePolicy
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import java.io.IOException

/** Installs controlled-run choices in the journey's own temporary settings store. */
internal suspend fun configureControlledJourneySettings(settings: SettingsRepository) {
    // The real runner starts after five seconds. Keep its updater lanes offline while
    // preserving engine preparation and recorded extraction in this throwaway graph.
    if (settings.set(UpdateSettingKeys.CHECK_ENABLED, false) is Outcome.Failure) {
        throw IOException("Cannot disable app update checks for the controlled journey")
    }
    if (settings.set(YouTubeSettingKeys.ENGINE_UPDATES, YouTubeEngineUpdatePolicy.OFF) is Outcome.Failure) {
        throw IOException("Cannot disable engine update checks for the controlled journey")
    }
}
