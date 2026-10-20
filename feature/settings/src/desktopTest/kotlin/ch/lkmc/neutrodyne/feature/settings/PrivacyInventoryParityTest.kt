// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.PrivacyInventory
import ch.lkmc.neutrodyne.core.ui.platform.AppleLinkSettings
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class PrivacyInventoryParityTest {
    @Test fun actualPolicyIdsEqualEveryRenderedInventoryRow() =
        runComposeUiTest {
            val policy =
                generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                    .map { File(it, "PRIVACY.md") }
                    .first { it.isFile }
                    .readText()
            val ids = Regex("(?m)^\\| `([a-z-]+)` ").findAll(policy).map { it.groupValues[1] }.toList()
            assertEquals(PrivacyInventory.destinations.map { it.id }, ids)
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { PrivacyScreen(DiscoverSettingsState()) }
                }
            }
            ids.forEach { onNodeWithTag("privacy-$it").performScrollTo().assertExists() }
        }

    @Test fun privacyShowsCurrentOffKeylessAndEffectiveProviderStates() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppNavigator provides RecordingAppNavigator()) {
                    PrivacyScreen(
                        DiscoverSettingsState(
                            providers =
                                persistentListOf(
                                    ProviderInfo(ProviderId.APPLE, false),
                                    ProviderInfo(ProviderId.PODCAST_INDEX, true),
                                    ProviderInfo(ProviderId.FYYD, true),
                                ),
                        ),
                    )
                }
            }
            onNodeWithTag("privacy-state-apple").performScrollTo()
            onNodeWithText("Off").assertExists()
            onNodeWithTag("privacy-state-podcastindex").performScrollTo()
            onNodeWithText("Off: no effective key").assertExists()
            onNodeWithTag("privacy-state-fyyd").performScrollTo()
            onNodeWithText("On").assertExists()
        }

    @Test fun providedAppleLinkActionReportsMissingHandler() =
        runComposeUiTest {
            var calls = 0
            val actions =
                object : PlatformActions by DiscoverTestActions {
                    override val appleLinks =
                        AppleLinkSettings {
                            calls++
                            OpenResult.NO_HANDLER
                        }
                }
            setContent {
                CompositionLocalProvider(
                    LocalAppNavigator provides RecordingAppNavigator(),
                    LocalPlatformActions provides actions,
                ) {
                    DiscoverSettingsScreen(DiscoverSettingsState(), {}, { _, _ -> }, { _, _ -> }, {}, {})
                }
            }
            onNodeWithText("Open by default settings").performScrollTo().performClick()
            onNodeWithText("No app can open these settings.").performScrollTo().assertExists()
            assertEquals(1, calls)
        }

    @Test fun discoverEditorClearsDraftOnCancelAndMasksBothFields() =
        runComposeUiTest {
            var keyCalls = 0
            setContent {
                CompositionLocalProvider(
                    LocalAppNavigator provides RecordingAppNavigator(),
                    LocalPlatformActions provides DiscoverTestActions,
                ) {
                    DiscoverSettingsScreen(
                        DiscoverSettingsState(
                            providers =
                                persistentListOf(ProviderInfo(ProviderId.PODCAST_INDEX, true, DirectoryKeySource.USER)),
                        ),
                        {},
                        { _, _ -> },
                        { _, _ -> keyCalls++ },
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("Open by default settings").assertDoesNotExist()
            onNodeWithText("Use my own Podcast Index API key").performScrollTo().performClick()
            onNodeWithTag("pi-key").performTextInput("private-key")
            onNodeWithTag("pi-secret").performTextInput("private-secret")
            onNodeWithTag("pi-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            onNodeWithTag("pi-secret").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            onNodeWithText("Cancel").performClick()
            onNodeWithText("Use my own Podcast Index API key").performScrollTo().performClick()
            onNodeWithTag("pi-key").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
            onNodeWithTag("pi-secret").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
            assertEquals(0, keyCalls)
        }
}

private object DiscoverTestActions : PlatformActions {
    override val urls = ExternalUrlOpener { OpenResult.OPENED }
    override val share = null
    override val files =
        object : FilePicker {
            override suspend fun pickFile(
                mimeTypes: List<String>,
                extensions: List<String>,
            ): String? = null

            override suspend fun pickFolder(title: String): String? = null
        }
    override val saver = FileSaver { _, _ -> null }
    override val reveal = null
    override val notifications = null
}
