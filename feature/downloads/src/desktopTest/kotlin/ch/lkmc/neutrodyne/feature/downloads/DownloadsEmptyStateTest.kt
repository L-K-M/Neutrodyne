// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.downloads

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DownloadsEmptyStateTest {
    @Test fun onboardingOpensAutoDownloadSettings() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            val navigator = RecordingAppNavigator()
            runDesktopComposeUiTest(width = 360, height = 640) {
                setContent {
                    CompositionLocalProvider(LocalAppNavigator provides navigator) {
                        NeutrodyneTheme(
                            AppearancePrefs(dynamicColor = false),
                            SystemUiState.DEFAULT,
                        ) { DownloadsRoute() }
                    }
                }
                onNodeWithText("Auto-download settings").performClick()
                assertEquals(
                    RecordingAppNavigator.Call.Push(SettingsKey(SettingsPage.DOWNLOADS)),
                    navigator.calls.single(),
                )
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
