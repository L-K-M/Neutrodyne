// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.DownloadAllEstimate
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.model.SettingSource
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DownloadSettingsRowsTest {
    private lateinit var previousLocale: Locale

    @BeforeTest fun englishCopy() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    private val enabled =
        SettingOverrides(
            autoDownload = true,
            autoDownloadKeepLatest = 3,
        )

    @Test fun groupDependentsRequireOwnOptInEvenWhenGlobalIsOn() =
        runDesktopComposeUiTest(
            width = 600,
            height = 900,
        ) {
            val edits = mutableListOf<DownloadSettingEdit>()
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        DownloadSettingsRows(
                            ScopedSettingsView(
                                SettingOverrides(),
                                enabled,
                                emptyMap(),
                            ),
                            DownloadSettingsScope.GROUP,
                            PlatformKind.ANDROID,
                            edits::add,
                        )
                    }
                }
            }
            onNodeWithText("Keep latest").assertIsNotEnabled().performClick()
            onNodeWithText("Delete played episodes").performScrollTo().performClick()
            onNodeWithText("Never").performClick()
            assertEquals(
                listOf<DownloadSettingEdit>(DownloadSettingEdit.Delete(DeleteAfter.NEVER)),
                edits,
            )
        }

    @Test fun controlsOfferTypedChoicesAndInheritance() =
        runDesktopComposeUiTest(
            width = 600,
            height = 1000,
        ) {
            val edits = mutableListOf<DownloadSettingEdit>()
            setContent {
                MaterialTheme {
                    DownloadSettingsRows(
                        ScopedSettingsView(
                            enabled,
                            enabled,
                            emptyMap(),
                        ),
                        DownloadSettingsScope.PODCAST,
                        PlatformKind.ANDROID,
                        edits::add,
                    )
                }
            }
            onNodeWithText("Keep latest").performClick()
            onNodeWithText("7 unplayed episodes").performClick()
            onNodeWithText("Network").performClick()
            onNodeWithText("Any network").performClick()
            onNodeWithText("Include video").performClick()
            onNodeWithText("Inherit").performClick()
            assertEquals(
                DownloadSettingEdit.Keep(7),
                edits[0],
            )
            assertEquals(
                DownloadSettingEdit.Network(NetworkPolicy.ANY),
                edits[1],
            )
            assertEquals(
                DownloadSettingEdit.Video(null),
                edits[2],
            )
        }

    @Test fun unsupportedPodcastHidesAutomaticRowsButKeepsCleanup() =
        runDesktopComposeUiTest(
            width = 600,
            height = 700,
        ) {
            setContent {
                MaterialTheme {
                    DownloadSettingsRows(
                        ScopedSettingsView(
                            enabled,
                            enabled,
                            mapOf(SettingField.AUTO_DOWNLOAD to SettingSource.NotSupported),
                        ),
                        DownloadSettingsScope.PODCAST,
                        PlatformKind.DESKTOP,
                        {},
                    )
                }
            }
            onNodeWithText("Automatic downloads").assertDoesNotExist()
            onNodeWithText("Delete played episodes").assertExists()
            onNodeWithText("Network").assertDoesNotExist()
        }

    @Test fun rejectingBulkAndOrphanDialogsNeverCallsTheirConfirm() =
        runDesktopComposeUiTest(
            width = 600,
            height = 600,
        ) {
            var confirmed = 0
            val bulk = mutableStateOf(true)
            var dismissed = 0
            setContent {
                MaterialTheme {
                    if (bulk.value) {
                        DownloadAllDialog(
                            DownloadAllState(
                                FeedSource.Group(7),
                                DownloadAllEstimate(
                                    listOf(1),
                                    999,
                                    2048,
                                    1,
                                    true,
                                ),
                                busy = false,
                            ),
                            { confirmed++ },
                            { confirmed++ },
                            {
                                bulk.value = false
                                dismissed++
                            },
                        )
                    } else {
                        OrphanFilesDialog(
                            2048,
                            false,
                            { confirmed++ },
                            { dismissed++ },
                        )
                    }
                }
            }
            onNodeWithText("1 newest of 999").assertExists()
            onNodeWithText("Cancel").performClick()
            onNodeWithText("Cancel").performClick()
            assertEquals(
                0,
                confirmed,
            )
            assertEquals(
                2,
                dismissed,
            )
        }

    @Test fun appDefaultsRemainEditableWhenAutomaticDownloadsAreOff() =
        runDesktopComposeUiTest(
            width = 600,
            height = 700,
        ) {
            val edits = mutableListOf<DownloadSettingEdit>()
            val defaults = enabled.copy(autoDownload = false)
            setContent {
                MaterialTheme {
                    DownloadSettingsRows(
                        ScopedSettingsView(
                            defaults,
                            defaults,
                            emptyMap(),
                        ),
                        DownloadSettingsScope.APP,
                        PlatformKind.DESKTOP,
                        edits::add,
                    )
                }
            }
            onNodeWithText("Network").assertDoesNotExist()
            onNodeWithText("Require charging").assertDoesNotExist()
            onNodeWithText("Keep latest").performClick()
            onNodeWithText("5 unplayed episodes").performClick()
            assertEquals(listOf<DownloadSettingEdit>(DownloadSettingEdit.Keep(5)), edits)
        }
}
