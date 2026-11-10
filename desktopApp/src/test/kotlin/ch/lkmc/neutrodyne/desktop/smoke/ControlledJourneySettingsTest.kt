// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.YouTubeEngineUpdatePolicy
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

class ControlledJourneySettingsTest {
    @Test
    fun `controlled runs disable update checks while keeping engine preparation enabled`() =
        runBlocking {
            val settings = FakeSettingsRepository()

            configureControlledJourneySettings(settings)

            assertThat(settings.get(UpdateSettingKeys.CHECK_ENABLED)).isFalse()
            assertThat(settings.get(YouTubeSettingKeys.ENGINE_UPDATES)).isEqualTo(YouTubeEngineUpdatePolicy.OFF)
            assertThat(settings.get(YouTubeSettingKeys.ENGINE_ENABLED)).isTrue()
        }

    @Test
    fun `a failed settings write stops the controlled prologue`() =
        runBlocking {
            val settings = FakeSettingsRepository().apply { failNextSet = SettingsError.WriteFailed }

            assertFailsWith<IOException> { configureControlledJourneySettings(settings) }
            Unit
        }
}
