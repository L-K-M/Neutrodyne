// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ImportResumeUiTest {
    private val imports = FakeImportRepository()
    private val navigator = RecordingAppNavigator()
    private val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private lateinit var locale: Locale

    @BeforeTest fun prepare() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun cleanUp() {
        appScope.cancel()
        Locale.setDefault(locale)
    }

    private fun seed(name: String): Long =
        runBlocking {
            (imports.create(ImportSource("file:///$name", name)) as Outcome.Success).value
        }

    private fun ComposeUiTest.banner() {
        val vm = ImportResumeViewModel(imports, appScope)
        val lifecycle =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply {
                        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    }
            }
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycle,
                LocalAppNavigator provides navigator,
                LocalSnackbarHost provides SnackbarHostState(),
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) { importResumeRoute(vm) }
            }
        }
        waitUntil { vm.uiState.value.session != null }
        waitForIdle()
    }

    @Test fun multipleSessionsShowOneBannerAndNeverNavigateAutomatically() =
        runComposeUiTest {
            val first = seed("one")
            seed("two")
            banner()
            onAllNodesWithText("Finish importing 0 podcasts").assertCountEquals(1)
            assertTrue(navigator.calls.isEmpty(), "Observing open sessions must not stack previews")
            onNodeWithText("Review").performClick()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Push(ImportKey(first.toString()))),
                navigator.calls,
            )
            onAllNodesWithText("Review").assertCountEquals(0)
        }

    @Test fun discardPreviewRevealsTheNextOpenSession() =
        runComposeUiTest {
            val first = seed("one")
            val second = seed("two")
            banner()
            onNodeWithText("Discard").performClick()
            waitUntil { imports.sessions.value[first] == null }
            onNodeWithText("Review").performClick()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Push(ImportKey(second.toString()))),
                navigator.calls,
            )
        }

    @Test fun fetchingBannerOffersViewAndNoDiscard() =
        runComposeUiTest {
            val id = seed("one")
            imports.sessions.value =
                imports.sessions.value.let {
                    it + (id to it.getValue(id).copy(state = ImportState.FETCHING))
                }
            banner()
            onNodeWithText("Importing… 0 of 0").assertExists()
            onNodeWithText("Discard").assertDoesNotExist()
            onNodeWithText("View").performClick()
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Push(ImportKey(id.toString()))),
                navigator.calls,
            )
        }

    @Test fun previewBannerHasGermanActions() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            seed("one")
            banner()
            onNodeWithText("Import von 0 Podcasts abschließen").assertExists()
            onNodeWithText("Verwerfen").assertExists()
        }
}
