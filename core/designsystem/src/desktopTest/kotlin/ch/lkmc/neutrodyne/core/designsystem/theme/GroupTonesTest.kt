// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.art.ArtColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GroupTonesTest {
    @Test fun lightOs() = assertContrast(SystemUiState.DEFAULT)

    @Test fun darkOs() = assertContrast(SystemUiState.DEFAULT.copy(dark = true))

    @Test fun highContrast() = assertContrast(SystemUiState.DEFAULT.copy(contrast = 1.0))

    /** The radio's paper is the only surface, whatever the OS asks for (PO-55). */
    private fun assertContrast(system: SystemUiState) =
        runComposeUiTest {
            val contrasts = mutableListOf<Double>()
            setContent {
                NeutrodyneTheme(system) {
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
                "OS dark=${system.dark}, contrast ${system.contrast}: minimum dot contrast ${contrasts.min()}",
            )
        }
}
