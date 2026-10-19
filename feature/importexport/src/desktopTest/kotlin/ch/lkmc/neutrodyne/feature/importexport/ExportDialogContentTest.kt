// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.PreparedExport
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The essential visible actions of 08's export dialog: the platform-gated Share/Save pair, the
 * option callbacks and the warning's Continue/Cancel swap.
 */
@OptIn(ExperimentalTestApi::class)
class ExportDialogContentTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setEnglish() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test fun desktopShowsSaveOnlyAndReportsClicks() =
        runComposeUiTest {
            val clicks = mutableListOf<String>()
            content(state = readyState(), kind = PlatformKind.DESKTOP) { clicks += it }

            onNodeWithText("Export subscriptions").assertIsDisplayed()
            onNodeWithText("Save to file").assertIsDisplayed().assertIsEnabled()
            onNodeWithText("Share").assertDoesNotExist()

            onNodeWithText("Flat list").performClick()
            onNodeWithText("Include passwords for private feeds").performClick()
            onNodeWithText("Save to file").performClick()

            assertEquals(listOf("layout:FLAT", "passwords:true", "save"), clicks)
        }

    @Test fun androidShowsShareAndTheGroupShareTitle() =
        runComposeUiTest {
            val clicks = mutableListOf<String>()
            content(
                state = readyState(groupName = "tech"),
                kind = PlatformKind.ANDROID,
            ) { clicks += it }

            onNodeWithText("Share 'tech' as OPML").assertIsDisplayed()
            onNodeWithText("Save to file").assertIsDisplayed()
            onNodeWithText("Share").performClick()

            assertEquals(listOf("share"), clicks)
        }

    @Test fun theWarningSwapsOptionsForCountsAndContinueOrCancel() =
        runComposeUiTest {
            val clicks = mutableListOf<String>()
            val warning = PreparedExport("a.opml", "text/x-opml", "content://x", 5, 2, 1)
            content(state = readyState().copy(warning = warning), kind = PlatformKind.DESKTOP) {
                clicks += it
            }

            onNodeWithText("private access links for 2 feeds", substring = true).assertIsDisplayed()
            onNodeWithText("1 password in plain text", substring = true).assertIsDisplayed()
            onNodeWithText("Grouped (recommended)").assertDoesNotExist()
            onNodeWithText("Save to file").assertDoesNotExist()

            onNodeWithText("Continue").performClick()
            onNodeWithText("Cancel").performClick()

            assertEquals(listOf("confirm", "cancel"), clicks)
        }

    @Test fun busyDisablesTheDeliveryActions() =
        runComposeUiTest {
            content(state = readyState().copy(busy = true), kind = PlatformKind.DESKTOP) {}

            onNodeWithText("Save to file").assertIsNotEnabled()
        }

    private fun readyState(groupName: String? = null) =
        ExportUiState(
            ready = true,
            layout = ExportLayout.GROUPED,
            includeYouTube = true,
            includePasswords = false,
            groupName = groupName,
        )

    private fun ComposeUiTest.content(
        state: ExportUiState,
        kind: PlatformKind,
        onAction: (String) -> Unit,
    ) {
        setContent {
            CompositionLocalProvider(LocalPlatformKind provides kind) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    ExportDialogContent(
                        state = state,
                        canShare = kind == PlatformKind.ANDROID,
                        onLayout = { onAction("layout:$it") },
                        onYouTube = { onAction("youtube:$it") },
                        onPasswords = { onAction("passwords:$it") },
                        onShare = { onAction("share") },
                        onSave = { onAction("save") },
                        onConfirmWarning = { onAction("confirm") },
                        onCancelWarning = { onAction("cancel") },
                    )
                }
            }
        }
        waitForIdle()
    }
}
