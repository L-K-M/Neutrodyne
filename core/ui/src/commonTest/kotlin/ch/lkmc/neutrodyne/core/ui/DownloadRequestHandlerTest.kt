// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController
import ch.lkmc.neutrodyne.core.testing.FakeDownloadController.Action
import ch.lkmc.neutrodyne.core.testing.testDownloadStatus
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.dl_interrupted
import ch.lkmc.neutrodyne.core.ui.resources.dl_paused_system
import ch.lkmc.neutrodyne.core.ui.resources.dl_queued
import ch.lkmc.neutrodyne.core.ui.resources.dl_retry_soon
import ch.lkmc.neutrodyne.core.ui.resources.dl_tap_resume
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_charging
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_network
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_storage
import ch.lkmc.neutrodyne.core.ui.resources.dl_wait_wifi
import ch.lkmc.neutrodyne.core.ui.resources.downloads_folder_missing
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.RejectReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRequestHandlerTest {
    @Test fun everyWaitUsesItsDesignTextAndDesktopNamesTheFolder() {
        val text =
            mapOf(
                WaitReason.NONE to Res.string.dl_queued,
                WaitReason.SLOT to Res.string.dl_queued,
                WaitReason.NETWORK to Res.string.dl_wait_network,
                WaitReason.UNMETERED_NETWORK to Res.string.dl_wait_wifi,
                WaitReason.CHARGING to Res.string.dl_wait_charging,
                WaitReason.STORAGE to Res.string.dl_wait_storage,
                WaitReason.BACKOFF to Res.string.dl_retry_soon,
                WaitReason.SYSTEM to Res.string.dl_paused_system,
                WaitReason.NEEDS_FOREGROUND to Res.string.dl_tap_resume,
            )
        assertEquals(WaitReason.entries.toSet(), text.keys)
        text.forEach { (wait, resource) ->
            assertEquals(
                UiText.Res(resource),
                DownloadStatusText.describe(
                    DownloadState.QUEUED,
                    wait,
                    null,
                    null,
                    null,
                    0,
                    PlatformKind.ANDROID,
                ),
            )
        }
        assertEquals(
            UiText.Res(Res.string.dl_interrupted),
            DownloadStatusText.describe(
                DownloadState.QUEUED,
                WaitReason.SYSTEM,
                null,
                null,
                null,
                0,
                PlatformKind.DESKTOP,
            ),
        )
        assertEquals(
            UiText.Res(Res.string.downloads_folder_missing),
            DownloadStatusText.describe(
                DownloadState.MISSING,
                null,
                null,
                DownloadError.STORAGE_UNAVAILABLE,
                null,
                0,
                PlatformKind.DESKTOP,
            ),
        )
    }

    @Test fun everyWaitHasTheDesignAction() {
        val expected =
            mapOf(
                WaitReason.NONE to DownloadCommand.PROMOTE,
                WaitReason.SLOT to DownloadCommand.PROMOTE,
                WaitReason.NETWORK to null,
                WaitReason.UNMETERED_NETWORK to DownloadCommand.MOBILE,
                WaitReason.CHARGING to DownloadCommand.PROMOTE,
                WaitReason.STORAGE to DownloadCommand.STORAGE,
                WaitReason.BACKOFF to DownloadCommand.RETRY,
                WaitReason.SYSTEM to DownloadCommand.PROMOTE,
                WaitReason.NEEDS_FOREGROUND to DownloadCommand.RESUME,
            )
        assertEquals(WaitReason.entries.toSet(), expected.keys)
        expected.forEach { (reason, command) ->
            assertEquals(
                command,
                downloadWaitCommand(
                    reason,
                    DownloadLane.MANUAL,
                ),
            )
        }
        assertEquals(DownloadCommand.PROMOTE, downloadWaitCommand(WaitReason.UNMETERED_NETWORK, DownloadLane.AUTO))
    }

    @Test fun meteredDismissalNeverRetriesOrWrites() =
        runTest {
            val controller =
                FakeDownloadController().apply {
                    requestResult =
                        RequestResult.NeedsMeteredDecision(2, 1_000, 1)
                }
            var writes = 0
            val handler =
                DownloadRequestHandler(
                    backgroundScope,
                    controller,
                    UserMessages(),
                    { writes++ },
                    { writes++ },
                    { throw it },
                )
            handler.request(listOf(1, 2))
            runCurrent()
            assertEquals(listOf(1L, 2L), handler.consent.value?.ids)
            handler.dismissConsent()
            runCurrent()
            assertNull(handler.consent.value)
            assertEquals(1, controller.calls.size)
            assertEquals(0, writes)
        }

    @Test fun everyConsentRetriesTheOriginalIdsAndCorrectPolicy() =
        runTest {
            for (choice in DownloadConsentChoice.entries) {
                val controller =
                    FakeDownloadController().apply {
                        requestResult =
                            RequestResult.NeedsMeteredDecision(2, 0, 2)
                    }
                var writes = 0
                val handler =
                    DownloadRequestHandler(
                        backgroundScope,
                        controller,
                        UserMessages(),
                        { writes++ },
                        {},
                        { throw it },
                    )
                handler.request(listOf(3, 2))
                runCurrent()
                controller.requestResult = RequestResult.Queued(2, 0, emptyMap(), false)
                handler.answerConsent(choice)
                runCurrent()
                assertEquals(listOf(3L, 2L), controller.calls.last().ids)
                assertEquals(choice != DownloadConsentChoice.WIFI, controller.calls.last().allowMetered)
                assertEquals(if (choice == DownloadConsentChoice.ALWAYS) 1 else 0, writes)
                assertNull(handler.consent.value)
            }
        }

    @Test fun failedAlwaysWriteNeverEnqueues() =
        runTest {
            val controller =
                FakeDownloadController().apply {
                    requestResult =
                        RequestResult.NeedsMeteredDecision(1, 0, 1)
                }
            var failures = 0
            val handler =
                DownloadRequestHandler(
                    backgroundScope,
                    controller,
                    UserMessages(),
                    { error("save") },
                    {},
                    { failures++ },
                )
            handler.request(listOf(1))
            runCurrent()
            handler.answerConsent(DownloadConsentChoice.ALWAYS)
            runCurrent()
            assertEquals(1, controller.calls.size)
            assertEquals(1, failures)
        }

    @Test fun togglesRouteAllStatesAndKeepDestructiveActionsBehindConfirmation() =
        runTest {
            val expected =
                mapOf(
                    DownloadState.RESOLVING to Action.CANCEL,
                    DownloadState.DOWNLOADING to Action.PAUSE,
                    DownloadState.PAUSED to Action.RESUME,
                    DownloadState.FAILED to Action.RETRY,
                    DownloadState.MISSING to Action.REQUEST,
                )
            for (state in DownloadState.entries) {
                val controller =
                    FakeDownloadController().apply {
                        statuses.value =
                            mapOf(1L to testDownloadStatus(state = state))
                    }
                val handler = DownloadRequestHandler(backgroundScope, controller, UserMessages(), {}, {}, { throw it })
                handler.toggle(1)
                runCurrent()
                assertEquals(expected[state], controller.calls.singleOrNull()?.action)
                assertEquals(state in setOf(DownloadState.QUEUED, DownloadState.COMPLETED), handler.menu.value != null)
            }
        }

    @Test fun deletionCancelDoesNothingAndConfirmationUsesUserReason() =
        runTest {
            val controller = FakeDownloadController()
            val handler = DownloadRequestHandler(backgroundScope, controller, UserMessages(), {}, {}, { throw it })
            handler.confirmDelete(listOf(1, 2), 800)
            handler.dismissDelete()
            handler.deleteConfirmed()
            runCurrent()
            assertTrue(controller.calls.isEmpty())
            handler.confirmDelete(listOf(2), 400)
            handler.deleteConfirmed()
            handler.deleteConfirmed()
            runCurrent()
            assertEquals(
                listOf(
                    FakeDownloadController.Call(
                        Action.DELETE,
                        listOf(2),
                        reason = DeleteReason.USER,
                    ),
                ),
                controller.calls,
            )
        }

    @Test fun notificationPromptOnlyFollowsQueuedAndAcknowledgesDismissal() =
        runTest {
            val controller =
                FakeDownloadController().apply {
                    requestResult =
                        RequestResult.Queued(1, 0, emptyMap(), true)
                }
            var prompted = 0
            val handler =
                DownloadRequestHandler(
                    backgroundScope,
                    controller,
                    UserMessages(),
                    {},
                    { prompted++ },
                    { throw it },
                )
            assertFalse(handler.notificationPrompt.value)
            handler.request(listOf(1))
            runCurrent()
            assertTrue(handler.notificationPrompt.value)
            handler.dismissNotifications()
            runCurrent()
            assertFalse(handler.notificationPrompt.value)
            assertEquals(1, prompted)
        }

    @Test fun everyRejectProducesOneActionableMessage() =
        runTest {
            for (reason in RejectReason.entries) {
                val controller =
                    FakeDownloadController().apply {
                        requestResult =
                            RequestResult.Queued(
                                0,
                                0,
                                mapOf(1L to reason, 2L to reason),
                                false,
                            )
                    }
                val messages = UserMessages()
                val handler = DownloadRequestHandler(backgroundScope, controller, messages, {}, {}, { throw it })
                handler.request(listOf(1, 2))
                runCurrent()
                assertEquals(1, messages.flow.value.size)
            }
        }

    @Test fun sizeFormattingHandlesUnknownZeroAndLargeFilesWithoutJvmApis() {
        assertEquals("0 B", downloadSize(0))
        assertEquals("999 B", downloadSize(999))
        assertEquals("1.2 MB", downloadSize(1_234_567))
        assertEquals("9.2 TB", downloadSize(9_234_567_891_234))
    }
}
