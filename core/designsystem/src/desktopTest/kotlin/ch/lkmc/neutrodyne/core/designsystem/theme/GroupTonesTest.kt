// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.art.ArtColors
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GroupTonesTest {
    @Test fun lightAppOnLightOs() = assertContrast(ThemeMode.LIGHT, SystemUiState.DEFAULT)

    @Test fun lightAppOnDarkOs() = assertContrast(ThemeMode.LIGHT, SystemUiState.DEFAULT.copy(dark = true))

    @Test fun darkAppOnLightOs() = assertContrast(ThemeMode.DARK, SystemUiState.DEFAULT)

    @Test fun darkAppOnDarkOs() = assertContrast(ThemeMode.DARK, SystemUiState.DEFAULT.copy(dark = true))

    @Test fun systemThemeOnLightOs() = assertContrast(ThemeMode.SYSTEM, SystemUiState.DEFAULT)

    @Test fun systemThemeOnDarkOs() = assertContrast(ThemeMode.SYSTEM, SystemUiState.DEFAULT.copy(dark = true))

    private fun assertContrast(
        theme: ThemeMode,
        system: SystemUiState,
    ) = runComposeUiTest {
        val contrasts = mutableListOf<Double>()
        setContent {
            NeutrodyneTheme(AppearancePrefs(theme = theme, dynamicColor = false), system) {
                assertEquals(system, LocalSystemUiState.current)
                val surface = MaterialTheme.colorScheme.surface.toArgb()
                for (seed in GroupPalette.COLORS + listOf(null)) {
                    val tones = groupTones(seed)
                    contrasts += ArtColors.contrast(tones.dot.toArgb(), surface)
                    assertTrue(ArtColors.contrast(tones.onContainer.toArgb(), tones.container.toArgb()) >= 4.5)
                }
            }
        }
        waitForIdle()
        assertTrue(contrasts.isNotEmpty())
        assertTrue(
            contrasts.all { it >= 3.0 },
            "$theme on OS dark=${system.dark}: minimum dot contrast ${contrasts.min()}",
        )
    }
}
