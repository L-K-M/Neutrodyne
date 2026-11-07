// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UnavailableRowContrastTest {
    @Test fun readableMetadataKeepsNormalTextContrast() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader()
            for (theme in listOf("light", "dark", "black")) {
                for (availability in listOf(Availability.AVAILABLE, Availability.UNAVAILABLE)) {
                    for (font in listOf(1f, 2f)) {
                        runDesktopComposeUiTest(width = 360, height = 700) {
                            setContent {
                                CompositionLocalProvider(
                                    LocalDensity provides Density(1f, font),
                                    LocalUiClock provides TestClock(),
                                ) {
                                    NeutrodyneTheme(
                                        AppearancePrefs(dynamicColor = false, pureBlack = theme == "black"),
                                        SystemUiState(theme != "light", 0.0, true),
                                    ) {
                                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                                            EpisodeRow(
                                                testEpisodeRow(
                                                    1,
                                                    podcastTitle = METADATA,
                                                ).copy(availability = availability),
                                                null,
                                                EpisodeRowStyle.FEED,
                                                RowCaps.FULL,
                                                false,
                                                null,
                                                {},
                                            )
                                        }
                                    }
                                }
                            }
                            waitForIdle()
                            val pixels =
                                onNodeWithText(METADATA, substring = true, useUnmergedTree = true)
                                    .captureToImage()
                                    .toPixelMap()
                            val background = pixels[0, 0].luminance()
                            var foreground = background
                            for (y in 0 until pixels.height) {
                                for (x in 0 until pixels.width) {
                                    val luminance = pixels[x, y].luminance()
                                    foreground =
                                        if (background > .5f) min(foreground, luminance) else max(foreground, luminance)
                                }
                            }
                            val ratio = (max(background, foreground) + .05f) / (min(background, foreground) + .05f)
                            assertTrue(ratio >= 4.5f, "$theme $availability font $font metadata contrast is $ratio")
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private companion object {
        const val METADATA = "MMMMMMMMMMMM"
    }
}
