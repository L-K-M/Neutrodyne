// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.BackupKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackupSettingsNavigationTest {
    @Test fun englishBackupRowOpensBackupKey() = backupRowOpensBackupKey(Locale.ENGLISH, "Backup")

    @Test fun germanBackupRowOpensBackupKey() = backupRowOpensBackupKey(Locale.GERMAN, "Sicherung")

    private fun backupRowOpensBackupKey(
        locale: Locale,
        title: String,
    ) = runComposeUiTest {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        val navigator = RecordingAppNavigator()
        try {
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        SettingsHomeRoute(FakeSettingsRepository())
                    }
                }
            }
            onNodeWithText(title)
                .assertExists("M3 Settings must expose Backup")
                .performScrollTo()
                .performClick()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Push(BackupKey)),
                navigator.calls,
            )
        } finally {
            Locale.setDefault(previous)
        }
    }
}
