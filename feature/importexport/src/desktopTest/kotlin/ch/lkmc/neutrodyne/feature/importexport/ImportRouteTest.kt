// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
import ch.lkmc.neutrodyne.core.domain.ImportRepository
import ch.lkmc.neutrodyne.core.domain.ImportScreenVisibility
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupProposal
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportOptions
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val backups =
        ch.lkmc.neutrodyne.core.testing
            .FakeBackupRepository()
    private val playback =
        ch.lkmc.neutrodyne.core.testing
            .FakePlaybackController()
    private var playbackController: ch.lkmc.neutrodyne.playback.api.PlaybackController = playback
    private val groups = FakeGroupRepository()
    private val visibility = ImportScreenVisibility()
    private val navigator = RecordingAppNavigator()
    private val host = SnackbarHostState()
    private var restoreFile: String? = null
    private var restorePickCalls = 0

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
                    ): String? {
                        restorePickCalls++
                        return restoreFile
                    }

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
                            ImportSessionRoute(
                                key,
                                sessionId,
                                vm,
                                RestoreViewModel(sessionId, backups, imports, playbackController, appScope),
                            )
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { vm.uiState.value.loaded }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertDisplayedWhenReady(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun importedUrlMetadataIsSanitizedAcrossLabelsAndSemantics() =
        runComposeUiTest {
            val privateUrl = "https://alice:secret@private.example/feed?token=private-token"
            imports.createProposals = listOf(GroupProposal("private", privateUrl, 1, null, true, null))
            val title = "Listen at ftp://alice:secret@private.example/feed?token=private-token"
            val id = seed(sessionItems = listOf(item(0, title = title)), sourceName = "feeds from $privateUrl")
            setRouteContent(ImportKey(id.toString()))

            onAllNodesWithText("secret", substring = true, useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithText("private-token", substring = true, useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithContentDescription("secret", substring = true, useUnmergedTree = true).assertCountEquals(0)
            onNodeWithContentDescription("Include group private.example").assertExists()
            onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("import-toggle-0"))
            onNodeWithText("Listen at private.example").assertExists()
            onAllNodesWithText("secret", substring = true, useUnmergedTree = true).assertCountEquals(0)
            onNodeWithContentDescription("Select Listen at private.example").assertExists()
            assertEquals(
                title,
                imports.items.value
                    .getValue(id)
                    .single()
                    .title,
            )
            assertEquals(
                privateUrl,
                imports.proposals.value
                    .getValue(id)
                    .single()
                    .name,
            )
            assertEquals(
                "feeds from $privateUrl",
                imports.sessions.value
                    .getValue(id)
                    .sourceName,
            )
        }

    @Test fun excludedFoldersAndControlsHaveGermanLabels() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            imports.createProposals = listOf(GroupProposal("feeds", "feeds", 2, null, false, "wrapper"))
            val id = seed(sessionItems = listOf(item(0).copy(isPrivate = true)))
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("feeds sieht eher wie ein Ordner aus, nicht wie eine Gruppe").assertExists()
            onNodeWithContentDescription("Gruppe feeds einbeziehen").assertExists()
            onNodeWithContentDescription("Ordner als Gruppen importieren").assertExists()
            onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("import-toggle-0"))
            onNodeWithContentDescription("Show 0 auswählen").assertExists()
            onNodeWithContentDescription("Privater Feed").assertExists()
        }

    @Test fun failedPreviewCancellationKeepsRouteAndShowsError() =
        runComposeUiTest {
            val id = seed()
            val attempted = AtomicBoolean()
            val repo =
                object : ImportRepository by imports {
                    override suspend fun cancel(sessionId: Long) {
                        attempted.set(true)
                        error("disk failed")
                    }
                }
            val vm = ImportViewModel(id, repo, groups, visibility, appScope)
            setRouteContent(ImportKey(id.toString()), vm)
            onNodeWithText("Cancel").performClick()
            waitUntil(timeoutMillis = 5_000) { attempted.get() && !vm.uiState.value.busy }
            waitForIdle()

            assertEquals(emptyList(), navigator.calls, "Failed deletion must keep the preview route open")
            assertEquals(ImportState.PREVIEW, imports.sessions.value[id]!!.state)
            waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData != null }
            assertEquals("Couldn't save the change", host.currentSnackbarData!!.visuals.message)
        }

    @Test fun credentialBearingImportedTitleDisplaysHostOnly() =
        runComposeUiTest {
            val privateUrl = "https://alice:secret@host0.example.com/feed?token=private-token"
            val id = seed(sessionItems = listOf(item(0, title = "Listen at $privateUrl")))
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("Listen at $privateUrl").assertDoesNotExist()
            onNodeWithText("Listen at host0.example.com").assertExists()
            assertEquals(
                "Listen at $privateUrl",
                imports.items.value
                    .getValue(id)
                    .single()
                    .title,
            )
        }

    @Test fun excludedWrapperCanBeIncludedAndRenamedWithLocalizedExplanation() =
        runComposeUiTest {
            imports.createProposals = listOf(GroupProposal("feeds", "feeds", 2, null, false, "wrapper"))
            imports.createOptions = { ImportOptions(excludedGroupKeys = setOf("feeds")) }
            val id = seed()
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("feeds looks like a container, not a group").assertExists()
            onNodeWithContentDescription("Include group feeds").performClick()
            waitUntil(timeoutMillis = 5_000) {
                imports.sessions.value[id]!!
                    .options.excludedGroupKeys
                    .isEmpty()
            }
            onNodeWithText("Rename").performClick()
            onNodeWithText("Group name").performTextReplacement("News")
            onNodeWithText("Save").performClick()
            waitUntil(timeoutMillis = 5_000) {
                imports.sessions.value[id]!!
                    .options.renamedGroups["feeds"] == "News"
            }
        }

    @Test fun excludedGpodderSectionShowsLocalizedExplanation() =
        runComposeUiTest {
            imports.createProposals = listOf(GroupProposal("audio", "audio", 2, null, false, "gpodder_section"))
            val id = seed()
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("audio is a gPodder section, not a group").assertExists()
            onNodeWithContentDescription("Include group audio").assertExists()
            onNodeWithText("Rename").assertExists()
        }

    @Test fun selectionAndOptionControlsHaveAccessibleNames() =
        runComposeUiTest {
            val id = seed()
            setRouteContent(ImportKey(id.toString()))
            val checkbox = onNodeWithTag("import-toggle-0").fetchSemanticsNode()
            assertTrue(
                checkbox.config
                    .getOrElseNullable(
                        SemanticsProperties.ContentDescription,
                    ) { null }
                    ?.any(String::isNotBlank) ==
                    true,
                "Selection checkbox must have an accessible name",
            )
            val switches =
                onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
                ).fetchSemanticsNodes()
            assertEquals(3, switches.size)
            for (node in switches) {
                assertTrue(
                    node.config
                        .getOrElseNullable(
                            SemanticsProperties.ContentDescription,
                        ) { null }
                        ?.any(String::isNotBlank) ==
                        true,
                    "Every option switch must have an accessible name",
                )
            }
            onNodeWithContentDescription("Select Show 0").assertExists()
            onNodeWithContentDescription("Import folders as groups").assertExists()
            val treatPlayedLabel = "Treat existing episodes as played (except the newest per podcast)"
            onNodeWithContentDescription(treatPlayedLabel).assertExists()
            onNodeWithContentDescription("Notify me about new episodes for these podcasts").assertExists()
        }

    @Test fun privateFeedIconHasLocalizedAccessibleName() =
        runComposeUiTest {
            val id = seed(sessionItems = listOf(item(0).copy(isPrivate = true)))
            setRouteContent(ImportKey(id.toString()))

            onNodeWithContentDescription("Private feed").assertExists()
        }

    @Test fun progressCountsOnlyCommittedWorkAndAttemptedOutcomes() =
        runComposeUiTest {
            val id =
                seed(
                    sessionItems =
                        listOf(
                            item(0, status = ImportItemStatus.QUEUED),
                            item(1, status = ImportItemStatus.ALREADY_SUBSCRIBED, selected = false),
                            item(2, status = ImportItemStatus.INVALID_URL, selected = false),
                            item(3, selected = false),
                            item(4, status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET, selected = false),
                            item(5, status = ImportItemStatus.DUPLICATE_IN_FILE, selected = false),
                        ),
                )
            setState(id, ImportState.COMMITTED)
            setRouteContent(ImportKey(id.toString()))

            assertDisplayedWhenReady("Fetched 0 of 1")
            imports.items.value = imports.items.value + (
                id to
                    imports.items.value.getValue(id).map {
                        if (it.ordinal == 0) it.copy(status = ImportItemStatus.SUBSCRIBED) else it
                    }
            )
            waitForIdle()
            assertDisplayedWhenReady("Fetched 1 of 1")
            val outcomes =
                listOf(
                    ImportItemStatus.SUBSCRIBED,
                    ImportItemStatus.MERGED,
                    ImportItemStatus.NOT_A_FEED,
                    ImportItemStatus.NO_MEDIA,
                    ImportItemStatus.AUTH_REQUIRED,
                    ImportItemStatus.GONE,
                    ImportItemStatus.FETCH_FAILED,
                )
            imports.items.value = imports.items.value + (
                id to (
                    outcomes.mapIndexed { index, status -> item(index, status = status, selected = false) } +
                        item(7, status = ImportItemStatus.QUEUED, selected = false)
                )
            )
            waitForIdle()
            assertDisplayedWhenReady("Fetched 7 of 8")
        }

    @Test fun alreadySubscribedHasPreviewBadge() =
        runComposeUiTest {
            val existing = item(0, status = ImportItemStatus.ALREADY_SUBSCRIBED, selected = false)
            val id = seed(sessionItems = listOf(existing))
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("Already subscribed").assertExists()
        }

    @Test fun authenticationFailureExplainsPasswordIsRequired() =
        runComposeUiTest {
            val id = seed(sessionItems = listOf(item(0, status = ImportItemStatus.AUTH_REQUIRED)))
            setState(id, ImportState.DONE)
            setRouteContent(ImportKey(id.toString()))

            val description = "Password required. Enter the feed password to continue."
            assertDisplayedWhenReady(description)
        }

    @Test fun goneFeedExplainsAddressNeedsChecking() =
        runComposeUiTest {
            val id = seed(sessionItems = listOf(item(0, status = ImportItemStatus.GONE)))
            setState(id, ImportState.DONE)
            setRouteContent(ImportKey(id.toString()))

            assertDisplayedWhenReady("Feed no longer available. Check its address or remove it.")
        }

    @Test fun queuedRowShowsPendingFetch() =
        runComposeUiTest {
            val id = seed(sessionItems = listOf(item(0, status = ImportItemStatus.QUEUED)))
            setState(id, ImportState.FETCHING)
            setRouteContent(ImportKey(id.toString()))

            assertDisplayedWhenReady("Queued")
        }

    @Test fun unsupportedYoutubeDoesNotPromiseLaterRefresh() =
        runComposeUiTest {
            val unsupported =
                item(
                    0,
                    status = ImportItemStatus.YOUTUBE_UNSUPPORTED_YET,
                    selected = false,
                    kind = ImportItemKind.YOUTUBE,
                )
            val id = seed(sessionItems = listOf(unsupported))
            setState(id, ImportState.DONE)
            setRouteContent(ImportKey(id.toString()))

            val deferredHint = "YouTube isn't responding; this channel loads with a later refresh"
            onNodeWithText(deferredHint).assertDoesNotExist()
            onNodeWithText("YouTube channel — supported in a later build").assertExists()
        }

    @Test fun deferredFetchAlonePromisesLaterRefresh() =
        runComposeUiTest {
            val id =
                seed(
                    sessionItems =
                        listOf(
                            item(
                                0,
                                status = ImportItemStatus.FETCH_FAILED,
                                kind = ImportItemKind.YOUTUBE,
                            ).copy(errorDetail = "DEFERRED"),
                        ),
                )
            setState(id, ImportState.DONE)
            setRouteContent(ImportKey(id.toString()))

            val description = "YouTube isn't responding; this channel loads with a later refresh"
            assertDisplayedWhenReady(description)
            onNodeWithText("Will load later").assertExists()
        }

    @Test fun mergedSubscriptionsAreReportedSeparatelyFromAdded() =
        runComposeUiTest {
            val id = seed(sessionItems = listOf(item(0, status = ImportItemStatus.MERGED)))
            setState(id, ImportState.DONE)
            setRouteContent(ImportKey(id.toString()))

            onNodeWithText("0 added").assertExists()
            onNodeWithText("1 merged with existing subscriptions").assertExists()
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
    fun aBackupInspectErrorOffersAnotherFile() =
        runComposeUiTest {
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            setRouteContent(ImportKey(id.toString()))

            assertDisplayedWhenReady("Choose another file")
        }

    @Test fun backupPreviewShowsInspectedCountsDefaultsAndReplaceConfirmation() =
        runComposeUiTest {
            backups.inspectOutcome = Outcome.Success(restorePreview())
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            setRouteContent(ImportKey(id.toString()))
            assertDisplayedWhenReady("Listening history")
            onNodeWithText(
                "142 podcasts, 6 groups, 3214 played episodes, 12 in Up next, 8 settings.",
                substring = true,
            ).assertExists()
            onNodeWithText("Made on Android").assertExists()
            onNodeWithText("This backup includes passwords for private feeds.").assertExists()
            onNodeWithText("Listening history").assertIsOn()
            onNodeWithText("Up next").assertIsOn()
            onNodeWithText("Settings").assertIsOff()
            onNodeWithText("Replace").performClick()
            onNodeWithText("Settings").assertIsOn()
            onNodeWithText("Restore").performClick()
            assertDisplayedWhenReady(
                "Removes 9 podcasts and 2 groups that are not in the backup, " +
                    "and replaces your listening history",
            )
            assertTrue(backups.restoreRequests.isEmpty())
            onAllNodesWithText("Cancel").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
            onAllNodesWithText("Replace").onLast().performClick()
            waitUntil(timeoutMillis = 5_000) { backups.restoreRequests.size == 1 }
            assertEquals(1, playback.pauseCalls)
            assertTrue(imports.calls.none { it.startsWith("confirm(") })
        }

    @Test fun replacePausesPlaybackOnTheMainDispatcher() =
        runComposeUiTest {
            val pausedOnMain = AtomicBoolean(false)
            playbackController =
                object : ch.lkmc.neutrodyne.playback.api.PlaybackController {
                    override fun pause() {
                        pausedOnMain.set(java.awt.EventQueue.isDispatchThread())
                        playback.pause()
                    }
                }
            backups.inspectOutcome = Outcome.Success(restorePreview())
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            setRouteContent(ImportKey(id.toString()))
            assertDisplayedWhenReady("Replace")
            onNodeWithText("Replace").performClick()
            onNodeWithText("Restore").performClick()
            onAllNodesWithText("Replace").onLast().performClick()
            waitUntil(timeoutMillis = 5_000) { backups.restoreRequests.size == 1 }
            assertTrue(pausedOnMain.get(), "Playback pause must run on the main dispatcher")
        }

    @Test fun backupProgressHandsOffToTheExistingReport() =
        runComposeUiTest {
            backups.inspectOutcome = Outcome.Success(restorePreview())
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            imports.sessions.value =
                imports.sessions.value +
                (
                    id to
                        imports.sessions.value
                            .getValue(id)
                            .copy(options = ImportOptions(restoreRequestedAt = 1L))
                )
            backups.restoreProgress.value =
                ch.lkmc.neutrodyne.core.domain.RestoreProgress
                    .Running("episodes", 12, 20)
            setRouteContent(ImportKey(id.toString()))
            assertDisplayedWhenReady("Restoring… Listening history 12 of 20")
            runOnIdle {
                setState(id, ImportState.DONE)
                backups.restoreProgress.value =
                    ch.lkmc.neutrodyne.core.domain.RestoreProgress
                        .Finished(id, false)
            }
            assertDisplayedWhenReady("Import finished")
        }

    @Test fun restoreRunningIsShownInsteadOfNormalImportConfirmation() =
        runComposeUiTest {
            backups.inspectOutcome = Outcome.Success(restorePreview())
            backups.restoreOutcome = Outcome.Failure(ch.lkmc.neutrodyne.core.domain.BackupError.RestoreRunning)
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            setRouteContent(ImportKey(id.toString()))
            assertDisplayedWhenReady("Restore")
            onNodeWithText("Restore").performClick()
            assertDisplayedWhenReady("A restore is already running")
            onNodeWithText("Choose another file").assertExists()
            assertTrue(imports.calls.none { it.startsWith("confirm(") })
        }

    @Test fun inspectErrorCanChooseAnotherBackupAndPickerCancelDoesNothing() =
        runComposeUiTest {
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            setRouteContent(ImportKey(id.toString()))
            assertDisplayedWhenReady("Choose another file")
            val before = imports.sessions.value.size
            onNodeWithText("Choose another file").performClick()
            waitUntil(timeoutMillis = 5_000) { restorePickCalls == 1 }
            assertEquals(before, imports.sessions.value.size)
            assertTrue(navigator.calls.isEmpty())
            restoreFile = "file:///another.zip"
            onNodeWithText("Choose another file").performClick()
            waitUntil(timeoutMillis = 5_000) { navigator.calls.any { it is RecordingAppNavigator.Call.PushDetail } }
            assertEquals(before + 1, imports.sessions.value.size)
        }

    @Test fun newerBackupErrorIncludesUpdateAndChooseAnotherActions() =
        runComposeUiTest {
            backups.inspectOutcome =
                Outcome.Failure(
                    ch.lkmc.neutrodyne.core.domain.BackupError
                        .NewerFormat(99),
                )
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            setRouteContent(ImportKey(id.toString()))
            assertDisplayedWhenReady(
                "This backup was made by a newer version of Neutrodyne. " +
                    "Update the app to restore it.",
            )
            onNodeWithText("Choose another file").assertExists()
            onNodeWithText("Check for updates").assertExists()
        }

    @Test fun germanRestorePreviewUsesLocalizedLabels() =
        runComposeUiTest {
            Locale.setDefault(Locale.GERMAN)
            backups.inspectOutcome = Outcome.Success(restorePreview())
            val id = seed(format = ImportFormat.NEUTRODYNE_BACKUP)
            setRouteContent(ImportKey(id.toString()))
            assertDisplayedWhenReady("Hörverlauf")
            onNodeWithText("Zusammenführen").assertExists()
            onNodeWithText("Einstellungen").assertIsOff()
            onNodeWithText("Ersetzen").performClick()
            onNodeWithText("Einstellungen").assertIsOn()
        }

    @Test fun aCommittedBackupReopensItsReportWithoutLiveRestoreProgress() =
        runComposeUiTest {
            backups.inspectOutcome = Outcome.Success(restorePreview())
            val id =
                seed(
                    format = ImportFormat.NEUTRODYNE_BACKUP,
                    sessionItems =
                        listOf(
                            item(0, status = ImportItemStatus.QUEUED),
                            item(1, status = ImportItemStatus.QUEUED),
                        ),
                )
            imports.sessions.value = imports.sessions.value + (
                id to
                    imports.sessions.value.getValue(id).copy(
                        state = ImportState.COMMITTED,
                        options = ImportOptions(restoreRequestedAt = 1L),
                    )
            )
            setRouteContent(ImportKey(id.toString()))
            assertDisplayedWhenReady("Fetched 0 of 2")
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
