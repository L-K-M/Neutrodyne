// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.ImportScreenVisibility
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `ImportKey` route's UI contract (05 Preview contract): the route parses the key's id,
 * renders the persisted session through `ImportRepository` fakes, and keeps pop ownership —
 * explicit preview cancel deletes then pops the key, while plain Back on a committed session
 * only pops. Everything the screen shows comes through the fake's flows, not call logs.
 */
@OptIn(ExperimentalTestApi::class)
class ImportRouteTest {
    private lateinit var previousLocale: Locale
    private val imports = FakeImportRepository()
    private val groups = FakeGroupRepository()
    private val visibility = ImportScreenVisibility()
    private val navigator = RecordingAppNavigator()
    private val host = SnackbarHostState()

    /** The VM's injected `@ApplicationScope` stand-in — confirm/cancel outlive the route. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val actions =
        object : PlatformActions {
            override val urls =
                ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener {
                    ch.lkmc.neutrodyne.core.ui.platform.OpenResult.OPENED
                }
            override val share = null
            override val files =
                object : ch.lkmc.neutrodyne.core.ui.platform.FilePicker {
                    override suspend fun pickFile(
                        mimeTypes: List<String>,
                        extensions: List<String>,
                    ): String? = null

                    override suspend fun pickFolder(title: String): String? = null
                }
            override val saver =
                ch.lkmc.neutrodyne.core.ui.platform
                    .FileSaver { _, _ -> "file:///unused" }
            override val reveal = null
            override val notifications = null
        }

    @BeforeTest
    fun setUp() {
        // The shared test-task config sets the JVM to de_DE; these assertions name English labels.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        appScope.cancel()
        Locale.setDefault(previousLocale)
    }

    private fun item(
        ordinal: Int,
        title: String = "Show $ordinal",
        status: ImportItemStatus = ImportItemStatus.PREVIEW,
        selected: Boolean = true,
        kind: ImportItemKind = ImportItemKind.RSS,
    ) = ImportItemView(
        ordinal = ordinal,
        title = title,
        host = "host$ordinal.example.com",
        kind = kind,
        status = status,
        selected = selected,
        selectable = status == ImportItemStatus.PREVIEW,
        groupNames = emptyList(),
        isPrivate = false,
        errorDetail = null,
        podcastId = null,
    )

    private suspend fun seed(
        sessionItems: List<ImportItemView> = listOf(item(0), item(1)),
        format: ImportFormat = ImportFormat.OPML,
        sourceName: String? = "feeds.opml",
    ): Long {
        imports.createItems = sessionItems
        imports.createFormat = format
        return (imports.create(ImportSource("file:///x", sourceName)) as Outcome.Success).value
    }

    private fun setState(
        id: Long,
        state: ImportState,
    ) {
        imports.sessions.value =
            imports.sessions.value + (
                id to
                    imports.sessions.value
                        .getValue(id)
                        .copy(state = state)
            )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setRouteContent(
        key: ImportKey,
        viewModel: ImportViewModel? = null,
    ) {
        val sessionId = key.sessionId.toLongOrNull() ?: -1L
        val vm = viewModel ?: ImportViewModel(sessionId, imports, groups, visibility, appScope)
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides ResumedLifecycleOwner()) {
                CompositionLocalProvider(
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalAppNavigator provides navigator,
                    LocalSnackbarHost provides host,
                    LocalPlatformActions provides actions,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        if (key.sessionId.toLongOrNull() == null) {
                            ImportRoute(key)
                        } else {
                            ImportSessionRoute(key, sessionId, vm)
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { vm.uiState.value.loaded }
        waitForIdle()
    }

    @Test
    fun thePreviewRendersThePersistedSessionAndRowTogglesPersist() =
        runComposeUiTest {
            val id = seed()
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("2 podcasts in feeds.opml").assertExists()
            onNodeWithText("Show 0").assertExists()
            onNodeWithText("host0.example.com").assertExists()

            onNodeWithTag("import-toggle-0").performClick()
            waitUntil(timeoutMillis = 5_000) {
                imports.items.value
                    .getValue(id)
                    .single { it.ordinal == 0 }
                    .selected
                    .not()
            }
        }

    @Test
    fun aMissingSessionShowsTheUnavailableBody() =
        runComposeUiTest {
            setRouteContent(ImportKey("99"), viewModel = ImportViewModel(99L, imports, groups, visibility, appScope))

            onNodeWithText("This import is no longer available").assertExists()
            onNodeWithText("Back").assertExists()
        }

    @Test
    fun aNonNumericKeyShowsTheUnavailableBody() =
        runComposeUiTest {
            val vm = ImportViewModel(1L, imports, groups, visibility, appScope)
            setRouteContent(ImportKey("not-a-number"), viewModel = vm)

            onNodeWithText("This import is no longer available").assertExists()
        }

    @Test
    fun aBackupPreviewShowsTheRestorePendingBody() =
        runComposeUiTest {
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("Restoring a backup isn't available in this build yet.").assertExists()
        }

    @Test
    fun confirmCommitsThroughTheRepositoryAndShowsProgress() =
        runComposeUiTest {
            val id = seed()
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("Subscribe to 2 podcasts").performClick()

            waitUntil(timeoutMillis = 5_000) {
                imports.sessions.value[id]?.state == ImportState.COMMITTED
            }
            assertTrue(imports.calls.any { it == "confirm($id)" })
            onNodeWithText("Fetched 0 of 2").assertExists()
        }

    @Test
    fun thePreviewCancelDeletesTheSessionThenPopsTheKey() =
        runComposeUiTest {
            val id = seed()
            val key = ImportKey(id.toString())
            setRouteContent(key)

            onNodeWithText("Cancel").performClick()

            waitUntil(timeoutMillis = 5_000) { imports.sessions.value[id] == null }
            assertEquals(listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.PopEntry(key)), navigator.calls)
        }

    @Test
    fun backOnAFetchingSessionOnlyPops() =
        runComposeUiTest {
            val id = seed()
            setRouteContent(ImportKey(id.toString()))
            onNodeWithText("Subscribe to 2 podcasts").performClick()
            waitUntil(timeoutMillis = 5_000) {
                imports.sessions.value[id]?.state == ImportState.COMMITTED
            }
            navigator.calls.clear()

            onNodeWithContentDescription("Back").performClick()

            waitUntil(timeoutMillis = 5_000) { navigator.calls.isNotEmpty() }
            assertEquals(listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Pop), navigator.calls)
            assertTrue(imports.calls.none { it == "cancel($id)" })
            assertEquals(ImportState.COMMITTED, imports.sessions.value[id]?.state)
        }

    @Test
    fun stoppingFetchingAsksThenCancelsTheSession() =
        runComposeUiTest {
            val id = seed()
            setRouteContent(ImportKey(id.toString()))
            setState(id, ImportState.FETCHING)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Stop", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            onNodeWithText("Stop", useUnmergedTree = true).performClick()
            onNodeWithText("Stop importing?").assertExists()

            // The destructive confirm names the rule: added podcasts stay in the library.
            onNodeWithText("Podcasts added so far stay in your library and load later.").assertExists()
            onAllNodesWithText("Stop", useUnmergedTree = true).assertCountEquals(2)[1].performClick()

            waitUntil(timeoutMillis = 5_000) {
                imports.sessions.value[id]?.state == ImportState.CANCELLED
            }
            onAllNodesWithText("Import stopped").assertCountEquals(2)
        }

    @Test
    fun aZeroSelectionStillConfirmsForMembershipOnlyWork() =
        runComposeUiTest {
            val id =
                seed(
                    sessionItems =
                        listOf(
                            item(0, status = ImportItemStatus.ALREADY_SUBSCRIBED, selected = false),
                        ),
                )
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("Subscribe").performClick()

            waitUntil(timeoutMillis = 5_000) {
                imports.sessions.value[id]?.state == ImportState.COMMITTED
            }
        }

    @Test
    fun anExistingGroupIsOfferedAsTheTarget() =
        runComposeUiTest {
            val groupId = (groups.create(GroupDraft("Tech")) as Outcome.Success).value
            val id = seed()
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("Put everything into group…").performClick()
            onNodeWithText("Tech").performClick()

            waitUntil(timeoutMillis = 5_000) {
                imports.sessions.value[id]
                    ?.options
                    ?.targetGroupId == groupId
            }
        }

    /** `createUnsafe` skips the main-thread checks so the test can resume it off the UI thread. */
    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }
}
