// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Owner decision 2026-11-17: every screen wears the radio's paper and ink, with no other mode. */
@OptIn(ExperimentalTestApi::class)
class RadioThemeTest {
    @Test fun retiredPreferencesOsDarkModeAndArtworkLeaveTheRadioScheme() =
        runComposeUiTest {
            val prefs =
                mutableStateOf(
                    AppearancePrefs(ThemeMode.DARK, dynamicColor = true, pureBlack = true, artworkTint = true),
                )
            val system = mutableStateOf(SystemUiState(dark = true, contrast = 0.0, reducedMotion = true))
            var app: ColorScheme? = null
            var scoped: ColorScheme? = null
            setContent {
                NeutrodyneTheme(prefs.value, system.value) {
                    app = MaterialTheme.colorScheme
                    ArtworkTheme(0xFF0044EE.toInt(), ArtworkSurface.FULL) { scoped = MaterialTheme.colorScheme }
                }
            }
            waitForIdle()
            assertEquals(RadioScheme.colors(0.0), app)
            assertEquals(app, scoped)

            runOnIdle {
                prefs.value = AppearancePrefs(ThemeMode.LIGHT, dynamicColor = false)
                system.value = system.value.copy(dark = false)
            }
            waitForIdle()
            assertEquals(RadioScheme.colors(0.0), app)
            assertEquals(app, scoped)
        }

    @Test fun contrastRequestDarkensInkAndKeepsTheMaterials() {
        val standard = RadioScheme.colors(0.0)
        val high = RadioScheme.colors(1.0)
        assertNotEquals(standard.onSurface, high.onSurface)
        assertEquals(standard.surface, high.surface)
        assertTrue(high.onSurface.luminance() < standard.onSurface.luminance())
    }

    @Test fun textRolesKeepWcagAaOnPaper() {
        for (scheme in listOf(RadioScheme.colors(0.0), RadioScheme.colors(1.0))) {
            for (paper in listOf(scheme.surface, scheme.surfaceContainerHighest, scheme.surfaceContainerLowest)) {
                assertAtLeast(TEXT_AA, scheme.onSurface, paper)
                assertAtLeast(TEXT_AA, scheme.onSurfaceVariant, paper)
                assertAtLeast(TEXT_AA, scheme.primary, paper)
                assertAtLeast(TEXT_AA, scheme.error, paper)
                assertAtLeast(TEXT_AA, scheme.tertiary, paper)
                assertAtLeast(COMPONENT_AA, scheme.outline, paper)
            }
            assertAtLeast(TEXT_AA, scheme.onPrimary, scheme.primary)
            assertAtLeast(TEXT_AA, scheme.onSecondaryContainer, scheme.secondaryContainer)
            assertAtLeast(TEXT_AA, scheme.inverseOnSurface, scheme.inverseSurface)
        }
    }

    private fun assertAtLeast(
        minimum: Float,
        foreground: Color,
        background: Color,
    ) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue(ratio >= minimum, "$foreground on $background is $ratio:1, below $minimum:1")
    }

    private companion object {
        const val TEXT_AA = 4.5f
        const val COMPONENT_AA = 3f
    }
}
