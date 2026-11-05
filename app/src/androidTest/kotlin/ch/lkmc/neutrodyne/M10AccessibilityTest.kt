// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeRow
import ch.lkmc.neutrodyne.core.ui.EpisodeRowStyle
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.RowCaps
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real ATF scans accompany real clicks; semantics-only actions are additional evidence. */
@ReleaseSmoke
@SdkSuppress(minSdkVersion = 34)
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class M10AccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun episodeRowsStayOperableAtNormalAndLargeText() {
        installFakeImageLoader()
        val cases =
            AuditTheme.entries.flatMap { theme ->
                listOf(1f, 2f).flatMap { font ->
                    (listOf<DownloadState?>(null) + DownloadState.entries).flatMap { download ->
                        listOf(Availability.AVAILABLE, Availability.UNAVAILABLE).map { availability ->
                            AuditRow(theme, font, download, availability)
                        }
                    }
                }
            }
        var sample by mutableStateOf(cases.first())
        var selected = 0
        compose.activity.setContent {
            val density = LocalDensity.current
            NeutrodyneTheme(
                AppearancePrefs(dynamicColor = false, pureBlack = sample.theme == AuditTheme.BLACK),
                SystemUiState(sample.theme != AuditTheme.LIGHT, 0.0, true),
            ) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, sample.font),
                    LocalUiClock provides TestClock(),
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            EpisodeRow(
                                testEpisodeRow(
                                    1,
                                    title = "Accessibility episode",
                                ).copy(availability = sample.availability),
                                RowLive(downloadState = sample.download),
                                EpisodeRowStyle.FEED,
                                RowCaps.FULL,
                                false,
                                null,
                                { if (it is EpisodeAction.Select) selected++ },
                            )
                        }
                    }
                }
            }
        }
        compose.enableAccessibilityChecks()
        for (case in cases) {
            compose.runOnIdle { sample = case }
            compose.onNodeWithText("Accessibility episode").performClick()
            val before = selected
            compose.onNodeWithText("Accessibility episode").performCustomAccessibilityActionWithLabel("Select")
            compose.runOnIdle { assertEquals(before + 1, selected) }
        }
    }
}

private enum class AuditTheme { LIGHT, DARK, BLACK }

private data class AuditRow(
    val theme: AuditTheme,
    val font: Float,
    val download: DownloadState?,
    val availability: Availability,
)
