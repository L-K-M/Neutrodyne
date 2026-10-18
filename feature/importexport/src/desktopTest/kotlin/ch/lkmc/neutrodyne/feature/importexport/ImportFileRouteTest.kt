// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator.Call
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.rememberDesktopPlatformActions
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ImportFileRouteTest {
    private lateinit var previousLocale: Locale

    @BeforeTest fun useEnglish() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test fun desktopUnreadableOffersTheFolderPrivacyHint() =
        runComposeUiTest {
            val vm = ImportFileViewModel(FakeImportRepository())
            val navigator = RecordingAppNavigator()
            setContent {
                val base = rememberDesktopPlatformActions()
                CompositionLocalProvider(
                    LocalLifecycleOwner provides ResumedLifecycleOwner(),
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalAppNavigator provides navigator,
                    LocalPlatformActions provides actions(base) { error("Native picker unavailable") },
                ) { ImportFileRoute(vm) }
            }
            waitUntil(timeoutMillis = 5_000) { vm.result.value is FileImportResult.Failed }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Privacy & Security", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size == 1
            }
            onNodeWithText("Privacy & Security", substring = true, useUnmergedTree = true).assertExists()
        }

    @Test fun chosenFileReplacesPickerWithTheCreatedSession() =
        runComposeUiTest {
            val navigator = RecordingAppNavigator()
            val vm = ImportFileViewModel(FakeImportRepository())
            setContent {
                val base = rememberDesktopPlatformActions()
                CompositionLocalProvider(
                    LocalLifecycleOwner provides ResumedLifecycleOwner(),
                    LocalPlatformActions provides actions(base) { "file:///tmp/list.opml" },
                    LocalAppNavigator provides navigator,
                ) {
                    ImportFileRoute(vm)
                }
            }
            waitUntil(timeoutMillis = 5_000) { navigator.calls.size == 2 }
            assertEquals(listOf<Call>(Call.PopEntry(ImportFileKey), Call.Push(ImportKey("1"))), navigator.calls)
        }

    @Test fun cancelOnlyClosesThePickerEntry() =
        runComposeUiTest {
            val imports = FakeImportRepository()
            val navigator = RecordingAppNavigator()
            val vm = ImportFileViewModel(imports)
            setContent {
                val base = rememberDesktopPlatformActions()
                CompositionLocalProvider(
                    LocalLifecycleOwner provides ResumedLifecycleOwner(),
                    LocalPlatformActions provides actions(base) { null },
                    LocalAppNavigator provides navigator,
                ) {
                    ImportFileRoute(vm)
                }
            }
            waitUntil(timeoutMillis = 5_000) { navigator.calls.isNotEmpty() }
            assertEquals(listOf<Call>(Call.PopEntry(ImportFileKey)), navigator.calls)
            assertEquals(emptyList<String>(), imports.calls)
        }

    @Test fun dialogErrorIsVisibleAndDismissesWithoutOpeningASession() =
        runComposeUiTest {
            val imports = FakeImportRepository()
            val navigator = RecordingAppNavigator()
            val vm = ImportFileViewModel(imports)
            setContent {
                val base = rememberDesktopPlatformActions()
                CompositionLocalProvider(
                    LocalLifecycleOwner provides ResumedLifecycleOwner(),
                    LocalPlatformActions provides actions(base) { error("Native picker unavailable") },
                    LocalAppNavigator provides navigator,
                ) {
                    ImportFileRoute(vm)
                }
            }
            waitUntil(timeoutMillis = 5_000) { vm.result.value is FileImportResult.Failed }
            onNodeWithText("Couldn’t import this file").assertExists()
            assertEquals(emptyList<Call>(), navigator.calls)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Dismiss").fetchSemanticsNodes().size == 1
            }
            onNodeWithText("Dismiss").performClick()
            assertEquals(listOf<Call>(Call.PopEntry(ImportFileKey)), navigator.calls)
            assertEquals(emptyList<String>(), imports.calls)
        }

    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle =
            LifecycleRegistry.createUnsafe(this).apply {
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
    }

    private fun actions(
        base: PlatformActions,
        choose: () -> String?,
    ): PlatformActions =
        object : PlatformActions by base {
            override val files =
                object : FilePicker {
                    override suspend fun pickFile(
                        mimeTypes: List<String>,
                        extensions: List<String>,
                    ): String? = choose()

                    override suspend fun pickFolder(title: String): String? = null
                }
        }
}
