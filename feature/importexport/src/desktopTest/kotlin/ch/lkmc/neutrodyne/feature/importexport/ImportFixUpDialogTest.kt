// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class ImportFixUpDialogTest {
    @Test fun secretsAndCredentialUrlsNeverEnterSavedState() {
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for (kind in ImportFixUp.entries) {
                runComposeUiTest {
                    val registry = SaveableStateRegistry(emptyMap()) { true }
                    var visible by mutableStateOf(true)
                    setContent {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                                if (visible) ImportFixUpDialog(kind, { null }, { null }, { visible = false })
                            }
                        }
                    }
                    if (kind == ImportFixUp.URL) {
                        onNodeWithTag(
                            "import-url-input",
                        ).performTextReplacement("https://u:opaque-secret@host/feed?token=private")
                    } else {
                        onAllNodes(hasSetTextAction())[0].performTextReplacement("alice")
                        onAllNodes(hasSetTextAction())[1].performTextReplacement("opaque-secret")
                    }
                    runOnIdle {
                        val saved = registry.performSave().toString()
                        assertFalse("opaque-secret" in saved)
                        assertFalse("token=private" in saved)
                        visible = false
                    }
                    waitForIdle()
                    runOnIdle { visible = true }
                    waitForIdle()
                    onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach { node ->
                        assertFalse(node.config.toString().contains("opaque-secret"))
                    }
                }
            }
        } finally {
            Locale.setDefault(locale)
        }
    }
}
