// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.downloads

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.StorageUsage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DownloadsEmptyStateTest {
    @Test fun emptyStateOffersTheDownloadSettingsAction() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            var calls = 0
            runDesktopComposeUiTest(width = 360, height = 640) {
                setContent {
                    CompositionLocalProvider(
                        LocalAppNavigator provides RecordingAppNavigator(),
                        LocalPlatformKind provides PlatformKind.DESKTOP,
                    ) {
                        NeutrodyneTheme(
                            AppearancePrefs(dynamicColor = false),
                            SystemUiState.DEFAULT,
                        ) {
                            DownloadsScreen(
                                DownloadsUiState(
                                    overview =
                                        DownloadsOverview(
                                            emptyList(),
                                            emptyList(),
                                            emptyList(),
                                            StorageUsage("dir:default", 0, 0, 0, null, null, false),
                                            emptySet(),
                                        ),
                                ),
                                emptyMap(),
                                {},
                                {},
                                {},
                                { _, _ -> },
                                {},
                                {},
                                {},
                                null,
                                {},
                                {},
                                {},
                                {},
                                {},
                                {},
                                {},
                                {},
                                { calls++ },
                                {},
                                {},
                            )
                        }
                    }
                }
                onNodeWithText("Download settings").performClick()
                assertEquals(1, calls)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
