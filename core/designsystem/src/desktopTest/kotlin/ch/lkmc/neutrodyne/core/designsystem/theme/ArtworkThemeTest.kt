// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class ArtworkThemeTest {
    @Test fun fallbackUsesCurrentPreferences() =
        runComposeUiTest {
            val prefs = mutableStateOf(AppearancePrefs(ThemeMode.DARK, dynamicColor = false))
            val seed = mutableStateOf<Int?>(0xFF0044EE.toInt())
            var app: ColorScheme? = null
            var scoped: ColorScheme? = null
            setContent {
                NeutrodyneTheme(prefs.value, SystemUiState.DEFAULT.copy(reducedMotion = true)) {
                    app = MaterialTheme.colorScheme
                    ArtworkTheme(seed.value) { scoped = MaterialTheme.colorScheme }
                }
            }
            waitUntil(timeoutMillis = 10_000) { scoped?.primary != app?.primary }
            runOnIdle { prefs.value = prefs.value.copy(pureBlack = true) }
            waitForIdle()
            assertEquals(0f, scoped!!.surface.red)
            assertNotEquals(app!!.primary, scoped!!.primary)
            runOnIdle { prefs.value = prefs.value.copy(artworkTint = false) }
            waitForIdle()
            assertEquals(app!!.primary, scoped!!.primary)
            assertEquals(app!!.surface, scoped!!.surface)
            runOnIdle { prefs.value = prefs.value.copy(theme = ThemeMode.LIGHT, pureBlack = false) }
            waitForIdle()
            assertEquals(app!!.primary, scoped!!.primary)
            assertEquals(app!!.surface, scoped!!.surface)
            runOnIdle {
                prefs.value = prefs.value.copy(artworkTint = true)
                seed.value = 0xFF888888.toInt()
            }
            waitUntil(timeoutMillis = 10_000) { scoped?.primary == app?.primary && scoped?.surface == app?.surface }
            runOnIdle { seed.value = null }
            waitForIdle()
            assertEquals(app!!.primary, scoped!!.primary)
            assertEquals(app!!.surface, scoped!!.surface)
        }

    @Test fun miniBlendFollowsLatestSeed() =
        runComposeUiTest {
            val seed = mutableStateOf<Int?>(0xFFEE4400.toInt())
            val surface = mutableStateOf(ArtworkSurface.FULL)
            var app: ColorScheme? = null
            var scoped: ColorScheme? = null
            var fullContainer: Color? = null
            setContent {
                NeutrodyneTheme(
                    AppearancePrefs(ThemeMode.DARK, dynamicColor = false),
                    SystemUiState.DEFAULT.copy(reducedMotion = true),
                ) {
                    app = MaterialTheme.colorScheme
                    ArtworkTheme(seed.value, surface.value) { scoped = MaterialTheme.colorScheme }
                }
            }
            waitUntil(timeoutMillis = 10_000) { scoped?.primary != app?.primary }
            runOnIdle {
                seed.value = 0xFF0044EE.toInt()
            }
            val expected = runBlocking { ArtworkSchemeCache().scheme(0xFF0044EE.toInt(), true, 0.0) }
            waitUntil(timeoutMillis = 10_000) { scoped?.primary == expected?.primary }
            runOnIdle {
                fullContainer = scoped!!.primaryContainer
                surface.value = ArtworkSurface.MINI
            }
            waitForIdle()
            assertEquals(app!!.primary, scoped!!.primary)
            assertEquals(
                lerp(app!!.surfaceContainerHigh, fullContainer!!, 0.12f),
                scoped!!.surfaceContainerHigh,
            )
        }
}
